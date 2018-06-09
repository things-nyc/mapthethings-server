(ns mapthethings-server.batch-test
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [mapthethings-server.batch :refer :all]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread onto-chan put!
                     alts! alts!! timeout]]))

(defrecord MockS3
  [msg]

  component/Lifecycle
  (start [this]
    (log/info "MockS3 starting:" (:msg this))
    this)
  (stop [this]
    (try
      (log/info "MockS3 stopping:" (:msg this))
      (assoc this :msg nil)
      (catch Throwable t
        (log/warn t "Error when stopping S3"))))

  S3protocol
  (read-json [this bucket-name key obj-chan]
    (put! obj-chan {:error "Unimplemented"} (fn [_] (close! obj-chan))))
  (write-json [this bucket-name key obj]
    (go {:error "Unimplemented"}))
  (get-json-bucket [this bucket-name list-chan]
    (put! list-chan {:error "Unimplemented"} (fn [_] (close! list-chan)))))


(deftest partition-by-onto-chan-transducer
  (testing "partition into channel transducer")
  (let [input (vec (range 20))
        output (sequence
                (mapthethings-server.batch/partition-by-onto-chan identity)
                (range 20))
        ; All these ways block.
        ; merged (async/merge output)
        ; output (<!! (async/reduce conj [] merged))]
        ; output (<!! (async/reduce conj [] (async/merge output)))]
        ; output (<!! (go-loop [acc [] v (<! merged)]
        ;               (if (nil? v)
        ;                 acc
        ;                 (recur (conj acc v) (<! merged)))))]
        output (<!! (go-loop [acc [] chs output]
                      (if chs
                        (recur (conj acc (<! (first chs))) (next chs))
                        acc)))]
    (is (= input output)))
  (let [input (vec (range 20))
        output (chan 10 (mapthethings-server.batch/partition-by-onto-chan identity))
        _ (onto-chan output input)
        output (<!! (go-loop [acc [] ch (<! output)]
                      (if ch
                        (recur (conj acc (<! ch)) (<! output))
                        acc)))]
    (is (= input output)))

  (testing "when it is actually partitioning")
  (let [input (vec (mapcat #(identity [% %]) (range 20)))
        output (chan 10 (comp
                          (mapthethings-server.batch/partition-by-onto-chan identity)
                          (map #(async/reduce conj [] %))))
        _ (onto-chan output input)
        output (<!! (go-loop [acc [] ch (<! output)]
                      (if ch
                        (recur (concat acc (<! ch)) (<! output))
                        acc)))]
    (is (= input output)))

  (testing "Timing when it is actually partitioning")
  #_
  (let [input (vec (mapcat #(identity [% %]) (range 20)))
        output (chan 10 (comp
                          (mapthethings-server.batch/partition-by-onto-chan identity)
                          (map #(async/reduce conj [] %))))
        feed (go-loop [in (seq input)] ; Feed with delay to demonstrate that output gets items only as they are issued.
              (if in
                (do
                  (println "Feeding" (first in))
                  (<! (async/timeout 200))
                  (>! output (first in))
                  (recur (next in)))
                (async/close! output)))
        output (<!! (go-loop [acc [] ch (<! output)]
                      (println acc)
                      (if ch
                        (recur (concat acc (<! ch)) (<! output))
                        acc)))]
        ; Wait for both feed and output to close.
        ; output (<!! (go-loop []
        ;               (let [[val ch] (async/alts! [output feed])
        ;                     result (if (= ch output) val)]
        ;                 (if result
        ;                   result
        ;                   (recur)))))]
    (is (= input output))))
