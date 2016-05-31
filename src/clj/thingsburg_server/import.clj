(ns thingsburg-server.import
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [environ.core :refer [env]]
            [thingsburg-server.web :as post]
            [thingsburg-server.grids :as grids]
            [thingsburg-server.data :as data]
            [clojurewerkz.machine-head.client :as mh]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  (:import [java.io Reader]))

(defn post-data [data]
  (doseq [[msg raw] data]
    (post/handle-msg msg raw)))

(defn parse-sample
  [sample]
  (let [coords (:gps sample (:coordinates sample (:coords sample)))
        [slat slon] (if (some? coords) (string/split coords #"[,/]" 2))
        slat (:latitude sample (:lat sample slat))
        lat (data/parse-lat slat)
        slon (:longitude sample (:lng sample (:lon sample slon)))
        lon (data/parse-lon slon)]
    (if (or (nil? lat) (nil? lon))
      [nil, (format "Invalid ping lat/lon: %s/%s" slat slon)]
      [{
        :type "ping"
        :lat lat :lon lon
        :timestamp (or (:timestamp sample) (:time sample))
        :rssi (:rssi sample)
        :lsnr (or (:lsnr sample) (:snr sample))
        :msgid (:msgid sample)
        :appkey (:appkey sample)
      }, nil])))

(defn parse-file
  "Parses the file and returns a sequence of [msg raw-msg] pairs."
  [filename]
  (let [text (slurp filename)
        arr (json/read-str text :key-fn keyword)]
    (if (map? arr)
      (throw (Exception. "Cannot handle JSON map"))
      (map parse-sample (seq arr)))))

(defn -main [& [filename]]
  (let []
    (log/info "Importing:" filename)
    (post-data (parse-file filename))
    (<!! (go (<! (timeout 45000))))))
