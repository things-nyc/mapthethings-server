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
  {
    :type "ttn"
    :lat (get-in ttn [:payload :latitude])
    :lon (get-in ttn [:payload :longitude])
    :rssi (float (get-in ttn [:metadata 0 :rssi] 0))
    :lsnr (float (get-in ttn [:metadata 0 :lsnr] 0))
  })

(defn decode-payload [encoded]
  ; TODO Parse bytes as packed lat/lon or JSON or other formats
  (let [bytes (b64/decode (.getBytes encoded))
        json-string (String. bytes)
        lat-lon (json/read-str json-string :key-fn keyword)]
    (if (and (:lat lat-lon) (:lon lat-lon))
      lat-lon
      {:error (str "Unable to parse JSON lat/lon from " json-string)})))

; Format of message from TTN
; {
;   "payload":"{ // b64 encoded bytes, which in our case are UTF-8 encoding of string
;     "longitude":25.0,
;     "latitude":25.0
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
