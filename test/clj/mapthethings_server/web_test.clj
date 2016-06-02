(ns mapthethings-server.web-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close!]]
            [clojure.string :refer [blank? join trim]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [mapthethings-server.grids :as grids]
            [mapthethings-server.web :refer :all]))

(deftest main-page-test
  (testing "main page"
    (let [app (make-app)
          response (app (request :get "/"))]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "var mymap = L.map('mapid')")))))

(deftest view-grids-test
  (testing "view grids endpoint"
    (let [app (make-app)
          response (app (request :get "/api/v0/grids/10.0/10.0/8.0/12.0"))
          grid-ids ["E70CQ-v0","B70CQ-v0","A70CQ-v0","F60CQ-v0","E60CQ-v0","4D0CQ-v0","1D0CQ-v0","0D0CQ-v0","5C0CQ-v0","4C0CQ-v0","6D0CQ-v0","3D0CQ-v0","2D0CQ-v0","7C0CQ-v0","6C0CQ-v0"]
          expectation (format "[%s]" (join "," (map (partial format "\"https://s3.amazonaws.com/%s/%s\"" grids/grid-bucket) grid-ids)))]
      (is (= 200 (:status response)))
      (is (= expectation (:body response))))))

(deftest ping-test
  (testing "ping endpoint"
    (let [app (make-app)
          response (app (request :post "/api/v0/pings" {"lat" 40.0 "lon" -74.0 "timestamp" "2016-05-25T15:30:26.713Z"}))]
      (is (= 201 (:status response)))
      (is (= "{\"type\":\"attempt\",\"lat\":40.0,\"lon\":-74.0,\"timestamp\":\"2016-05-25T15:30:26.713Z\",\"msgid\":null,\"appkey\":null,\"client-ip\":\"localhost\"}" (:body response))))))
