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
  [stat value]
  (if (nil? stat)
    {:cnt 1 :avg value :std 0.0 :q 0.0}
    (let [avg-old (:avg stat)
          avg-new (cummulative-avg avg-old value (:cnt stat))]
      (-> stat
        (update :cnt incnil)
        (assoc :avg avg-new)
        (update :q cummulative-qf value avg-old avg-new)
        (#(assoc % :std (Math/sqrt (/ (:q %) (:cnt %)))))))))
