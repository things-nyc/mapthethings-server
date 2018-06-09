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
    [this bucket-name list-chan]
    "Converts every object in the given bucket from JSON to a map and puts it on the list-chan"))

; com.futurose.thingsburg.messages.test

(defn list-bucket [bucket-name list-chan]
  (go-loop [prev-response nil times 0]
    (let [opts (cond-> {:bucket-name bucket-name
                        ;:prefix "keys/start/with/this"  ; optional
                        :max-keys 2
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
  (get-json-bucket [this bucket-name objs-chan]
    (let [list-chan (chan)
          _ (list-bucket bucket-name list-chan)]
      ; (async/pipe (async/map :key [list-chan]) objs-chan))))
      ; (pipeline-async 1 objs-chan #(go (>! %2 %1) (close! %2)) list-chan))))
      (pipeline-async 1 objs-chan (partial grids/read-s3-as-json bucket-name) (async/map :key [list-chan])))))

(comment
  (def key-schema
    [{:attribute-name "id"   :key-type "HASH"}
     {:attribute-name "date" :key-type "RANGE"}])
  (def attribute-schema
    [ {:attribute-name "id"      :attribute-type "S"}
      {:attribute-name "date"    :attribute-type "N"}])
  (create-table (:ddb system) "TestTable" key-schema attribute-schema 1 1)
  (async/take! (create-table (:ddb system) "TestTable" key-schema attribute-schema 1 1)) prn

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
  (write-object [this table-name key obj]))

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
          (log/debug "create-table" tbl)
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
  (write-object [this table-name key obj]))

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

(defn make-system
  [config]
  (component/system-map
   :s3 (->S3)
   :ddb (->DDB)))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))]
    (log/info "Running batch.")
    (log/debug "(Debug logging.)")
    (let [system (component/start (make-system {}))]
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
              :lat "52.241100", :provider "NULL"}, :aws-id "0007958b-f13d-4985-a3b4-cfbcd3bc79ac"})


; 20180603 Today I learned
; - onto-chan returns a channel that one needs to sit on to wait until the operation is finished.
; - #(close! c) cannot be used as completion function in put! because arity is wrong.
; -
