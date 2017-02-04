(ns mapthethings-server.data
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.data.codec.base64 :as b64]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [environ.core :refer [env]]))

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

(defn decode-lat-lon-payload [bytes]
  (if (not= 7 (alength bytes))
    {:error (str "Unable to parse lat/lon from" (alength bytes) "bytes")}
    (let [lat (extract-24bit bytes 1)
          lat (/ lat 93206.0)
          lon (extract-24bit bytes 4)
          lon (/ lon 46603.0)]
      {:lat lat :lon lon :mtt true})))

(defn extract-phone [bytes offset digit-count]
  (let [nibbles (reduce (fn [nibs b] (concat nibs [(bit-and 0x0f (bit-shift-right b 4)) (bit-and 0x0f b)])) [] (drop offset (vec bytes)))
        digits (map #(char (+ (int \0) %)) nibbles)]
    (apply str (take digit-count digits))))

(defn extract-message [bytes offset]
  (let [bytes (drop offset (vec bytes))]
    (apply str (map char bytes))))

(defn decode-sms-message [bytes]
  ;03 0A 16 46 55 55 55 50 M e s s a g e
  (let [phone-digit-count (aget bytes 1)
        phone (extract-phone bytes 2 phone-digit-count)
        phone-len (/ (inc phone-digit-count) 2)
        msg (extract-message bytes (+ 2 phone-len))]
    {:message msg
     :phone (str "+" phone)
     :sms true}))

(defn decode-byte-payload [bytes encoded]
  ; Parse bytes as packed lat/lon or JSON or other formats
  (let [len (alength bytes)]
    (if (= 0 len)
      {:error (str "Unable to parse lat/lon from no bytes")}
      (let [decoded (case (bit-and 0xFF (aget bytes 0))
                      0x01 (decode-lat-lon-payload bytes) ; 01 112233 112233 (little endian 24bit lat, lon)
                      0x02 (assoc (decode-lat-lon-payload bytes) :tracked true) ; 02 112233 112233 (little endian 24bit lat, lon)
                      0x03 (decode-sms-message bytes) ; 03 0A 16 46 55 55 55 50 M e s s a g e
                      0x81 (assoc (decode-lat-lon-payload bytes) :test-msg true) ; 81 112233 112233 (little endian 24bit lat, lon)
                      ; (decode-json-payload bytes)
                      {:error (str "Unable to parse packet: " bytes)})]
        decoded))))

(defn decode-payload [encoded]
  ; Parse bytes as packed lat/lon or JSON or other formats
  (decode-byte-payload (b64/decode (.getBytes encoded)) encoded))

; Format of V1(staging) message from TTN
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

; Format of V2 message from TTN
; {
;   "port": 1,
;   "counter": 0,
;   "payload_raw": "AQ==",
;   "payload_fields": {
;     "led": true
;   },
;   "metadata": {
;     "time": "2016-09-13T09:59:08.179119279Z",
;     "frequency": 868.3,
;     "modulation": "LORA",
;     "data_rate": "SF7BW125",
;     "coding_rate": "4/5",
;     "gateways": [{
;       "eui": "B827EBFFFE87BD22",
;       "timestamp": 1489443003,
;       "time": "2016-09-13T09:59:08.167028Z",
;       "channel": 1,
;       "rssi": -49,
;       "snr": 8,
;       "rf_chain": 1
;     }]
;   }
; }

(defn dev-eui-from-topic [topic]
  "Extract DevEUI from topic with format: AppEUI/devices/DevEUI/up"
  (get (string/split topic #"/") 2))

(defn parse-json-string [json-string]
  (json/read-str json-string :key-fn keyword))

(defn msg-from-ttn-v1
  "Takes a ttn v1 message and returns a simplified map containing just
  :type, :lat, :lon, :rssi, :lsnr, :test-msg, :dev_eui, and :error if there was a problem."
  [ttn mqtt_topic]
  (log/debug ttn)
  (-> (decode-payload (:payload ttn))
    (assoc :dev_eui (:dev_eui ttn))
    (assoc :type "ttn")
    (assoc :rssi (float (get-in ttn [:metadata 0 :rssi] 0)))
    (assoc :lsnr (float (get-in ttn [:metadata 0 :lsnr] 0)))))

(defn msg-from-ttn-v2
  "Takes a ttn v2 message and returns a simplified map containing just
  :type, :lat, :lon, :rssi, :lsnr, :test-msg, and :error if there was a problem."
  [ttn mqtt-topic]
  (log/debug ttn)
  (-> (decode-payload (:payload_raw ttn))
    (assoc :dev_eui (dev-eui-from-topic mqtt-topic))
    (assoc :type "ttn")
    (assoc :rssi (float (get-in ttn [:metadata :gateways 0 :rssi] 0)))
    (assoc :lsnr (float (get-in ttn [:metadata :gateways 0 :snr] 0)))))

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
