(ns mapthethings-server.data-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [mapthethings-server.data :refer :all]))

(def test-ttn-string
  ; Code: (clojure.string/upper-case (apply str (map #(format "%02x" (int %)) (.getBytes "{\"msgid\": \"[UNIQUE_MSG_ID]\", \"appkey\": \"[APP_KEY]\", \"longitude\":-74.0059, \"latitude\":40.7128}"))))

  ; Payload: {"msgid": "[UNIQUE_MSG_ID]", "appkey": "[APP_KEY]", "longitude":25.0, "latitude":25.0}
  ; Hex: 7B226D73676964223A20225B554E495155455F4D53475F49445D222C20226170706B6579223A20225B5448494E4753425552475F4150505F4B45595D222C20226C6F6E676974756465223A32352E302C20226C61746974756465223A32352E307D
  ;      7B226D73676964223A20225B554E495155455F4D53475F49445D222C20226170706B6579223A20225B5448494E4753425552475F4150505F4B45595D222C20226C6F6E676974756465223A32302E302C20226C61746974756465223A32302E307D
  ; Payload: {"msgid": "[UNIQUE_MSG_ID]", "appkey": "[APP_KEY]", "longitude":-74.0059, "latitude":-40.7128}
  ; Hex: 7B226D73676964223A20225B554E495155455F4D53475F49445D222C20226170706B6579223A20225B5448494E4753425552475F4150505F4B45595D222C20226C6F6E676974756465223A2D37342E303035392C20226C61746974756465223A34302E373132387D

  "{
    \"payload\":\"eyJtc2dpZCI6ICJbVU5JUVVFX01TR19JRF0iLCAiYXBwa2V5IjogIltUSElOR1NCVVJHX0FQUF9LRVldIiwgImxvbmdpdHVkZSI6MjUuMCwgImxhdGl0dWRlIjoyNS4wfQ==\",
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

(deftest ttn-parse-test
  (testing "parse ttn string as a clj map with keywords"
    (let [msg (ttn-string->clj test-ttn-string)]
      (is (some? (:lat msg)))
      (is (some? (:lon msg)))
      (is (some? (:rssi msg)))
      (is (some? (:lsnr msg)))
      )))

(deftest ttn-parse-test
  (testing "parse ttn string as a clj map with keywords"
    (let [ttn (ttn-string->clj test-ttn-string)
          msg (ttn->msg ttn)]
      (is (some? (:lat msg)))
      (is (some? (:lon msg)))
      (is (= (:rssi msg) (float -5)))
      (is (= (:lsnr msg) (float 5.3)))
      )))

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
      (is (= value -3447985)))
    ))

(defn- float= [x y]
  ; http://gettingclojure.wikidot.com/cookbook:numbers
  (let [epsilon 0.00001
        scale (if (or (zero? x) (zero? y)) 1 (Math/abs x))]
    (<= (Math/abs (- x y)) (* scale epsilon))))

(deftest payload-48bit-test
  (testing "parse 48 bit lat/lon payload"
    (let [payload (byte-array [0x20 0xE9 0xD7 0x39 0x4F 0x63 0xCB])
          msg (decode-48bit-payload payload)]
      (is (float= 40.6714 (:lat msg)))
      (is (float= -73.9863 (:lon msg)))
      )))
