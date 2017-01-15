(ns mapthethings-server.data-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [mapthethings-server.data :refer :all]))

(def test-ttn-string-v1
  ; Code: (clojure.string/upper-case (apply str (map #(format "%02x" (int %)) (.getBytes "{\"msgid\": \"[UNIQUE_MSG_ID]\", \"appkey\": \"[APP_KEY]\", \"longitude\":-74.0059, \"latitude\":40.7128}"))))

  ; Payload: {"msgid": "[UNIQUE_MSG_ID]", "appkey": "[APP_KEY]", "longitude":25.0, "latitude":25.0}
  ; Hex: 7B226D73676964223A20225B554E495155455F4D53475F49445D222C20226170706B6579223A20225B5448494E4753425552475F4150505F4B45595D222C20226C6F6E676974756465223A32352E302C20226C61746974756465223A32352E307D
  ;      7B226D73676964223A20225B554E495155455F4D53475F49445D222C20226170706B6579223A20225B5448494E4753425552475F4150505F4B45595D222C20226C6F6E676974756465223A32302E302C20226C61746974756465223A32302E307D
  ; Payload: {"msgid": "[UNIQUE_MSG_ID]", "appkey": "[APP_KEY]", "longitude":-74.0059, "latitude":-40.7128}
  ; Hex: 7B226D73676964223A20225B554E495155455F4D53475F49445D222C20226170706B6579223A20225B5448494E4753425552475F4150505F4B45595D222C20226C6F6E676974756465223A2D37342E303035392C20226C61746974756465223A34302E373132387D

  "{
    \"payload\":\"AfHXOVBjyw==\",
    \"port\":1,
    \"counter\":4,
    \"dev_eui\":\"00000000DEADBEEF\",
    \"metadata\":[
      {
        \"frequency\":865.4516,
        \"datarate\":\"SF9BW125\",
        \"codingrate\":\"4/8\",
        \"gateway_timestamp\":1,
        \"gateway_time\":\"2016-05-22T06:05:38.645444008Z\",
        \"channel\":0,
        \"server_time\":\"2016-05-22T06:05:38.681605388Z\",
        \"rssi\":-5,
        \"lsnr\":5.3,
        \"rfchain\":0,
        \"crc\":0,
        \"modulation\":\"LoRa\",
        \"gateway_eui\":\"0102030405060708\",
        \"altitude\":0,
        \"longitude\":0,
        \"latitude\":0
      }
    ]
  }
")

(def test-ttn-string-v2
  "{
    \"port\": 1,
    \"counter\": 0,
    \"payload_raw\": \"AfHXOVBjyw==\",
    \"payload_fields\": {
      \"led\": true
    },
    \"metadata\": {
      \"time\": \"2016-09-13T09:59:08.179119279Z\",
      \"frequency\": 868.3,
      \"modulation\": \"LORA\",
      \"data_rate\": \"SF7BW125\",
      \"coding_rate\": \"4/5\",
      \"gateways\":
      [{
        \"eui\": \"B827EBFFFE87BD22\",
        \"timestamp\": 1489443003,
        \"time\": \"2016-09-13T09:59:08.167028Z\",
        \"channel\": 1,
        \"rssi\": -49,
        \"snr\": 8,
        \"rf_chain\": 1
      }]
    }
  }")


(deftest ttn-parse-test
  (testing "parse v1/staging ttn string as a clj map with keywords"
    (let [msg (parse-json-string test-ttn-string-v1)]
      (is (some? (:payload msg)))
      (is (some? (:port msg)))
      (is (some? (get-in msg [:metadata 0 :rssi])))
      (is (some? (get-in msg [:metadata 0 :lsnr])))))
  (testing "parse v2 ttn string as a clj map with keywords"
    (let [msg (parse-json-string test-ttn-string-v2)]
      (is (some? (:payload_raw msg)))
      (is (some? (:payload_fields msg)))
      (is (some? (:port msg)))
      (is (some? (get-in msg [:metadata :gateways 0 :rssi])))
      (is (some? (get-in msg [:metadata :gateways 0 :snr]))))))

(deftest ttn-msg-parse-test
  (testing "parse staging ttn map as a msg map"
    (let [ttn (parse-json-string test-ttn-string-v1)
          msg (msg-from-ttn-v1 ttn)]
      (is (not (:payload msg)))
      (is (some? (:lat msg)))
      (is (some? (:lon msg)))
      (is (= (:rssi msg) (float -5)))
      (is (= (:lsnr msg) (float 5.3)))))
  (testing "parse v2 ttn map as a msg map"
    (let [ttn (parse-json-string test-ttn-string-v2)
          msg (msg-from-ttn-v2 ttn)]
      (is (not (:payload_raw msg)))
      (is (some? (:lat msg)))
      (is (some? (:lon msg)))
      (is (= (:rssi msg) (float -49)))
      (is (= (:lsnr msg) (float 8))))))

(deftest extract-24bit-test
  (testing "extract 24 bit value"
    (let [payload (byte-array [0x02 0x04 0x01])
          value (extract-24bit payload)]
      (is (= value 66562)))
    (let [payload (byte-array [0xE9 0xD7 0x39])
          value (extract-24bit payload)]
      (is (= value 3790825)))
    (let [payload (byte-array [0x4F 0x63 0xCB])
          value (extract-24bit payload)]
      (is (= value -3447985)))))


(defn- float= [x y]
  ; http://gettingclojure.wikidot.com/cookbook:numbers
  (let [epsilon 0.00001
        scale (if (or (zero? x) (zero? y)) 1 (Math/abs x))]
    (<= (Math/abs (- x y)) (* scale epsilon))))

(deftest payload-lat-lon-test
  (testing "parse 48 bit lat/lon payload"
    (let [payload (byte-array [0x01 0xE9 0xD7 0x39 0x4F 0x63 0xCB])
          msg (decode-lat-lon-payload payload)]
      (is (float= 40.6714 (:lat msg)))
      (is (float= -73.9863 (:lon msg))))))

(deftest parse-byte-packet-test
  (testing "parse MTT packet (format + 48 bit lat/lon payload)"
    (let [payload (byte-array [0x01 0xE9 0xD7 0x39 0x4F 0x63 0xCB])
          msg (decode-byte-payload payload "fake-encoded")]
      (is (not (:test-msg msg)))
      (is (not (:tracked msg)))
      (is (float= 40.6714 (:lat msg)))
      (is (float= -73.9863 (:lon msg))))
    (let [payload (byte-array [0x02 0xE9 0xD7 0x39 0x4F 0x63 0xCB])
          msg (decode-byte-payload payload "fake-encoded")]
      (is (not (:test-msg msg)))
      (is (:tracked msg))
      (is (float= 40.6714 (:lat msg)))
      (is (float= -73.9863 (:lon msg))))
    (let [payload (byte-array [0x81 0xE9 0xD7 0x39 0x4F 0x63 0xCB])
          msg (decode-byte-payload payload "fake-encoded")]
      (is (not (:tracked msg)))
      (is (:test-msg msg))
      (is (float= 40.6714 (:lat msg)))
      (is (float= -73.9863 (:lon msg))))))

(deftest parse-sms-packet
  (testing "parse sms packet (format phonelen message)")
  (let [payload (byte-array (vec (concat [0x03 0x0B 0x16 0x46 0x18 0x84 0x56 0x70] (.getBytes "Message" "UTF-8"))))
        msg (decode-byte-payload payload "fake-encoded")]
    (is (= "+16461884567" (:phone msg)))
    (is (= "Message" (:message msg)))))
