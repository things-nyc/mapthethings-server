(ns mapthethings-server.import-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [mapthethings-server.import :refer :all]
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
  }")


(deftest import-1-parse-test
  (testing "parse import JSON"
    (let [[msg error] (parse-sample (json/read-str test-import-1 :key-fn keyword))]
      (is (approx= (:lat msg) 40.756697 1e-5))
      (is (approx= (:lon msg) -74.036350 1e-5))
      (is (approx= (:rssi msg) -18 1e-5))
      (is (approx= (:lsnr msg) 10.2 1e-5)))))


(def test-import-2
  "{
    \"lat\": \"40.756697S\",
    \"lng\": \"74.036350E\",
    \"rssi\": -18.00,
    \"snr\": 10.20,
    \"time\": \"2016-05-23T14:26:09.067686292Z\"
  }")


(deftest import-2-parse-test
  (testing "parse import JSON"
    (let [[msg error] (parse-sample (json/read-str test-import-2 :key-fn keyword))]
      (is (approx= (:lat msg) -40.756697 1e-5))
      (is (approx= (:lon msg) 74.036350 1e-5))
      (is (approx= (:rssi msg) -18 1e-5))
      (is (approx= (:lsnr msg) 10.2 1e-5)))))


(def test-import-3
  "{
    \"gps\":[52.0399832725525,5.57624816894531],
    \"time\": \"2016-04-17 15:24:28\",
    \"rssi\": -117,
    \"snr\": -18,
    \"device\": \"LoRaMote Thomas\",
    \"gateway\": \"Lorank, De Bilt\"
  }")


(deftest import-3-parse-test
  (testing "parse import JSON"
    (let [[msg error] (parse-sample (json/read-str test-import-3 :key-fn keyword))]
      (is (approx= (:lat msg) 52.0399832725525 1e-15))
      (is (approx= (:lon msg) 5.57624816894531 1e-15))
      (is (approx= (:rssi msg) -117 1e-5))
      (is (approx= (:lsnr msg) -18 1e-5)))))
