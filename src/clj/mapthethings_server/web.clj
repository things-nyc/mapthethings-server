(ns mapthethings-server.web
  (:require [compojure.core :refer [defroutes GET PUT POST DELETE ANY]]
            [compojure.route :as route]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.defaults :refer :all]
            #_[ring.middleware.stacktrace :as trace]
            [environ.core :refer [env]]
            [mapthethings-server.geo :as geo]
            [mapthethings-server.grids :as grids]
            [mapthethings-server.data :as data]
            [mapthethings-server.sms :as sms]
            [clojurewerkz.machine-head.client :as mh]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.sqs :as sqs]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  (:import [ch.hsr.geohash GeoHash])
  (:gen-class))

(def current-timestamp
  (let [f (time-format/formatters :date-time)]
    (fn []
      (time-format/unparse f (time/now)))))

(defn splash []
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (io/resource "map.html"))})

(defn error-response [code msg]
 {:status code
  :headers {"Content-Type" "text/plain"}
  :body msg})

(defn view-grids-response [lat1 lon1 lat2 lon2]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str (mapv grids/s3-url (grids/view-grids lat1 lon1 lat2 lon2)) :escape-slash false)})

(def messages-queue-name (or (env :messages-queue-name) "nyc_thethings_map_messages"))

(def message-queue (delay (sqs/find-queue messages-queue-name)))

(defn store-raw-msg [msg-id msg]
  (grids/write-s3-as-json grids/raw-bucket msg-id (assoc msg :aws-id msg-id)))

(defn handle-msg [msg raw]
  (sqs/send-message @message-queue (prn-str {:msg msg :raw-msg raw})))

(defn wait-all
  "Waits for all channels and timeout in msecs. Returns true if succeeded."
  [channels wait-msecs]
  (let [tc (timeout wait-msecs)]
    (<!! (go-loop [channels (conj channels tc)]
          (if (= 1 (count channels))
            true ; The only channel left is the timeout. Yay!
            (let [[_ c] (alts! (vec channels))]
              (if (identical? c tc)
                false ; Timeout signaled. Drat!
                (recur (remove #(identical? % c) channels)))))))))

(defn process-sqs-msg [{msg-id :message-id body-string :body :as sqs-msg}]
  ; {
  ;   :attributes {
  ;     :ApproximateFirstReceiveTimestamp "1464711665873",
  ;     :SentTimestamp "1464661271995",
  ;     :ApproximateReceiveCount "1",
  ;     :SenderId "AIDAJSWP24JQK6WQFWTLE"
  ;   },
  ;   :message-id "904b266a-8ec7-4ef5-a646-44affa26dfc9",
  ;   :receipt-handle "AQEBhIISGic7lgQnlLhkGf66JmIAvNDBiAM1KqM7sOCyo1n1mEE4ZCG7OE71IefvpN/XOFaZmslz+GbmwYoHWfCjq8VpYivTl2LfZTq/WpZPpmWBlJQ3VsTRX6XPrTPkAhCwrXD9fhrElfmR9XTuEyqB/zSPE/KE7poS5hWWuc31UgfmYhgtsAwMki5bIQWxIxqilai/R1ep59U3KD7hz3TpbFD5oGXw2WVJWnyDFowkrJ3xQ2lW5tWRlw8isNXO/XxRLH4XkYxUJxwW5mnuRzX2w/HQY7/EtysTr2Rq9mypNLA3llBWa1b0zjv33QKDuLtTb0VmjXVHLcTqMSQD66btTTuDJintejEq0yyL7tmokTgwIGoMxsnzwLImme7rT2LoZU3IeRQ1B1UESpRbXgWTqg==",
  ;   :body "{:type \"attempt\", :lat 40.756697, :lon -74.03635, :timestamp \"2016-05-23T14:26:11.399644707Z\", :rssi -17, :lsnr 12.2, :msgid nil, :appkey nil}\n",
  ;   :md5of-body "f02e18460d467c04c9dd6527aca26e92"
  ; }
  (let [body (edn/read-string body-string)
        msg (:msg body)
        msg (assoc msg :aws-id msg-id)
        is-test (:test-msg msg false)
        success (or is-test ; To get here is success for test message
                  ; Otherwise, for a real message wait for storing raw message in S3 and updating of grids
                  (wait-all (conj (grids/update-grids-with-msg msg)
                             (store-raw-msg msg-id body)) 10000))] ; Use sqs ID generally as unique ID
    (if (not success)
      (log/warn "Failed to process message within 10 seconds") ; Allow it to be re-offered by SQS
      (do
        (if is-test
          (log/debug "Deleting test message" msg-id)
          (log/debug "Deleting message" msg-id))
        (sqs/delete-message (assoc sqs-msg :queue-url @message-queue))))))

(defn retry-fn [f delay msg]
  (fn [& args]
    (loop []
      (let [[v e]
            (try
              [(apply f args) nil]
             (catch Exception e
               (log/error e msg)
               (<!! (timeout delay))
               [nil e]))]
        (if (some? e)
          (recur)
          v)))))

(def receive-messages
  (retry-fn
    #(sqs/receive-message
      :queue-url @message-queue
      :wait-time-seconds 10
      :max-number-of-messages 10
      :delete false
      :attribute-names ["All"])
    5000 "Error receiving SQS messages"))

(defn sqs-handler []
  (log/info "Starting SQS handler listening to" (str @message-queue))
  (go-loop []
    (let [msgs (receive-messages)
          msgs (:messages msgs)]
      (doseq [msg msgs]
        (try
          (process-sqs-msg msg)
          (catch Exception e
            (log/error e (str "Failed processing SQS message"  (:message-id msg)))))))
    (recur)))

(defn ttn-handler [extract-data]
  (let [in (chan)]
    (go-loop []
      (when-let [ttn-string (<! in)]
        (try
          (log/debug "Received ttn-string" ttn-string)
          (let [json (data/parse-json-string ttn-string)
                msg (extract-data json)
                err (:error msg)]
            (if err
              (log/error (str "Failed to handle TTN message. " err))
              (do
                (log/debug "Converted to msg:" msg)
                (handle-msg msg ttn-string))))
          (catch Exception e
            (log/error e "Failed to handle TTN message")))
        (recur)))
    in))

(defn msg-sent-response [msg req-body]
  (try
      (log/debug "Received transmissions-packet" msg)
      (handle-msg msg req-body)
      {:status 201 ; HTTP "Created"
       :headers {"Content-Type" "application/json"}
       :body (json/write-str msg :escape-slash false)}
    (catch Exception e
      (let [error-msg (format "Failed to handle transmissions-packet [%s]." (str msg))]
        (log/error e error-msg)
        (error-response 500 error-msg)))))

(defn get-client-ip
  "http://stackoverflow.com/a/30022208/1207583"
  [req]
  (if-let [ips (get-in req [:headers "x-forwarded-for"])]
    (-> ips (clojure.string/split #",") first)
    (:remote-addr req)))

(defn parse-msg-sent-request [{body :body :as req}]
  (let [json (slurp body)
        params (json/read-str json :key-fn keyword)
        slat (:latitude params (:lat params))
        lat (data/parse-lat slat)
        slon (:longitude params (:lng params (:lon params)))
        lon (data/parse-lon slon)]
    (if (or (nil? lat) (nil? lon))
      [nil, (format "Invalid transmissions-packet lat/lon: %s/%s" slat slon)]
      [{
        :type "attempt"
        :lat lat :lon lon
        :timestamp (or (:timestamp params) (current-timestamp))
        :msg_seq (:msg_seq params)
        :dev_eui (:dev_eui params)
        :client-ip (get-client-ip req)}
       , nil])))

(defn handle-transmission-notice [req]
  (let [[msg, error-msg] (parse-msg-sent-request req)]
    (if msg
      (msg-sent-response msg (prn-str (:params req)))
      (error-response 400 error-msg))))

(defroutes routes
  (GET "/" [] (splash))
  (GET "/api/v0/grids/:lat1/:lon1/:lat2/:lon2"
    [lat1 lon1 lat2 lon2]
    (view-grids-response (edn/read-string lat1) (edn/read-string lon1) (edn/read-string lat2) (edn/read-string lon2)))
  (POST "/api/v0/transmissions" req (handle-transmission-notice req))

  (POST "/sms/in" req (sms/handle-incoming req))
  (POST "/sms/status" req (sms/handle-status req))

  (ANY "*" []
    (route/not-found (slurp (io/resource "404.html")))))

(defn wrap-services [f services]
  (fn [req]
    (f (assoc req :services services))))

(defn wrap-log-request [f]
  (fn [req]
    ; Don't slurp the :body here without replacing it. You'll hit EOF trying to slurp it later.
    (log/info "log-request:" (str req))
    #_(println "log-request:" (str req))
    (f req)))

(defn make-app
  ([]
   (make-app {}))

  ([services]
   (let [ic (:inbound-chan services)]
        ;services (if ic services (assoc services :inbound-chan (msg-sent-handler)))

     (-> routes
      ;(wrap-services services)
       (wrap-defaults api-defaults)
       (wrap-keyword-params)
       (wrap-params)
       (wrap-log-request)
       #_(trace/wrap-stacktrace)))))

(defn connect-to-mqtt [mqtt-url username password parser]
  (let [work-channel (ttn-handler parser)
        id   (mh/generate-id)
        mqtt-opts {:username username
                   :password password
                   :keep-alive-interval 60 #_:seconds}]
        ; _ (println "URL:" mqtt-url "Options:" mqtt-opts)]
   (letfn [
           (resubscribe [e]
             (log/error e (str "TTN Disconnected from" mqtt-url))
             (subscribe)
             (log/info "Resubscribed to TTN at" mqtt-url))
           (subscribe []
             ; Topic: <AppEUI>/devices/<DevEUI>/up
             ; TODO: Restrict subscription to single app ID
             (mh/subscribe (mh/connect mqtt-url id mqtt-opts) {"+/devices/+/up" 0} ; 0 QOS is fire-and-forget
               (fn [^String topic metadata ^bytes mqtt-msg]
                 (let [json-string-msg (String. mqtt-msg "UTF-8")]
                   (go (>! work-channel json-string-msg))))
               {:on-connection-lost resubscribe}))]
       (subscribe)
       (log/info "Subscribed to TTN at" mqtt-url))))

(defn connect-to-ttn []
  (connect-to-mqtt
    (env :ttn-mqtt-url "tcp://staging.thethingsnetwork.org:1883")
    (env :ttn-app-eui) (env :ttn-access-password)
    data/msg-from-ttn-v1)
  (connect-to-mqtt
    (env :ttnv2-mqtt-url "tcp://eu.thethings.network:1883")
    (env :ttnv2-app-id) (env :ttnv2-access-key)
    data/msg-from-ttn-v2))

#_(let [ddb (geo/get-ddb)
        manager (geo/geo-manager ddb)]
      ;table (create-table ddb)

   (log/info #_(.toString table) (.toString manager)))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))
        app (make-app)
        sqs-channel (sqs-handler)]
    (log/info "Binding to:" (str port))
    (sms/init)
    (connect-to-ttn)
    (jetty/run-jetty app {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
