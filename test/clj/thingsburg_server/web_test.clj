(ns thingsburg-server.web-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close!]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [thingsburg-server.web :refer :all]))

(deftest main-page-test
  (testing "main page"
    (let [app (make-app)
          response (app (request :get "/"))]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "var mymap = L.map('mapid')")))))

(deftest view-grids-test
  (testing "view grids endpoint"
    (let [app (make-app)
          response (app (request :get "/api/v0/grids/10.0/10.0/8.0/12.0"))]
      (is (= 200 (:status response)))
      (is (= "[\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/E70CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/B70CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/A70CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/F60CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/E60CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/4D0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/1D0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/0D0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/5C0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/4C0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/6D0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/3D0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/2D0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/7C0CQ-v0\",\"https://s3.amazonaws.com/com.futurose.thingsburg.grids/6C0CQ-v0\"]" (:body response))))))

(deftest ping-test
  (testing "ping endpoint"
    (let [app (make-app)
          response (app (request :post "/api/v0/pings" {"lat" 40.0 "lon" -74.0 "timestamp" "2016-05-25T15:30:26.713Z"}))]
      (is (= 201 (:status response)))
      (is (= "{\"type\":\"ping\",\"lat\":40.0,\"lon\":-74.0,\"timestamp\":\"2016-05-25T15:30:26.713Z\",\"msgid\":null,\"appkey\":null,\"client-ip\":\"localhost\"}" (:body response))))))
