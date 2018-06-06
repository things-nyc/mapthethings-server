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


(deftest parse-dev-eui-from-topic
  (testing "parsing dev-eui from topic string")
  (let [dev-eui ""]
    (is (= "DEVID" dev-eui))))
