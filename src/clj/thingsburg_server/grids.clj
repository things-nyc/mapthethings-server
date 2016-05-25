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
           [java.lang Math]
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

(defn constrain [min max v]
  (let [step (- max min)]
    (cond
      (< v min) (constrain min max (+ v step))
      (> v max) (constrain min max (- v step))
      :else v)))
  
(def normalize-lat (partial constrain -90.0 90.0))
(def normalize-lon (partial constrain -180.0 180.0))

(defn grid-hash-raw [lat lon level]
    (GeoHash/withBitPrecision (normalize-lat lat) (normalize-lon lon) (+ level level)))

(defn cell-hash-raw [lat lon level]
    (grid-hash-raw lat lon (+ level 5))) ; 10 extra bits

(def grid-hash (comp geohash-to-string grid-hash-raw))
(def cell-hash (comp geohash-to-string cell-hash-raw))

(defn s3-key [hash]
  (format "%s-v0" (s/reverse hash)))

(defn s3-url [hash]
  (format "https://s3.amazonaws.com/%s/%s" grid-bucket (s3-key hash)))

(defn grid-stack-hashes
  "Return the set of hashes for grids containing the lat/lon
  coordinate at scales from level0 to level20"
  [lat lon]
  (map geohash-to-string
    (for [level (range 20)]
      (grid-hash-raw lat lon level))))

(defn update-signals [cell msg]
  (let [rssi (:rssi msg)
        lsnr (:lsnr msg)
        incnil (fnil inc 0)
        avgfn (fn [a x k] (+ a (/ (- x a) (incnil k)))) ; A + (x - A) / n
        qfn (fn [q x alast anew] (+ q (* (- x alast) (- x anew)))) ; Q + (x - Alast) * (x - Anew)
        ]
    (cond-> cell
      rssi ((fn [c]
            (let [rssi-avg-old (:rssi-avg cell)
                  rssi-avg-new (avgfn rssi-avg-old rssi (:rssi-cnt cell))]
              (-> c
                (update :rssi-cnt incnil)
                (assoc :rssi-avg rssi-avg-new)
                (update :rssi-q qfn rssi rssi-avg-old rssi-avg-new)
                (#(assoc % :rssi-std (Math/sqrt (/ (:rssi-q %) (:rssi-cnt %))))))))) ; sqrt(Q / n)
      lsnr ((fn [c]
            (let [lsnr-avg-old (:lsnr-avg cell)
                  lsnr-avg-new (avgfn lsnr-avg-old lsnr (:lsnr-cnt cell))]
              (-> c
                (update :lsnr-cnt incnil)
                (assoc :lsnr-avg lsnr-avg-new)
                (update :lsnr-q  qfn lsnr lsnr-avg-old lsnr-avg-new)
                (#(assoc % :lsnr-std (Math/sqrt (/ (:lsnr-q %) (:lsnr-cnt %))))))))) ; sqrt(Q / n)
      (:ttn msg) (update :ok incnil))))

(defn update-cell [cell msg]
  (-> (update cell :pings (fnil inc 0))
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
          (log/debug "Waiting to update \"" _key "\"")
          (<! (timeout 30000))
          (if (= write (:write @dirty)) ; Same past write frame
            (let [dold @dirty
                  dnew (assoc dold :write (:count dold))
                  winner (compare-and-set! dirty dold dnew)
                  _ (if winner (log/debug "Updating \"" _key "\""))]
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
      (if (not= hash (:hash @grid-atom)) (throw (Exception. (format "Hash key [%s] does not match hash of returned grid [%]." hash (:hash @grid-atom)))))
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

(defn generate-view-grids [lat lon x-count y-count level]
  (vec
    (let [start (grid-hash-raw lat lon level)]
      (for [x (range x-count)
            y (range y-count)]
        (let [geo (if (zero? x) start ((apply comp (repeat x #(.getEasternNeighbour %))) start))
              geo (if (zero? y) geo ((apply comp (repeat y #(.getSouthernNeighbour %))) geo))]
          (geohash-to-string geo))))))

(defn view-grids
  "Return a set of hashes representing grids that would cover view"
  ([lat1 lon1 lat2 lon2]
    (view-grids lat1 lon1 lat2 lon2 0))

  ([lat1 lon1 lat2 lon2 depth]
  (loop [level 20]
    (if (zero? level)
      [(grid-hash lat1 lon1 level)]
      (let [geo1 (grid-hash-raw lat1 lon1 level)
            box1 (.getBoundingBox geo1)
            geo2 (grid-hash-raw lat2 lon2 level)
            box2 (.getBoundingBox geo2)
            ul1 (.getUpperLeft box1)
            lr1 (.getLowerRight box1)

            ulat (.getLatitude ul1)
            llat (.getLatitude (.getLowerRight box2))
            box-height (Math/abs (- ulat (.getLatitude lr1)))
            y-count (inc (int (/ (Math/abs (- ulat llat)) box-height)))

            llon (.getLongitude ul1)
            rlon (.getLongitude (.getLowerRight box2))
            rlon (if (< rlon llon) (+ rlon 360) rlon)
            box-width (Math/abs (- (.getLongitude lr1) llon))
            x-count (inc (int (/ (Math/abs (- rlon llon)) box-width)))
            ]
        #_(log/debug "x-count" x-count ", y-count" y-count)
        (if (<= x-count 4)
          (generate-view-grids lat1 lon1 x-count y-count level)
          (recur (dec level))))))))
