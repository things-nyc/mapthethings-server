(ns thingsburg-server.grids-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close!]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [thingsburg-server.grids :refer :all]))

(deftest bounding-hashes-test
  (testing "generating bounding hashes"
    (let [hashes (bounding-hashes 27.0 45.0)]
      (is (= 20 (count hashes))))))

(deftest s3-key-test
  (testing "generate s3 key from hash"
    (let [key (s3-key "HASH")]
      (is (= key "HSAH-v0")))))

(deftest fetch-grid-s3-test
  (testing "fetch missing grid"
    (let [lat 89.0
          lon 179.0
          level 2
          hash (grid-hash lat lon level)
          rchan (fetch-grid-s3 hash)
          grid (<!! rchan)]
      (is (= grid {:level 2, :hash "EF", :cells {}})))))

(deftest write-grid-s3-test
  (testing "write grid"
    (let [lat -89.0
          lon -179.0
          level 2
          hash (grid-hash lat lon level)
          grid (make-grid hash)
          rchan (write-grid-s3 grid)
          result (<!! rchan)]
      (is (nil? (:error result))))))

(deftest grid-update-test
  (testing "grid updating"
    (let [lat 25.5
          lon 34.2
          level 2
          hash (grid-hash lat lon level)
          chash (cell-hash lat lon level)
          grid {
            :level level
            :hash hash
            :cells {}
          }
          msg {:lat lat :lon lon :rssi 2.2 :lsnr 1.3}
          updated (update-grid grid msg)]
      (println updated)
      (is (some? updated))
      (is (= (get-in updated [:cells chash :ok]) 1))
      (is (= (get-in updated [:cells chash :pings]) 1)))))

(deftest sample-update-test
  (testing "sample updating"
    (let [hashes (bounding-hashes 27.0 45.0)]
      (is (= 20 (count hashes))))))

#_(deftest inbound-email
  (testing "inbound email endpoint"
    (let [email {
            "sender" "initiator@email.com"
            "Return-Path" "<initiator@email.com>"
            "recipient" "r@req.futurose.com"
            "Subject" "Request for Information"
            "body-plain" "Text plain"
            "body-html" "<html><body><p>This is HTML paragraph.</p></body></html>"
          }
          ic (chan 1)
          app (make-app {:inbound-chan ic})
          req (request :post "/inbound-email" email)
          response (app req)]
      (is (= 200 (:status response)))
      (is (= "" (:body response)))
      (let [inmail (<!! ic)]
        (is (= "initiator@email.com" (:sender inmail))))
      (close! ic))))
