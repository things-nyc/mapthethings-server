(ns thingsburg-server.import-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [thingsburg-server.import :refer :all]
            [clojure.algo.generic.math-functions :refer [approx=]]))

(def test-import-1
  "{
      \"data\": \"AAEAAA==\",
      \"dataRate\": \"SF7BW125\",
      \"frequency\": 912.5,
      \"gatewayEui\": \"008000000000ABFF\",
      \"gps\": \"40.756697N,74.036350W\",
      \"nodeEui\": \"DEAD00A1\",
      \"payload_dr\": 0,
      \"payload_seq\": 1,
      \"rawData\": \"QKEArd4AAQABCNUk/9W9UEU=\",
      \"rssi\": -18,
      \"server_time\": 1464013569,
      \"snr\": 10.2,
      \"time\": \"2016-05-23T14:26:09.067686292Z\"
  }"
)

(deftest import-1-parse-test
  (testing "parse import JSON"
    (let [[msg error] (parse-sample (json/read-str test-import-1 :key-fn keyword))]
      (is (approx= (:lat msg) 40.756697 1e-5))
      (is (approx= (:lon msg) -74.036350 1e-5))
      (is (approx= (:rssi msg) -18 1e-5))
      (is (approx= (:lsnr msg) 10.2 1e-5))
      )))

(def test-import-2
  "{
    \"lat\": \"40.756697S\",
    \"lng\": \"74.036350E\",
    \"rssi\": -18.00,
    \"snr\": 10.20,
    \"time\": \"2016-05-23T14:26:09.067686292Z\"
  }"
)

(deftest import-2-parse-test
  (testing "parse import JSON"
    (let [[msg error] (parse-sample (json/read-str test-import-2 :key-fn keyword))]
      (is (approx= (:lat msg) -40.756697 1e-5))
      (is (approx= (:lon msg) 74.036350 1e-5))
      (is (approx= (:rssi msg) -18 1e-5))
      (is (approx= (:lsnr msg) 10.2 1e-5))
      )))
