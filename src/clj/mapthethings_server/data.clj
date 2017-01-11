(ns mapthethings-server.data
  (:require [clojure.edn :as edn]
            [clojure.data.codec.base64 :as b64]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [environ.core :refer [env]]))

(defn ttn->msg
  "Takes a ttn message and returns a simplified map containing just
  :lat, :lon, :rssi, and :lsnr"
  [ttn]
  (log/debug ttn)
  (let [msg {
              :type "ttn"
              :lat (get-in ttn [:payload :lat])
              :lon (get-in ttn [:payload :lon])
              :rssi (float (get-in ttn [:metadata 0 :rssi] 0))
              :lsnr (float (get-in ttn [:metadata 0 :lsnr] 0))}]

    (if (get-in ttn [:payload :test-msg])
      (assoc msg :test-msg true)
      msg)))

(defn decode-json-payload [bytes]
  (let [json-string (String. bytes)
        lat-lon (json/read-str json-string :key-fn keyword)
        lat-lon (if (contains? lat-lon :longitude) (assoc lat-lon :lon (:longitude lat-lon)) lat-lon)
        lat-lon (if (contains? lat-lon :latitude) (assoc lat-lon :lat (:latitude lat-lon)) lat-lon)]
    lat-lon))

(defn extract-24bit
  ([bytes]
   (extract-24bit bytes 0))
  ([bytes offset]
   (let [low (aget bytes offset)
         mid (aget bytes (inc offset))
         high (aget bytes (+ offset 2))]
          ; Thanks, https://blog.quiptiq.com/2012/07/01/creating-numeric-types-from-byte-arrays-in-clojure/
     (bit-or
       (bit-shift-left (int high) 16) ; Sign extended, which is what we want
       (bit-shift-left (bit-and 0xff (int mid)) 8) ; and to chop sign extension
       (bit-and 0xff (int low))))))

(defn decode-48bit-payload [bytes]
  (let [lat (extract-24bit bytes 1)
        lat (/ lat 93206.0)
        lon (extract-24bit bytes 4)
        lon (/ lon 46603.0)]
    {:lat lat :lon lon}))

(defn decode-byte-payload [bytes encoded]
  ; Parse bytes as packed lat/lon or JSON or other formats
  (let [len (alength bytes)
        lat-lon (case (bit-and 0xFF (aget bytes 0))
                  0x01 (decode-48bit-payload bytes) ; 01 112233 112233 (little endian 24bit lat, lon)
                  0x02 (assoc (decode-48bit-payload bytes) :tracked true) ; 02 112233 112233 (little endian 24bit lat, lon)
                  0x81 (assoc (decode-48bit-payload bytes) :test-msg true) ; 81 112233 112233 (little endian 24bit lat, lon)
                  (decode-json-payload bytes))]
    (if (and (:lat lat-lon) (:lon lat-lon))
      lat-lon
      {:error (str "Unable to parse lat/lon from " bytes)})))

(defn decode-payload [encoded]
  ; Parse bytes as packed lat/lon or JSON or other formats
  (decode-byte-payload (b64/decode (.getBytes encoded)) encoded))

; Format of message from TTN
; {
;   "payload":"{ // b64 encoded bytes
;      We support
;        48 bit lat/lon pair with format tag (7 bytes): 0x01 (type) 0xlatitu 0xlongit (both little endian)
;        UTF-8 encoding of string {"lon":25.0,"lat":25.0}
;   }",
;   "port":1,
;   "counter":4,
;   "dev_eui":"00000000DEADBEEF",
;   "metadata":[
;     {
;       "frequency":865.4516,
;       "datarate":"SF9BW125",
;       "codingrate":"4/8",
;       "gateway_timestamp":1,
;       "gateway_time":"2016-05-22T06:05:38.645444008Z",
;       "channel":0,
;       "server_time":"2016-05-22T06:05:38.681605388Z",
;       "rssi":-5,
;       "lsnr":5.3,
;       "rfchain":0,
;       "crc":0,
;       "modulation":"LoRa",
;       "gateway_eui":"0102030405060708",
;       "altitude":0,
;       "longitude":0,
;       "latitude":0
;     },
;     Repeated metadata for each copy of message received by different gateways
;   ]
; }

(defn ttn-string->clj [json-string]
  (-> (json/read-str json-string :key-fn keyword)
    (update :payload decode-payload)))

(defn parse-lat [s]
  (cond
    (number? s) s
    :else
      (if-let [north (fnext (re-matches #"([\.\d]+)[Nn]" s))]
        (edn/read-string north)
        (if-let [south (fnext (re-matches #"([\.\d]+)[Ss]" s))]
          (- (edn/read-string south))
          (edn/read-string s)))))

(defn parse-lon [s]
  (cond
    (number? s) s
    :else
      (if-let [east (fnext (re-matches #"([\.\d]+)[Ee]" s))]
        (edn/read-string east)
        (if-let [west (fnext (re-matches #"([\.\d]+)[Ww]" s))]
          (- (edn/read-string west))
          (edn/read-string s)))))
