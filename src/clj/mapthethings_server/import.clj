(ns mapthethings-server.import
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
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
  (:import [java.io Reader]
           [java.lang IllegalArgumentException]))


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

(def utc-formatter (time-format/formatters :date-time))
(def csv-formatter (time-format/formatter "yyyy-MM-dd HH:mm:ss"))

(defn try-parse-time [f t]
  (try
    (time-format/parse f t)
  (catch IllegalArgumentException e
    nil)))

(defn parse-time [t]
  (if-let [parsed (or (try-parse-time utc-formatter t) (try-parse-time csv-formatter t))]
    (time-format/unparse utc-formatter parsed)
    t))

(defn parse-number [n]
  (cond
    (number? n) n
    :else (edn/read-string n)))

(defn parse-sample
  [sample]
  (let [[lat lon] (lat-lon sample)
        timestamp (or (:timestamp sample) (:time sample))
        timestamp (parse-time timestamp)]
    (if (or (nil? lat) (nil? lon))
      [nil, (format "Invalid sample lat/lon: %s" (string/join "," (map #(str (% sample)) [:gps :coordinates :coords :latitude :lat :longitude :lon :lng])))]
      [{
        :type "import"
        :lat lat :lon lon
        :timestamp timestamp
        :rssi (parse-number (:rssi sample))
        :lsnr (parse-number (or (:lsnr sample) (:snr sample)))
        :msgid (:msgid sample)
        :appkey (:appkey sample)
      }, sample])))

(defn parse-json-file
  "Parses the JSON file and returns a sequence of [msg raw-msg] pairs."
  [filename]
  (let [text (slurp filename)
        arr (json/read-str text :key-fn keyword)]
    (if (map? arr)
      (throw (Exception. "Cannot handle JSON map"))
      (map parse-sample (seq arr)))))

(defn lazy-close
  "Returns the input sequence and closes the file at end of sequence. Based on http://stackoverflow.com/a/13312151/1207583"
  [file sq]
  (letfn [(helper [sq]
            (lazy-seq
              (if (empty? sq)
                (do (.close file) nil)
                (cons (first sq) (helper (rest sq))))))]
    (helper sq)))

(defn parse-csv-file
  "Parses the CSV file and returns a sequence of [msg raw-msg] pairs."
  [filename]
  (let [in-file (io/reader filename)
        csv (lazy-close in-file (csv/read-csv in-file :separator \tab))
        header (mapv keyword (first csv))]
    (map #(parse-sample (zipmap header %)) (rest csv))))

(defn is-json [filename]
  (string/includes? filename ".json"))

(defn import-file
  "Decides whether to use JSON or CSV parsing"
  [filename api-key]
  (log/info "Importing:" filename)
  (post-data (if (is-json filename) (parse-json-file filename) (parse-csv-file filename)) api-key))

(defn -main [& [filename api-key]]
  (cond
    (or (nil? filename) (string/blank? filename)) (log/error "Filename required")
    (or (nil? api-key) (string/blank? api-key)) (log/error "API key required")
    :else (import-file filename api-key)))
