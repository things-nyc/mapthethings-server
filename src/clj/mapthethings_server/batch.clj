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
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread onto-chan
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

(defprotocol DDBprotocol
  (write-object [this bucket-name key obj]))

(defrecord DDB
  []

  DDBprotocol
  (write-object [this bucket-name key obj]
    this))

(defn make-system
  [config]
  (component/system-map
   :s3 (->S3)

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))]
    (log/info "Running batch.")
    (log/debug "(Debug logging.)")
    (let [system (component/start (make-system {}))]
      (component/stop system))))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
