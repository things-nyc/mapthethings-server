(ns mapthethings-server.stats
  (:require [clojure.edn :as edn]
            [clojure.data.codec.base64 :as b64]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [environ.core :refer [env]]))

(def incnil (fnil inc 0))

(defn- cummulative-avg [a x k]
  (+ a (/ (- x a) (incnil k)))) ; A + (x - A) / n

(defn- cummulative-qf [q x alast anew]
  (+ q (* (- x alast) (- x anew)))) ; Q + (x - Alast) * (x - Anew)

(defn cummulative-stat
  "Calculate cummulative avg and std dev per https://en.wikipedia.org/wiki/Standard_deviation#Rapid_calculation_methods"
  ([] {:avg 0.0 :q 0.0 :cnt 0 :std 0.0})
  ([stat value]
   (if (nil? stat)
    {:cnt 1 :avg value :std 0.0 :q 0.0}
    (let [avg-old (:avg stat)
          avg-new (cummulative-avg avg-old value (:cnt stat))]
      (-> stat
        (update :cnt incnil)
        (assoc :avg avg-new)
        (update :q cummulative-qf value avg-old avg-new)
        (#(assoc % :std (Math/sqrt (/ (:q %) (:cnt %))))))))))

(defn sum-stat
  "Calculate sum of two cummulative stats avg and std dev per https://en.wikipedia.org/wiki/Algorithms_for_calculating_variance#Parallel_algorithm"
  ([a b]
   (let [na (:cnt a)
         nb (:cnt b)
         cnt (+ na nb)

         d (- (:avg b) (:avg a))
         avg (+ (:avg a) (* d (/ nb cnt)))
         q (+ (:q a) (:q b) (/ (* d d na nb) cnt))]
      (-> {:avg avg :q q :cnt cnt}
        (#(assoc % :std (Math/sqrt (/ (:q %) (:cnt %)))))))))
