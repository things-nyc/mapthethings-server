(ns thingsburg-server.data-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close!]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [thingsburg-server.data :refer :all]))

(def test-ttn-string
  ; Payload: {"msgid": "[UNIQUE_MSG_ID]", "appkey": "[THINGSBURG_APP_KEY]", "longitude":25.0, "latitude":25.0}
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
      (println msg)
      (is (some? (:lat msg)))
      (is (some? (:lon msg)))
      (is (some? (:rssi msg)))
      (is (some? (:lsnr msg)))
      )))

(deftest ttn-parse-test
  (testing "parse ttn string as a clj map with keywords"
    (let [ttn (ttn-string->clj test-ttn-string)
          msg (ttn->msg ttn)]
      (println msg)
      (is (some? (:lat msg)))
      (is (some? (:lon msg)))
      (is (= (:rssi msg) (float -5)))
      (is (= (:lsnr msg) (float 5.3)))
      )))
