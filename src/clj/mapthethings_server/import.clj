(ns mapthethings-server.import
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [environ.core :refer [env]]
            [mapthethings-server.web :as post]
            [mapthethings-server.grids :as grids]
            [mapthethings-server.data :as data]
            [clojurewerkz.machine-head.client :as mh]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  (:import [java.io Reader]))

(defn post-data [data api-key]
  (doseq [[msg raw] data]
    (post/handle-msg (assoc msg :api-key api-key) raw)))

(defn lat-lon
  "Extract lat/lon from sample."
  [sample]
  (let [coords (:gps sample (:coordinates sample (:coords sample)))
        [lat lon] (if (vector? coords)
                    [(get coords 0) (get coords 1)]
                    (let [[slat slon] (if (and (string? coords) (some? coords)) (string/split coords #"[,/]" 2))
                          slat (:latitude sample (:lat sample slat))
                          slon (:longitude sample (:lng sample (:lon sample slon)))]
                      [slat slon]))]
    [(data/parse-lat lat) (data/parse-lon lon)]))

(defn parse-sample
  [sample]
  (let [[lat lon] (lat-lon sample)]
    (if (or (nil? lat) (nil? lon))
      [nil, (format "Invalid sample lat/lon: %s" (string/join "," (map #(str (% sample)) [:gps :coordinates :coords :latitude :lat :longitude :lon :lng])))]
      [{
        :type "import"
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

(defn -main [& [filename api-key]]
  (cond
    (or (nil? filename) (string/blank? filename)) (log/error "Filename required")
    (or (nil? api-key) (string/blank? api-key)) (log/error "API key required")
    :else (do
      (log/info "Importing:" filename)
      (post-data (parse-file filename) api-key)
      (<!! (go (<! (timeout 45000)))))))
