(ns mapthethings-server.batch
  (:require [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [environ.core :refer [env]]
            [com.stuartsierra.component :as component]
            [mapthethings-server.auth :as auth]
            [mapthethings-server.geo :as geo]
            [mapthethings-server.grids :as grids]
            [mapthethings-server.data :as data]
            [mapthethings-server.web :as web]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.dynamodbv2 :as ddb]
            [amazonica.aws.sqs :as sqs]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread onto-chan put!
                     alts! alts!! timeout pipeline-async]])
  (:import [ch.hsr.geohash GeoHash])
  (:gen-class))

; - Scan original message bucket and write all sample data to DDB.
; - Scan base sample layer summarizing by smallest grid cell keys. Write summaries to tile-N table.
; - For each x in N to 5, scan tile-x table write 4-tile summaries to tile-(x-1) table.
; - For each x in N-5 to 1, scan secondary index of tile-(x+5) and write grid cells to S3

(defprotocol S3protocol
  "S3 operations using channels"
  (read-json
    [this bucket-name key obj])
  (write-json
    [this bucket-name key obj]
    "Write an object as JSON. Converts incoming map to JSON and writes to S3 including Content-Type metadata.")
  (get-json-bucket
    [this bucket-name list-chan & {:keys [default-buffer-size]
                                   :or {default-buffer-size 10}}]
    "Converts every object in the given bucket from JSON to a map and puts it on the list-chan"))

; com.futurose.thingsburg.messages.test

(defn list-bucket [bucket-name list-chan]
  (go-loop [prev-response nil times 0]
    (let [opts (cond-> {:bucket-name bucket-name
                        ;:prefix "keys/start/with/this"  ; optional
                        :max-keys 10
                        :verbose true}
                prev-response (assoc :continuation-token (:next-continuation-token prev-response)))
          ; _ (println "Opts:" opts)
          ret (s3/list-objects-v2 opts)]
          ; _ (println "Ret:" ret)
          ; _ (println "Objs:" (count (:object-summaries ret)))]
      (<! (async/onto-chan list-chan (:object-summaries ret) false)) ; Leave open for continuation
      (if (and (< times 2) (:next-continuation-token ret))
        (recur ret (inc times))
        (async/close! list-chan)))))

(defrecord S3
  []

  S3protocol
  (read-json [this bucket-name key obj-chan]
    (grids/read-s3-as-json bucket-name key obj-chan))
  (write-json [this bucket-name key obj]
    (grids/write-s3-as-json bucket-name key obj))
  (get-json-bucket [this bucket-name objs-chan & {:keys [default-buffer-size]
                                                  :or {default-buffer-size 10}}]
    (let [list-chan (chan default-buffer-size)
          _ (list-bucket bucket-name list-chan)]
      (pipeline-async 1 objs-chan (partial grids/read-s3-as-json bucket-name) (async/map :key [list-chan])))))

(comment
  (def key-schema
    [{:attribute-name "id"   :key-type "HASH"}
     {:attribute-name "date" :key-type "RANGE"}])
  (def attribute-schema
    [ {:attribute-name "id"      :attribute-type "S"}
      {:attribute-name "date"    :attribute-type "N"}])
  (create-table (:ddb system) "TestTable" key-schema attribute-schema 1 1)
  (async/take! (create-table (:ddb system) "TestTable" key-schema attribute-schema 1 1) prn)

  (make-messages-table (:s3 system) (:ddb system) "com.futurose.thingsburg.messages.test" "mtt-messages-1")

  (ddb/create-table :table-name "TestTable"
              :key-schema
                [{:attribute-name "id"   :key-type "HASH"}
                 {:attribute-name "date" :key-type "RANGE"}]
              :attribute-definitions
                [{:attribute-name "id"      :attribute-type "S"}
                 {:attribute-name "date"    :attribute-type "N"}
                 {:attribute-name "column1" :attribute-type "S"}
                 {:attribute-name "column2" :attribute-type "S"}]
              :provisioned-throughput
                {:read-capacity-units 1
                 :write-capacity-units 1}))

(defprotocol DDBprotocol
  (create-table
    [this table-name key-schema attribute-schema read-units write-units]
    "Creates a table. Returns a channel that contains an error or is closed when the table becomes active.")
  (update-provisioning
    [this table-name read-units write-units]
    "Updates the provisioning of a table. Returns a channel that contains an error or is closed when the table update succeeds.")
  (write-object [this table-name obj]
    "")
  (scan-table [this table-name scan-chan]
    ""))

(defrecord DDB
  []

  DDBprotocol
  (create-table [this table-name key-schema attribute-schema read-units write-units]
    (go
      (try
        (let
          [tbl
            (ddb/create-table
              :table-name table-name
              :key-schema key-schema
              :attribute-definitions attribute-schema
              :provisioned-throughput
                { :read-capacity-units read-units
                  :write-capacity-units write-units})]
          (log/info "create-table" tbl)
          (loop [tbl (:table-description tbl)]
            (when (not= (:table-status tbl) "ACTIVE")
              ; (prn tbl)
              (<! (async/timeout 1000))
              (recur (:table (ddb/describe-table table-name))))))
            ; Otherwise nil result of (go) block - just closes channel
        (catch Exception e
          (let [error-msg (format "Failed create table [%s]." table-name)]
            (log/error e error-msg)
            {:error error-msg :exception e})))))

  (update-provisioning [this table-name read-units write-units])

  (write-object [this table-name item]
    (log/debug item)
    (ddb/put-item
          :table-name table-name
          :return-consumed-capacity "TOTAL"
          :return-item-collection-metrics "SIZE"
          :item item))

  (scan-table [this table-name scan-chan]
    (loop [opts {:table-name table-name}]
      (let [ret (ddb/scan opts)
            _ (prn ret)
            {items :items
             last-key :last-evaluated-key} ret]
        (async/onto-chan scan-chan items)
        (if last-key
          (recur (assoc opts :exclusive-start-key last-key)))))))

(def minimal-timestamp
  (let [basic-fmt (time-format/formatters :basic-date-time)
        multi-fmt (time-format/formatter time/utc
                    "yyyy-MM-dd HH:mm:ss"
                    (time-format/formatters :date-time))]
    (fn [ts]
      (when ts
        (try
          (let [dt (time-format/parse multi-fmt ts)]
            (time-format/unparse basic-fmt dt))
          (catch Exception e
            (log/error e (format "Failed to parse timestamp [%s]" ts))))))))

(defn recover-timestamp
  [msg]
  (cond
    (get-in msg [:msg :timestamp])
    msg

    (get-in msg [:raw-msg :timestamp])
    (assoc-in msg [:msg :timestamp] (get-in msg [:raw-msg :timestamp]))

    (instance? String (:raw-msg msg))
    (let [raw (json/read-str (:raw-msg msg) :key-fn keyword)]
      (-> msg
        (assoc :raw-msg raw)
        (assoc-in [:msg :timestamp] (or (get-in raw [:metadata :time]) (get-in raw [:metadata 0 :server_time])))))

    :default
    msg))

(defn workable-message
  "Return truthy for basically workable messages"
  [msg]
  (if (and (not= "ping" (:type msg))
        (not= "attempt" (:type msg))
        (not (:error msg)))
    true
    (log/warn "Skipping unworkable message:" (str msg))))

(defn enrich-message
  "Add information to msg, like :geohash and normalize :timestamp format"
  [level msg]
  (try
    (-> msg
      recover-timestamp
      (update-in [:msg :timestamp] minimal-timestamp)
      (assoc :geohash (grids/grid-hash (get-in msg [:msg :lat]) (get-in msg [:msg :lon]) level)))
    (catch Exception e
      (assoc msg :exception e :error "Failed to enrich message"))))

(defn use-message
  "Return truthy for usable messages"
  [msg]
  (if (and (not (:error msg)) (:geohash msg) (get-in msg [:msg :timestamp]) (get-in msg [:msg :lsnr]) (get-in msg [:msg :rssi]))
    true
    (log/warn "Skipping storing message:" (str msg))))

(defn storable-message
  "Returns storable version of message"
  [msg] (-> (select-keys msg [:geohash :s3key])
          (merge (select-keys (:msg msg) [:timestamp :lat :lon :lsnr :rssi]))))

(defn batch-put-items
  "TODO: Make this actually batch items and call batch put..."
  [ddb table-name & {:keys [default-buffer-size]
                     :or {default-buffer-size 10}}]
  ; Don't actually perform action in channel xf because who knows where that's running?
  ; (chan default-buffer-size (map (do (prn key %) (write-object ddb table-name %))))
  (let [c (chan default-buffer-size)]
    (go-loop []
      (when-let [item (<! c)]
        (write-object ddb table-name item)
        (recur)))
    c))

(def messages-key-schema
    [{:attribute-name "geohash"   :key-type "HASH"}
     {:attribute-name "timestamp" :key-type "RANGE"}])

(def messages-attribute-schema
    [ {:attribute-name "geohash"    :attribute-type "S"}
      {:attribute-name "timestamp"  :attribute-type "S"}])

(def cell-key-schema
    [{:attribute-name "group"   :key-type "HASH"}
     {:attribute-name "member"  :key-type "RANGE"}])

(def cell-attribute-schema
    [ {:attribute-name "group"  :attribute-type "S"}
      {:attribute-name "member" :attribute-type "S"}])

(defn make-messages-table
  "Scan S3 messages bucket putting each one in newly created messages table.
  Runs messages through a pipeline where it enriches them, plucks out only minimal attribute set, and filters out errors."
  [{:keys [s3 ddb messages-bucket-name messages-table-name default-buffer-size]
    :or {default-buffer-size 10}} level]
  (go
    (let [xf (comp (filter workable-message) (map (partial enrich-message level)) (filter use-message) (map storable-message))
          msgs-chan (chan default-buffer-size xf)
          _ (get-json-bucket s3 messages-bucket-name msgs-chan :default-buffer-size default-buffer-size) ; Start fetching messages while we create table below
          tbl-err (<! (create-table ddb messages-table-name messages-key-schema messages-attribute-schema 1 10))
          put-chan (batch-put-items ddb messages-table-name)]
      (if tbl-err
        tbl-err
        (async/pipe msgs-chan put-chan)))))

(defn partition-by-onto-chan
  "Partitions a sequence of values into sub-sequences whose elements all share the same result of application of (key-fn)."
  [key-fn]
  (fn [xf]
    (let [last-key (volatile! nil)
          values-chan (volatile! nil)]
      (fn
        ([] (xf))
        ([result]
         (let [ch @values-chan] ; Deref volatile value just once here
          (when ch (async/close! ch)))
         (xf result))
        ([result el]
         (let [key-val (key-fn el)
               [result out-chan]
               (let [ch @values-chan] ; Deref volatile value just once here
                (if (and ch (= @last-key key-val))
                  [result ch] ; Channel for this key value has already been forwarded
                  (let [new-ch (chan)]
                    (when ch (async/close! ch))
                    (vreset! values-chan new-ch)
                    (vreset! last-key key-val)
                    [(xf result new-ch) new-ch])))]
          (async/put! out-chan el)
          result))))))

        ; ([result el]
        ;  (let [key-val (key-fn el)
        ;        result (if (and @values-chan
        ;                     (= @last-key key-val))
        ;                 result ; Channel for this key value has already been forwarded
        ;                 (do
        ;                   (when @values-chan
        ;                     (async/close! @values-chan))
        ;                   (vreset! values-chan (chan))
        ;                   (vreset! last-key key-val)
        ;                   (xf result @values-chan)))]
        ;   (async/put! @values-chan el)
        ;   result))))))

(defn cell-from-messages [level cell msg]
  (let [cell (if cell cell
                (-> (grids/make-cell (:lat msg) (:lon msg) level)
                  (assoc :timestamp (:timestamp msg))))]
    (grids/update-cell cell msg)))

(defn add-group-member [cell]
  (let [[group member] (grids/split-hash (:hash cell) 1)]
    (assoc cell :group group :member member)))

(defn pipe-chan-of-channels [from-chan to-chan]
  (go-loop [] ; Can't use onto-chan because we have a channel of channels.
    (when-let [ch (<! from-chan)]
      (when-let [item (<! ch)]
        (>! to-chan item)
        (recur)))))

(defn make-messages-to-cell-table
  ""
  [{:keys [ddb messages-table-name cell-table-prefix default-buffer-size]} level]
  (let [;c (chan default-buffer-size
        ;    (comp)
        ;      (partition-by :geohash)
        ;      (map #(reduce cell-from-messages nil %))
        cell-table-name (str cell-table-prefix level)
        from-chan ; Channel of channels
          (chan default-buffer-size
            (comp
              (partition-by-onto-chan :geohash)
              (map #(async/reduce (partial cell-from-messages level) nil %))
              (map #(go (add-group-member (<! %))))))
        _ (scan-table ddb messages-table-name from-chan)
        tbl-err (<!! (create-table ddb cell-table-name cell-key-schema cell-attribute-schema 1 10))
        to-chan (batch-put-items ddb cell-table-name)]
    (if tbl-err
      tbl-err
      (pipe-chan-of-channels from-chan to-chan))))

(defn reduce-cells [level ret cell]
  (let [ret (if ret ret
                (-> (grids/make-cell (:clat cell) (:clon cell) level)
                  (assoc :group (:group cell))
                  (assoc :timestamp (:timestamp cell))))]
    (prn level ret cell)
    (doto (grids/sum-cells ret cell) prn)))

(defn make-quad-cell-table
  ""
  [{:keys [ddb cell-table-prefix default-buffer-size]} level]
  (let [in-cell-table-name (str cell-table-prefix (inc level))
        out-cell-table-name (str cell-table-prefix level)
        from-chan
          (chan default-buffer-size
            (comp
              (partition-by-onto-chan :group)
              (map #(async/reduce (partial reduce-cells level) nil %))
              (map #(go (add-group-member (<! %))))))
        _ (scan-table ddb in-cell-table-name from-chan)
        tbl-err (<!! (create-table ddb out-cell-table-name cell-key-schema cell-attribute-schema 1 10))
        to-chan (batch-put-items ddb out-cell-table-name)]
    (if tbl-err
      tbl-err
      (pipe-chan-of-channels from-chan to-chan))))

(defn rebuild-grids [system]
  (let [out (<!! (make-messages-table system 25))
        _ (log/debug "Result of make-messages-table" out)
        out (<!! (make-messages-to-cell-table system 25))
        _ (log/debug "Result of make-messages-to-cell-table" out)
        out (<!! (make-quad-cell-table system 24))
        _ (log/debug "Result of make-quad-cell-table" out)])
  (comment
    (doseq [level (range 23 5 -1)] (prn level) (<!! (make-quad-cell-table system level)))))

(defn make-system
  [config]
  (let [prefix (str "mtt-" (:era config (web/current-timestamp)) "-")]
    (component/system-map
      :prefix prefix
      :default-buffer-size 1
      :messages-bucket-name "com.futurose.thingsburg.messages.test"
      :messages-table-name (str prefix "messages")
      :cell-table-prefix (str prefix "cells-")
      :s3 (->S3)
      :ddb (->DDB))))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))]
    (log/info "Running batch.")
    (log/debug "(Debug logging.)")
    (let [system (component/start (make-system {:era "test"}))]
      (rebuild-grids system)
      (component/stop system))))

;; For interactive development:
;; (.stop server)
;; (def server (-main))

(comment
  (use 'mapthethings-server.batch :reload)
  (def list-chan (chan))
  (def system (com.stuartsierra.component/start (mapthethings-server.batch/make-system {})))
  (get-json-bucket (:s3 system) "com.futurose.thingsburg.messages.test" list-chan))


(comment
  {:msg {:msgid nil, :rssi -115.0, :appkey nil, :type "import", :lsnr 0.2, :lon 6.897752, :lat 52.217396,
          :timestamp "2016-03-05T15:46:48.000Z", :api-key "org.ttnmapper-20160531"},
    :raw-msg {:freq "868.100", :rssi "-115.00", :alt "102.0", :accuracy "7.00", :time "2016-03-05 15:46:48",
              :snr "0.20", :nodeaddr "03FFEEBB", :datarate "SF7BW125", :id "65565", :lon "6.897752", :gwaddr "AA555A000806053F",
              :lat "52.217396", :provider "gps"}, :aws-id "0002c5a0-c9a4-4de9-b9cd-3fcdcc721d76"}
  {:msg {:type "ping", :lat 51.9605040550232, :lon 5.78863620758057, :timestamp "2016-04-17 15:40:08",
          :rssi -119, :lsnr -9, :msgid nil, :appkey nil},
    :raw-msg nil, :aws-id "0002f284-5b27-40b3-a32f-f6896cd26649"}
  {:msg {:msgid nil, :rssi -120, :appkey nil, :type "import", :lsnr -10.2, :lon 5.66405296325684, :lat 52.0731782913208,
          :timestamp "2016-04-17 16:29:34", :api-key "net.ltcm.ttn-utrecht20160602"},
    :raw-msg nil, :aws-id "0003394f-db5e-47a5-9133-309584db9488"}
  {:msg {:msgid nil, :rssi -117, :appkey nil, :type "import", :lsnr -2.5, :lon 5.14771699905396, :lat 52.1662402153015,
          :timestamp "2016-03-30 13:47:26", :api-key "net.ltcm.ttn-utrecht20160602"},
    :raw-msg nil, :aws-id "0003549e-c774-46db-a837-ee7fafd46551"}
  {:msg {:msgid nil, :rssi -114.0, :appkey nil, :type "import", :lsnr 5.8, :lon 5.478725, :lat 51.492374,
          :timestamp "2016-03-17T20:09:49.000Z", :api-key "org.ttnmapper-20160531"},
    :raw-msg {:freq "868.100", :rssi "-114.00", :alt "67.0", :accuracy "3.00", :time "2016-03-17 20:09:49", :snr "5.80",
              :nodeaddr "02010507", :datarate "SF7BW125", :id "71855", :lon "5.478725", :gwaddr "FFFEB827EB6EBDC4",
              :lat "51.492374", :provider "gps"}, :aws-id "000536cc-b3e3-4c68-8c7e-99271cda1c1f"}
  {:msg {:msgid nil, :rssi -84.0, :appkey nil, :type "import", :lsnr 7.2, :lon 6.8572, :lat 52.2411,
          :timestamp "2016-02-01T09:33:52.000Z", :api-key "org.ttnmapper-20160531"},
    :raw-msg {:freq "868.500", :rssi "-84.00", :alt "NULL", :accuracy "NULL", :time "2016-02-01 09:33:52", :snr "7.20",
              :nodeaddr "02016304", :datarate "SF7BW125", :id "5421", :lon "6.857200", :gwaddr "008000000000AA78",
              :lat "52.241100", :provider "NULL"}, :aws-id "0007958b-f13d-4985-a3b4-cfbcd3bc79ac"}

; Parse failures.
; Basically, 'ping' messages with no timestamp and ttnctl entries with stringy :raw-msg and therefore no timestamp.
; Seems like exactly what we don't want in the DB!

  ; 2018-06-06 14:29:33.151:DEBUG:async-dispatch-2:mapthethings-server.batch: Skipping storing message:
  {:msg {:rssi -78.0, :bat -1, :alt 4, :hdop 1.09, :type "ttn", :dev_eui "mm-0001", :lsnr 10.2,
          :lon -73.98624552067463, :lat 40.671630581722205, :timestamp nil, :mtt true},
   :raw-msg "{\"app_id\":\"mtt\",\"dev_id\":\"mm-0001\",\"hardware_serial\":\"006158A2D06A7A4E\",\"port\":1,\"counter\":1267,\"payload_raw\":\"BTnX+MtjUwAEBEL/\",\"metadata\":{\"time\":\"2018-05-01T14:40:59.784088066Z\",\"frequency\":904.1,\"modulation\":\"LORA\",\"data_rate\":\"SF7BW125\",\"airtime\":61696000,\"coding_rate\":\"4/5\",\"gateways\":[{\"gtw_id\":\"eui-008000000000bcb0\",\"timestamp\":3071154843,\"time\":\"2018-05-01T14:40:59.663415Z\",\"channel\":1,\"rssi\":-78,\"snr\":10.2,\"rf_chain\":0,\"latitude\":40.67146,\"longitude\":-73.98627,\"altitude\":-1}]}}",
   :aws-id "0c7c61ca-a577-4dcc-ba4d-94114c008905", :geohash "y1972DE5282CF9"}
  ; 2018-06-06 14:29:28.532:DEBUG:async-dispatch-2:mapthethings-server.batch: Skipping storing message:
  {:msg {:type "ttn", :lat 36.1399373430895, :lon -115.15200738149905, :rssi -23.0, :lsnr 8.2, :timestamp nil},
   :raw-msg "{\"payload\":\"AQtmM2Mdrg==\",\"port\":1,\"counter\":1,\"dev_eui\":\"0002CC0100000011\",\"metadata\":[{\"frequency\":904.1,\"datarate\":\"SF10BW125\",\"codingrate\":\"4/5\",\"gateway_timestamp\":212467588,\"channel\":1,\"server_time\":\"2017-01-07T01:43:31.919477297Z\",\"rssi\":-23,\"lsnr\":8.2,\"rfchain\":0,\"crc\":1,\"modulation\":\"LORA\",\"gateway_eui\":\"00800000A000001C\",\"altitude\":645,\"longitude\":-115.1521,\"latitude\":36.1398}]}",
   :aws-id "0c09a43f-60a3-4ccc-b4de-67a218928dad", :geohash "y136B45AD838F8"}
  ; 2018-06-06 14:29:22.234:DEBUG:async-dispatch-6:mapthethings-server.batch: Skipping storing message:
  {:msg {:type "ttn", :lat 40.71115593416733, :lon -74.00585799197476, :rssi -99.0, :lsnr -0.2, :timestamp nil},
   :raw-msg "{\"payload\":\"AVzmOcFfyw==\",\"port\":1,\"counter\":113,\"dev_eui\":\"0000000027266BDA\",\"metadata\":[{\"frequency\":904.1,\"datarate\":\"SF10BW125\",\"codingrate\":\"4/5\",\"gateway_timestamp\":1699902564,\"channel\":1,\"server_time\":\"2017-01-08T23:51:52.950135055Z\",\"rssi\":-99,\"lsnr\":-0.2,\"rfchain\":0,\"crc\":1,\"modulation\":\"LORA\",\"gateway_eui\":\"008000000000A4F1\",\"altitude\":148,\"longitude\":-74.00596,\"latitude\":40.71076}]}",
   :aws-id "0b6882d4-bc13-4992-ba6d-df61843cf760", :geohash "y1972DDAFA7599"}
  ; 2018-06-06 14:29:15.024:DEBUG:async-dispatch-6:mapthethings-server.batch: Skipping storing message:
  {:msg {:lat 35.86221917043967, :lon 71.90721627363045, :tracked true, :type "ttn", :rssi -25.0, :lsnr 5.0, :timestamp nil},
   :raw-msg "{\"port\":1,\"counter\":5,\"payload_raw\":\"Au4AMzQiMw==\",\"metadata\":{\"time\":\"2017-01-13T04:15:26.97330752Z\",\"frequency\":868.1,\"modulation\":\"LORA\",\"data_rate\":\"SF7BW125\",\"coding_rate\":\"4/5\",\"gateways\":[{\"gtw_id\":\"ttnctl\",\"gtw_trusted\":true,\"time\":\"\",\"channel\":0,\"rssi\":-25,\"snr\":5}]}}",
   :aws-id "0a7be029-2cfc-487d-8546-c9d0acb01631", :geohash "y33C3C202483AC"}
  ; 2018-06-06 14:29:14.723:DEBUG:async-dispatch-5:mapthethings-server.batch: Skipping storing message:
  {:msg {:lat 40.671598394953115, :lon -73.98618114713645, :tracked true, :type "ttn", :rssi -46.0, :lsnr 10.5, :timestamp nil},
   :raw-msg "{\"port\":1,\"counter\":5,\"payload_raw\":\"AvXXOVZjyw==\",\"metadata\":{\"time\":\"2017-01-14T03:24:16.806367314Z\",\"frequency\":903.9,\"modulation\":\"LORA\",\"data_rate\":\"SF10BW125\",\"coding_rate\":\"4/5\",\"gateways\":[{\"gtw_id\":\"eui-008000000000bcb0\",\"gtw_trusted\":true,\"timestamp\":111229732,\"time\":\"1754-08-30T22:43:41.128654848Z\",\"channel\":0,\"rssi\":-46,\"snr\":10.5,\"latitude\":40.67189,\"longitude\":-73.98888,\"altitude\":15}]}}",
   :aws-id "0a6e16be-3739-43c3-9671-f7d0cd144ed7", :geohash "y1972DE5282E35"}
  ; 2018-06-06 14:29:08.459:DEBUG:async-dispatch-4:mapthethings-server.batch: Skipping storing message:
  {:msg {:rssi -78.0, :bat -1, :alt 12, :hdop 1.26, :type "ttn", :dev_eui "mm-0001", :lsnr 9.8, :lon -73.98628843636676, :lat 40.67148037679978, :timestamp nil, :mtt true},
   :raw-msg "{\"app_id\":\"mtt\",\"dev_id\":\"mm-0001\",\"hardware_serial\":\"006158A2D06A7A4E\",\"port\":1,\"counter\":1274,\"payload_raw\":\"BTnX6stjUQAMBOz/\",\"metadata\":{\"time\":\"2018-05-01T15:50:58.728560133Z\",\"frequency\":904.9,\"modulation\":\"LORA\",\"data_rate\":\"SF7BW125\",\"airtime\":61696000,\"coding_rate\":\"4/5\",\"gateways\":[{\"gtw_id\":\"eui-008000000000bcb0\",\"timestamp\":2975129804,\"time\":\"2018-05-01T15:50:58.608728Z\",\"channel\":5,\"rssi\":-78,\"snr\":9.8,\"rf_chain\":1,\"latitude\":40.67146,\"longitude\":-73.98627,\"altitude\":-1}]}}",
   :aws-id "09d88e75-e851-4e6e-8ac9-8b7fe5e422d0", :geohash "y1972DE5282989"}
  ; 2018-06-06 14:28:05.204:DEBUG:async-dispatch-1:mapthethings-server.batch: Skipping storing message:
  {:msgid nil, :rssi -52, :appkey nil, :type "ping",
   :aws-id "01943dc6-ade3-40fb-b10f-9b8e7ee471ee", :raw-payload nil, :lsnr 11.2, :lon -74.036471, :lat 40.756708,
   :error "Failed to enrich message"}
  ; 2018-06-06 14:28:01.638:DEBUG:async-dispatch-7:mapthethings-server.batch: Skipping storing message:
  {:msgid nil, :rssi -34, :appkey nil, :type "ping",
   :aws-id "01239cf6-59eb-4621-b924-9f5c06162774", :raw-payload nil, :lsnr 12, :lon -74.036361, :lat 40.756687,
   :error "Failed to enrich message"}
  ; 2018-06-06 14:27:53.203:DEBUG:async-dispatch-4:mapthethings-server.batch: Skipping storing message:
  {:msgid nil, :rssi -13, :appkey nil, :type "ping",
   :aws-id "005e2b09-7961-4274-bc35-96c6b26bac14", :raw-payload nil, :lsnr 10.2, :lon -74.03635, :lat 40.756697,
   :error "Failed to enrich message"}

  ; 2018-06-06 15:40:07.087:WARN:async-dispatch-4:mapthethings-server.batch: Skipping storing message:
  {:msg {:mtt true, :type "attempt", :lat 37.3376017, :lon -122.03583201, :timestamp "20170207T015717.169Z",
          :msg_seq 160, :dev_eui "289A8B03-AC7C-493A-ADBD-56912BE6EDA8", :client-ip "192.168.17.115"},
   :raw-msg "{}\n", :aws-id "06cee2b0-7621-4b05-a439-443b2711610c", :s3key "06cee2b0-7621-4b05-a439-443b2711610c", :geohash "y1364C2F1016E1"}
  ; 2018-06-06 15:40:08.083:WARN:async-dispatch-2:mapthethings-server.batch: Skipping storing message:
  {:msg {:type "attempt", :lat 37.785834, :lon -122.406417, :timestamp "20170104T014503.462Z",
          :msg_seq 0, :dev_eui "6283F596-1BBE-4611-9D46-6D91D8807313", :client-ip "127.0.0.1"},
   :raw-msg "{}\n", :aws-id "06f97ac1-07a4-4de4-9965-9da19d61c0ee", :s3key "06f97ac1-07a4-4de4-9965-9da19d61c0ee", :geohash "y13647BDC658FB"}
  ; 2018-06-06 15:40:08.337:WARN:async-dispatch-2:mapthethings-server.batch: Skipping storing message:
  {:msg {:mtt true, :type "attempt", :lat 37.48859201, :lon -122.29839276, :timestamp "20170207T044424.665Z",
          :msg_seq 126, :dev_eui "27D7BE08-3197-4CC0-BF21-282062532A6B", :client-ip "192.168.17.115"},
   :raw-msg "{}\n", :aws-id "0707499d-ab27-488d-8587-319161383f9f", :s3key "0707499d-ab27-488d-8587-319161383f9f", :geohash "y1364C4604915A"}
  ; 2018-06-06 15:40:08.577:WARN:async-dispatch-6:mapthethings-server.batch: Skipping storing message:
  {:msg {:type "attempt", :lat 37.3266182, :lon -122.02670239, :timestamp "20170104T055122.565Z",
          :msg_seq 0, :dev_eui "7AB8392F-6CA4-4BDF-ADF4-928B26AA02F4", :client-ip "0:0:0:0:0:0:0:1"},
   :raw-msg "{}\n", :aws-id "0710d244-0458-4c69-b7fc-ce4f68bcc29c", :s3key "0710d244-0458-4c69-b7fc-ce4f68bcc29c", :geohash "y1364C2E5A3CE9"}

  (amazonica.aws.dynamodbv2/scan :table-name "mtt-messages-7")
  {:count 8575, :items [], :scanned-count 8575, :last-evaluated-key {:geohash "y34192B752C475", :timestamp "20160306T143258.000Z"}}
  (amazonica.aws.dynamodbv2/scan :table-name "mtt-messages-7" :limit 5)
  :exclusive-start-key {:geohash "y34192B752C475", :timestamp "20160306T143258.000Z"})

; 20180603 Today I learned
; - onto-chan returns a channel that one needs to sit on to wait until the operation is finished.
; - #(close! c) cannot be used as completion function in put! because arity is wrong.
; -
