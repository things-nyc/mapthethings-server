(ns thingsburg-server.grids
  (:require [clojure.string :refer [blank? join trim]]
            [hickory.core :as hickory]
            [hickory.render :refer [hickory-to-html]]
            [hickory.select :as select]
            [environ.core :refer [env]]
            [clojure.data.json :as json]
            [amazonica.aws.s3 :as s3]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.core.cache :as cache]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]])
  (:import [ch.hsr.geohash GeoHash]
           [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.s3.model AmazonS3Exception]
           [com.amazonaws.regions Regions]))

(def bit-prefix "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_:")

(def raw-bucket "com.futurose.thingsburg.raw")
(def grid-bucket "com.futurose.thingsburg.grids")

(defn level-from-hash [hash]
  (quot (s/index-of bit-prefix (first hash)) 2))

(defn geohash-to-string [g]
  (format "%s%X" (get bit-prefix (.significantBits g)) (.ord g)))

(defn grid-hash-raw [lat lon level]
    (GeoHash/withBitPrecision lat lon (+ level level)))

(defn cell-hash-raw [lat lon level]
    (GeoHash/withBitPrecision lat lon (+ level level 10)))

(def grid-hash (comp geohash-to-string grid-hash-raw))
(def cell-hash (comp geohash-to-string cell-hash-raw))

(defn s3-key [hash]
  (format "%s-v0" (s/reverse hash)))

(defn bounding-hashes [lat lon]
  (map geohash-to-string
    (for [level (range 20)]
      (grid-hash-raw lat lon level))))

(defn update-signals [cell msg]
  (if (:rssi msg)
    (let [rssi (:rssi msg)
          snr (:lsnr msg)]
      (update cell :ok (fnil inc 0)))
    cell))

(defn update-cell [cell msg]
  (-> (update cell :pings inc)
    ((fn [c] (if (:timestamp msg) (update c :timestamp (:timestamp msg)) c)))
    (update-signals msg)))

(defn make-cell [lat lon level]
  (let [geohash (cell-hash-raw lat lon level)
        box (.getBoundingBox geohash)
        ul (.getUpperLeft box)
        lr (.getLowerRight box)
        center (.getBoundingBoxCenterPoint geohash)]
    {
    :hash (geohash-to-string geohash)
    :lat1 (.getLatitude ul)
    :lon1 (.getLongitude ul)
    :lat2 (.getLatitude lr)
    :lon2 (.getLongitude lr)
    :clat (.getLatitude center)
    :clon (.getLongitude center)
    ; x: x-index, y: y-index, // Position of this cell in the grid
    :pings 0
    :ok 0
    :rssi-avg 0.0
    :rssi-q 0.0
    :rssi-cnt 0
    :rssi-std 0.0
    :lsnr-avg 0.0
    :lsnr-q 0.0
    :lsnr-cnt 0
    :lsnr-std 0.0
    }))

(defn update-grid [grid msg]
  (let [level (:level grid)
        lat (:lat msg)
        lon (:lon msg)
        ch (keyword (cell-hash lat lon level))]
    (if (get-in grid [:cells ch])
      (update-in grid [:cells ch] update-cell msg)
      (assoc-in grid [:cells ch] (update-cell (make-cell lat lon level) msg)))))

(def GridCache (atom (cache/->LUCache {} {} 100)))
#_(cache/evict C :b)

(defn json->grid [json-grid]
  (json/read-str json-grid
    :key-fn keyword))

(defn grid->json [grid]
  (json/write-str grid))

(defn make-grid [hash]
  {
    :level (level-from-hash hash)
    :hash hash
    :cells {}
  })

(defn fetch-grid-s3
  "Fetch a grid from S3.
  Returns a channel with the grid on it or a newly made one."
  [hash]
  (go
    (try
      (let [obj (slurp (:input-stream
                  (s3/get-object
                    :bucket-name grid-bucket
                    :key (s3-key hash))))
            grid (json->grid obj)]
        grid)
    (catch AmazonS3Exception e
      (if (not= 404 (.getStatusCode e))
        (log/error e "Failed to get grid."))
      (make-grid hash)))))

(defn write-grid-s3 [grid]
  (go
    (try
      (let [hash (:hash grid)
            json (grid->json grid)
            bytes (.getBytes json "UTF-8")
            input-stream (java.io.ByteArrayInputStream. bytes)
            response (s3/put-object :bucket-name grid-bucket
                        :key (s3-key hash)
                        :input-stream input-stream
                        :metadata {
                          :content-type "application/json"
                          :content-length (count bytes)})]
        {:ok response})
    (catch AmazonS3Exception e
      (log/error e "Failed to write grid")
      {:error e}))))

(defn make-grid-watcher
  "Enqueues grid for write 30 seconds after it changes."
  []
  (let [dirty (atom {:count 0 :write 0})]
    (fn [_key grid-atom _old _new]
      ; Mark atom dirty - needs saving
      (let [{write :write} (swap! dirty update :count inc)]
        (go
          (log/debugf "Starting to wait to update \"" _key "\"")
          (<! (timeout 30000))
          (if (= write (:write @dirty)) ; Same past write frame
            (let [dold @dirty
                  dnew (assoc dold :write (:count dold))
                  winner (compare-and-set! dirty dold dnew)
                  _ (if winner (log/debugf "Winner of save competition! \"" _key "\""))]
              (if winner
                (write-grid-s3 @grid-atom)))))))))

(defn make-grid-atom [g]
  (let [hash (:hash g)
        grid-atom (atom g)]
    (add-watch grid-atom hash (make-grid-watcher))
    (swap! GridCache cache/miss hash grid-atom)
    grid-atom))

(defn fetch-grid
  "Fetches a grid from cache or S3.
  Returns a channel that puts the grid and closes."
  [hash]
  (if-let [grid-atom (cache/lookup @GridCache hash nil)]
    (do ; Found in cache. Mark hit and push through channel.
      (log/debug "Found grid atom for hash " hash " in cache!")
      (swap! GridCache cache/hit hash)
      (go grid-atom))
    (go
      (log/debug "Fetching hash from S3 " hash)
      (make-grid-atom (<! (fetch-grid-s3 hash))))))

(defn handle-msg [msg]
  (log/debug "handle-msg")
  (let [lat (:lat msg)
        lon (:lon msg)]
    (vec (for [level (range 20)]
      (go
        (log/debug "Handle msg " lat " x " lon " at level " level)
        (let [hash (grid-hash lat lon level)
              _ (log/debug "Hash: " hash)
              grid-atom (<! (fetch-grid hash))
              _ (log/debug "Fetched grid-atom" grid-atom)]
          (swap! grid-atom update-grid msg)))))))
