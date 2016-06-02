(ns mapthethings-server.grids-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.cache :as cache]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close! merge timeout]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [mapthethings-server.grids :refer :all]))

(deftest grid-stack-hashes-test
  (testing "generating bounding hashes"
    (let [hashes (grid-stack-hashes 27.0 45.0)]
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
      (is (= grid {:level 2, :hash "EF", :cells {}, :write-cnt 0})))))

(deftest write-grid-s3-test
  (testing "write grid"
    (let [lat -89.0
          lon -179.0
          level 4
          hash (grid-hash lat lon level)
          grid (make-grid hash)
          rchan (write-grid-s3 grid)
          result (<!! rchan)]
      (is (nil? (:error result))))))

(deftest fetch-grid-test
  (testing "fetch grid"
    (let [lat -89.0
          lon -179.0
          level 4
          hash (grid-hash lat lon level)
          rchan (fetch-grid hash)
          grid-atom (<!! rchan)]
      (is (= @grid-atom {:level 4, :hash "I0", :cells {}})))))

(deftest grid-update-test
  (testing "grid updating"
    (let [lat 25.5
          lon 34.2
          level 2
          hash (grid-hash lat lon level)
          chash (keyword (cell-hash lat lon level))
          grid {
            :level level
            :hash hash
            :cells {}
          }
          msg {:type "ttn" :lat lat :lon lon :rssi 2.2 :lsnr 1.3}
          updated (update-grid grid msg)
          updated-again (update-grid updated msg)]
      (is (some? updated))
      (is (= (get-in updated [:cells chash :count]) 1))
      (is (= (get-in updated [:cells chash :ttn-cnt]) 1))
      (is (= (get-in updated [:cells chash :ping-cnt]) 0))
      (is (= (get-in updated [:cells chash :rssi :cnt]) 1))
      (is (= (get-in updated [:cells chash :rssi :avg]) 2.2))
      (is (= (get-in updated [:cells chash :lsnr :avg]) 1.3))
      (is (some? updated-again))
      (is (= (get-in updated-again [:cells chash :count]) 2))
      (is (= (get-in updated-again [:cells chash :ttn-cnt]) 2))
      (is (= (get-in updated-again [:cells chash :ping-cnt]) 0))
      (is (= (get-in updated-again [:cells chash :rssi :cnt]) 2))
      (is (= (get-in updated-again [:cells chash :rssi :avg]) 2.2))
      (is (= (get-in updated-again [:cells chash :lsnr :avg]) 1.3))
      )))

(deftest sample-update-test
  (testing "sample updating"
    (let [hashes (grid-stack-hashes 27.0 45.0)]
      (is (= 20 (count hashes))))))

(deftest update-grids-with-msg-test
  (testing "Processing in response to a ttn message"
    (let [msg {
      :type "ttn"
      :lat 35.0
      :lon 35.0
      :rssi -15.0
      :lsnr 10.0
      }
      results (map #(<!! %) (update-grids-with-msg msg))]
      (is (= 20 (count results))
      (<!! (go (<! (timeout 45000))))))))

(deftest make-grid-atom-test
  (testing "Make a grid atom and it should go in the cache"
    (let [lat -89.0
          lon -179.0
          level 4
          hash (grid-hash lat lon level)
          ;_ (log/debug "Hash" hash "GridCache" @GridCache)
          grid-atom (make-grid-atom {:level level :hash hash :cells {}})
          ;_ (log/debug "GridCache post change" @GridCache)
          ]
      (is (some? (cache/lookup @GridCache hash))))))

(deftest view-grids-test
  (testing "Return a set of hashes representing grids that would cover view"
    (let [lat1 -10.0
          lon1 -10.0
          lat2 10.0
          lon2 10.0
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["KFE" "KFB" "KFA" "K254" "K251" "K250" "K256" "K253" "K252"])))))
