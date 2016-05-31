(ns thingsburg-server.data
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
  (let [bytes (b64/decode (.getBytes encoded))
        json-string (String. bytes)]
    (json/read-str json-string :key-fn keyword)))

(defn ttn-string->clj [json-string]
  (-> (json/read-str json-string :key-fn keyword)
    (update :payload decode-payload)))

(defn parse-lat [s]
  (if-let [north (fnext (re-matches #"([\.\d]+)[Nn]" s))]
    (edn/read-string north)
    (if-let [south (fnext (re-matches #"([\.\d]+)[Ss]" s))]
      (- (edn/read-string south))
      (edn/read-string s))))

(defn parse-lon [s]
  (if-let [east (fnext (re-matches #"([\.\d]+)[Ee]" s))]
    (edn/read-string east)
    (if-let [west (fnext (re-matches #"([\.\d]+)[Ww]" s))]
      (- (edn/read-string west))
      (edn/read-string s))))
