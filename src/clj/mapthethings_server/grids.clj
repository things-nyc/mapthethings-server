(ns mapthethings-server.grids
  (:require [clojure.edn :as edn]
            [environ.core :refer [env]]
            [clojure.data.json :as json]
            [amazonica.aws.s3 :as s3]
            [mapthethings-server.stats :as stats]
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

(def raw-bucket (or (env :messages-bucket-name) "nyc.thethings.map.messages"))
(def grid-bucket (or (env :grids-bucket-name) "nyc.thethings.map.grids"))

(defn bit-length-from-hash [hash]
  (s/index-of bit-prefix (first hash)))

(defn level-from-hash [hash]
  (quot (bit-length-from-hash hash) 2))

(def hexLookup
  {\0 "0000" \1 "0001" \2 "0010" \3 "0011"
   \4 "0100" \5 "0101" \6 "0110" \7 "0111"
   \8 "1000" \9 "1001" \A "1010" \B "1011"
   \C "1100" \D "1101" \E "1110" \F "1111"})

(defn hex2bin [hex]
  (s/join (map hexLookup (seq hex))))

(defn hash2geo [hash]
  (let [bits (bit-length-from-hash hash)
        bin (hex2bin (subs hash 1))
        bin-len (count bin)
        bin (cond
              (< bits bin-len) (subs bin (- bin-len bits)) ; Chop extra from front
              (> bits bin-len) (str (apply str (repeat (- bits bin-len) \0)) bin) ; Prefix with 0's
              :else bin)]
    (GeoHash/fromBinaryString bin)))

(defn coordinate-from-hash [hash]
  (let [geo (hash2geo hash)
        center (.getBoundingBoxCenterPoint geo)]
    {:level (level-from-hash hash)
     :lat (.getLatitude center)
     :lon (.getLongitude center)}))

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

(def incnil (fnil inc 0))

(defn update-signals [cell msg]
  (let [rssi (:rssi msg)
        lsnr (:lsnr msg)]
    (cond-> cell
      rssi (update :rssi stats/cummulative-stat rssi)
      lsnr (update :lsnr stats/cummulative-stat lsnr))))

(defn update-cell [cell msg]
  (-> cell
    (update-signals msg)
    (cond->
      (not= "attempt" (:type msg)) (update :count incnil)
      (= "ttn" (:type msg))     (update :ttn-cnt incnil)
      (= "api" (:type msg))     (update :api-cnt incnil)
      (= "import" (:type msg))  (update :import-cnt incnil)
      (= "attempt" (:type msg)) (update :attempt-cnt incnil)
      (:tracked msg)            (update :success-cnt incnil)
      (:timestamp msg)        (assoc :timestamp (:timestamp msg)))))

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
     :count 0
     :ttn-cnt 0
     :api-cnt 0
     :import-cnt 0
     :attempt-cnt 0
     :success-cnt 0
     :rssi {:avg 0.0 :q 0.0 :cnt 0 :std 0.0}
     :lsnr {:avg 0.0 :q 0.0 :cnt 0 :std 0.0}}))


(defn update-grid [grid msg]
  (let [level (:level grid)
        lat (:lat msg)
        lon (:lon msg)
        ch (keyword (cell-hash lat lon level))]
    (if (get-in grid [:cells ch])
      (update-in grid [:cells ch] update-cell msg)
      (assoc-in grid [:cells ch] (update-cell (make-cell lat lon level) msg)))))

(def GridCache (ref (cache/->LUCache {} {} (or (edn/read-string (env :grid-cache-size)) 2000))))
#_(cache/evict C :b)

(defn json->grid [json-grid]
  (json/read-str json-grid
    :key-fn keyword))

(defn make-grid [hash]
  {
    :level (level-from-hash hash)
    :hash hash
    :write-cnt 1
    :cells {}})

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
        (update grid :write-cnt incnil)) ; We loaded it because we're going to write it
     (catch AmazonS3Exception e
       (if (not= 404 (.getStatusCode e))
         (log/error e "Failed to get grid."))
       (make-grid hash)))))

(defn write-s3-as-json
  "Returns a channel that contains the result of the S3 put operation."
  [bucket-name key obj]
  (let [json (json/write-str obj)
        bytes (.getBytes json "UTF-8")
        input-stream (java.io.ByteArrayInputStream. bytes)]
    (go (s3/put-object
         :bucket-name bucket-name
         :key key
         :input-stream input-stream
         :metadata {
                    :content-type "application/json"
                    :content-length (count bytes)}))))

(defn write-grid-s3 [grid]
  (go
    (try
      (let [hash (:hash grid)
            response (<! (write-s3-as-json grid-bucket (s3-key hash) grid))]
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
          #_(log/debug "Waiting to update \"" _key "\"")
          (<! (timeout 30000))
          (if (= write (:write @dirty)) ; Same past write frame
            (let [dold @dirty
                  dnew (assoc dold :write (:count dold))
                  winner (compare-and-set! dirty dold dnew)
                  _ (if winner (log/debug "Writing grid to S3" _key))]
              (if winner
                (write-grid-s3 @grid-atom)))))))))


(defn make-grid-atom [g]
  (let [hash (:hash g)
        grid-atom (atom g)]
    (add-watch grid-atom hash (make-grid-watcher)) ; Do this work outside of sync
    (let [ga (dosync
              (if-let [cached-atom (cache/lookup @GridCache hash nil)]
                cached-atom ; Somebody beat us. Great!
                (do
                  (alter GridCache cache/miss hash grid-atom)
                  grid-atom)))]
      ; Postpone logging until after sync block
      (if (identical? ga grid-atom)
        (log/debug "GridCache miss:" hash)
        (log/debug "GridCache hit (late):" hash)) ;
      ga)))

(defn fetch-grid
  "Fetches a grid from cache or S3.
  Returns a channel that puts the grid and closes."
  [hash]
  (if-let [grid-atom (cache/lookup @GridCache hash nil)]
    (do ; Found in cache. Mark hit and push through channel.
      (if (not= hash (:hash @grid-atom)) (throw (Exception. (format "Hash key [%s] does not match hash of returned grid [%]." hash (:hash @grid-atom)))))
      (log/debug "GridCache hit:" hash)
      (dosync (alter GridCache cache/hit hash))
      (go grid-atom))
    (go
      (log/debug "Reading grid from S3" hash)
      (make-grid-atom (<! (fetch-grid-s3 hash))))))

(defn update-grids-with-msg
  "Returns a vector of channels"
  [msg]
  #_(log/debug "update-grids-with-msg")
  (let [lat (:lat msg)
        lon (:lon msg)]
    (vec (for [level (range 20)]
          (go
            (try
              #_(log/debug "Handle msg " lat " x " lon " at level " level)
              (let [hash (grid-hash lat lon level)
                    #_(log/debug "Hash: " hash)
                    grid-atom (<! (fetch-grid hash))
                    #_(log/debug "Fetched grid-atom" grid-atom)]
                (swap! grid-atom update-grid msg)
                (log/debug "Updated grid" hash)
                hash)
              (catch Exception e
                (log/error e)
                e)))))))

(defn generate-view-grids [lat lon x-count y-count level]
  (vec
    (let [start (grid-hash-raw lat lon level)]
      (for [x (range x-count)
            y (range y-count)]
        (let [geo (if (zero? x) start ((apply comp (repeat x #(.getEasternNeighbour %))) start))
              geo (if (zero? y) geo ((apply comp (repeat y #(.getSouthernNeighbour %))) geo))]
          (geohash-to-string geo))))))

(defn round-up-99 [x]
  (int (+ x 0.99)))

(defn view-grids
  "Return a set of hashes representing grids that would cover view"
  ([lat1 lon1 lat2 lon2]
   (view-grids lat1 lon1 lat2 lon2 6))

  ([lat1 lon1 lat2 lon2 max-grid-count]
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
             lat-span (- ulat llat (if (< lat1 lat2) -180 0))
             y-count (round-up-99 (/ lat-span box-height))

             llon (.getLongitude ul1)
             rlon (.getLongitude (.getLowerRight box2))
             lon-span (- rlon llon (if (> lon1 lon2) -360 0))
             box-width (Math/abs (- (.getLongitude lr1) llon))
             x-count (round-up-99 (/ lon-span box-width))]

         ;(log/debug "x-count" x-count ", y-count" y-count ", lat-span" lat-span ", lon-span" lon-span)
         (if (and (<= x-count max-grid-count) (<= y-count max-grid-count))
           (generate-view-grids lat1 lon1 x-count y-count level)
           (recur (dec level))))))))
