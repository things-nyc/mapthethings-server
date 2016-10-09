(ns mapthethings-server.stats-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close!]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [mapthethings-server.stats :refer :all]))

(deftest initializing-stats-test
  (testing "Sending nil stat returns appropriate initial value"
    (let [stat (cummulative-stat nil 10.0)
          stat2 (cummulative-stat {:cnt 0 :avg 0 :q 0 :std 0} 10.0)]
      (is (= stat stat2)))))

(deftest correct-stats-test
  (testing "Check that the stat is updated correctly"
    (let [stat1 (cummulative-stat nil 10.0)
          stat2 (cummulative-stat stat1 20.0)
          stat3 (cummulative-stat stat2 30.0)]
      (is (= 2 (:cnt stat2)))
      (is (= 15.0 (:avg stat2)))
      (is (= 50.0 (:q stat2)))
      (is (= 5.0 (:std stat2)))
      (is (= 3 (:cnt stat3)))
      (is (= 20.0 (:avg stat3)))
      (is (= 200.0 (:q stat3)))
      (is (= 8.16496580927726 (:std stat3))))))
