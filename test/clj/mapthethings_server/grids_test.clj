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
      (is (= grid {:level 2, :hash "EF", :cells {}, :write-cnt 1})))))

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
                :cells {}}

          msg {:type "ttn" :lat lat :lon lon :rssi 2.2 :lsnr 1.3}
          updated (update-grid grid msg)
          updated-again (update-grid updated msg)
          msg {:type "import" :lat lat :lon lon :rssi 2.2 :lsnr 1.3}
          updated-import (update-grid updated-again msg)
          msg {:type "attempt" :lat lat :lon lon}
          updated-attempt (update-grid updated-import msg)]

      (is (some? updated))
      (is (= (get-in updated [:cells chash :count]) 1))
      (is (= (get-in updated [:cells chash :ttn-cnt]) 1))
      (is (= (get-in updated [:cells chash :api-cnt]) 0))
      (is (= (get-in updated [:cells chash :import-cnt]) 0))
      (is (= (get-in updated [:cells chash :attempt-cnt]) 0))
      (is (= (get-in updated [:cells chash :success-cnt]) 0))
      (is (= (get-in updated [:cells chash :rssi :cnt]) 1))
      (is (= (get-in updated [:cells chash :rssi :avg]) 2.2))
      (is (= (get-in updated [:cells chash :lsnr :avg]) 1.3))
      (is (some? updated-again))
      (is (= (get-in updated-again [:cells chash :count]) 2))
      (is (= (get-in updated-again [:cells chash :ttn-cnt]) 2))
      (is (= (get-in updated-again [:cells chash :api-cnt]) 0))
      (is (= (get-in updated-again [:cells chash :import-cnt]) 0))
      (is (= (get-in updated-again [:cells chash :attempt-cnt]) 0))
      (is (= (get-in updated-again [:cells chash :success-cnt]) 0))
      (is (= (get-in updated-again [:cells chash :rssi :cnt]) 2))
      (is (= (get-in updated-again [:cells chash :rssi :avg]) 2.2))
      (is (= (get-in updated-again [:cells chash :lsnr :avg]) 1.3))
      (is (some? updated-import))
      (is (= (get-in updated-import [:cells chash :count]) 3))
      (is (= (get-in updated-import [:cells chash :ttn-cnt]) 2))
      (is (= (get-in updated-import [:cells chash :api-cnt]) 0))
      (is (= (get-in updated-import [:cells chash :import-cnt]) 1))
      (is (= (get-in updated-import [:cells chash :attempt-cnt]) 0))
      (is (= (get-in updated-import [:cells chash :success-cnt]) 0))
      (is (= (get-in updated-import [:cells chash :rssi :cnt]) 3))
      (is (= (get-in updated-import [:cells chash :rssi :avg]) 2.2))
      (is (= (get-in updated-import [:cells chash :lsnr :avg]) 1.3))
      (is (some? updated-attempt))
      (is (= (get-in updated-attempt [:cells chash :count]) 3))
      (is (= (get-in updated-attempt [:cells chash :ttn-cnt]) 2))
      (is (= (get-in updated-attempt [:cells chash :api-cnt]) 0))
      (is (= (get-in updated-attempt [:cells chash :import-cnt]) 1))
      (is (= (get-in updated-attempt [:cells chash :attempt-cnt]) 1))
      (is (= (get-in updated-attempt [:cells chash :success-cnt]) 0))
      (is (= (get-in updated-attempt [:cells chash :rssi :cnt]) 3))
      (is (= (get-in updated-attempt [:cells chash :rssi :avg]) 2.2))
      (is (= (get-in updated-attempt [:cells chash :lsnr :avg]) 1.3)))))


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
               :lsnr 10.0}

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
          grid-atom (make-grid-atom {:level level :hash hash :cells {}})]
          ;_ (log/debug "GridCache post change" @GridCache)

      (is (some? (cache/lookup @GridCache hash))))))

(deftest view-grids-test
  (testing "Return a set of hashes representing grids that would cover simple rectangular view"
    (let [lat1 10.0
          lon1 -10.0
          lat2 -10.0
          lon2 10.0
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["K1AB" "K1AA" "KFF" "KFE" "K301" "K300" "K255" "K254"]))))
  (testing "Return a set of hashes representing grids that would cover view wrapping poles"
    (let [lat1 -10.0
          lon1 -10.0
          lat2 10.0
          lon2 10.0
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["E3" "E2" "E7" "E6" "E9" "E8" "ED" "EC"]))))
  (testing "Return a set of hashes representing grids that would cover view wrapping date line"
    (let [lat1 10.0
          lon1 10.0
          lat2 -10.0
          lon2 -10.0
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["EC" "E9" "EE" "EB" "E4" "E1" "E6" "E3"]))))
  (testing "Return a set of hashes representing grids that would cover view wrapping poles and date line"
    (let [lat1 -10.0
          lon1 10.0
          lat2 10.0
          lon2 -10.0
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["E9" "E8" "ED" "EC" "EB" "EA" "EF" "EE" "E1" "E0" "E5" "E4" "E3" "E2" "E7" "E6"]))))
  (testing "Return a set of hashes representing grids that would cover view"
    (let [lat1 40.847600
          lon1 -73.863100
          lat2 40.497600
          lon2 -73.054300
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["W19738A" "W1972DF" "W1972DE" "W1972DB" "W1972DA" "W1973A0" "W1972F5" "W1972F4" "W1972F1" "W1972F0" "W1973A2" "W1972F7" "W1972F6" "W1972F3" "W1972F2" "W1973A8" "W1972FD" "W1972FC" "W1972F9" "W1972F8" "W1973AA" "W1972FF" "W1972FE" "W1972FB" "W1972FA" "W197900" "W197855" "W197854" "W197851" "W197850"]))))
  (testing "Return a set of hashes representing grids that would cover view (wrapping date line)"
    (let [;lat1 40.910027
          ;lon1 -73.859638
          lat1 40.847600
          lon1 -73.054300
          lat2 40.608053
          lon2 -74.109638
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["E6" "EC" "EE" "E4" "E6"]))))
  (testing "Return a set of hashes representing grids that would cover view (wrapping date line)"
    (let [lat1 40.910027
          lon1 -73.859638
          lat2 40.608053
          lon2 -74.109638
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["E6" "EC" "EE" "E4" "E6"])))))

(deftest geohash-rollover-test
  (testing "Getting southern neighbor from south latitude wraps to north latitude"
    (let [lat1 -89.0
          lon1 -1.0
          lat2 89.0
          lon2 1.0
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["SAAAC" "SAAA9" "SAAA8" "S1FFFD" "S1FFFC" "S1FFF9" "SAAAE" "SAAAB" "SAAAA" "S1FFFF" "S1FFFE" "S1FFFB" "S20004" "S20001" "S20000" "S35555" "S35554" "S35551" "S20006" "S20003" "S20002" "S35557" "S35556" "S35553"]))))
  (testing "Getting eastern neighbor from eastern longitude wraps to western longitude"
    (let [lat1 1.0
          lon1 179.0
          lat2 -1.0
          lon2 -179.0
          hashes (view-grids lat1 lon1 lat2 lon2)]
      (is (some? hashes))
      (is (= hashes ["S3AAAC" "S3AAA9" "S3AAA8" "S2FFFD" "S2FFFC" "S2FFF9" "S3AAAE" "S3AAAB" "S3AAAA" "S2FFFF" "S2FFFE" "S2FFFB" "S10004" "S10001" "S10000" "S5555" "S5554" "S5551" "S10006" "S10003" "S10002" "S5557" "S5556" "S5553"])))))
