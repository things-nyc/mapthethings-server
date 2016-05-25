(ns thingsburg-server.web
  (:require [compojure.core :refer [defroutes GET PUT POST DELETE ANY]]
            [compojure.route :as route]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.defaults :refer :all]
            [environ.core :refer [env]]
            [thingsburg-server.geo :as geo]
            [thingsburg-server.grids :as grids]
            [thingsburg-server.data :as data]
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

(def messages-queue-name (or (env :messages-queue-name) "ThingsburgMessages"))

(def message-queue (delay (sqs/find-queue messages-queue-name)))

(defn store-raw-msg [msg]
  (grids/write-s3-as-json grids/raw-bucket (:aws-id msg) msg))

(defn handle-msg [msg raw]
  (let [sqs-msg (sqs/send-message @message-queue (prn-str msg))
        msg (assoc msg :aws-id (:message-id sqs-msg))] ; Use sqs ID generally as unique ID
    (store-raw-msg (assoc msg :raw-payload raw))
    (grids/update-grids-with-msg msg)))

(defn ttn-handler []
  (let [in (chan)]
    (go-loop []
      (when-let [ttn-string (<! in)]
        (try
          (log/debug "Received ttn-string" ttn-string)
          (let [ttn (data/ttn-string->clj ttn-string)
                msg (data/ttn->msg ttn)]
            (log/debug "Converted to msg:" msg)
            (handle-msg msg ttn-string))
          (catch Exception e
            (log/error e "Failed handling TTN message")))
        (recur)))
    in))

(defn ping-response [ping req-body]
  (try
      (log/debug "Received ping-string" ping)
      (handle-msg ping req-body)
      {:status 201 ; HTTP "Created"
       :headers {"Content-Type" "application/json"}
       :body (json/write-str ping :escape-slash false)}
    (catch Exception e
      (let [error-msg (format "Failed to handle ping [%s]." (str ping))]
        (log/error e error-msg)
        (error-response 500 error-msg)))))

(defn get-client-ip
  "http://stackoverflow.com/a/30022208/1207583"
  [req]
  (if-let [ips (get-in req [:headers "x-forwarded-for"])]
    (-> ips (clojure.string/split #",") first)
    (:remote-addr req)))

(defn parse-ping-request [{params :params :as req}]
  (let [slat (:latitude params (:lat params))
        lat (edn/read-string slat)
        slon (:longitude params (:lng params (:lon params)))
        lon (edn/read-string slon)]
    (if (or (nil? lat) (nil? lon))
      [nil, (format "Invalid ping lat/lon: %s/%s" slat slon)]
      [{
        :type "ping"
        :lat lat :lon lon
        :timestamp (or (:timestamp params) (current-timestamp))
        :msgid (:msgid params)
        :appkey (:appkey params)
        :client-ip (get-client-ip req)
      }, nil])))

(defroutes routes
  (GET "/" [] (splash))
  (GET "/api/v0/grids/:lat1/:lon1/:lat2/:lon2"
    [lat1 lon1 lat2 lon2 :as request]
    (view-grids-response (edn/read-string lat1) (edn/read-string lon1) (edn/read-string lat2) (edn/read-string lon2)))
  (POST "/api/v0/pings" req
    (let [[ping, error-msg] (parse-ping-request req)]
      (if ping
        (ping-response ping (prn-str (:params req)))
        (error-response 400 error-msg))))
  (ANY "*" []
       (route/not-found (slurp (io/resource "404.html")))))

(defn wrap-services [f services]
  (fn [req]
    (f (assoc req :services services))))

(defn wrap-log-request [f]
  (fn [req]
    (log/info "log-request:" (str req))
    (f req)))

(defn make-app
  ([]
  (make-app {}))

  ([services]
  (let [ic (:inbound-chan services)
        ;services (if ic services (assoc services :inbound-chan (ping-handler)))
        ]
    (-> routes
      ;(wrap-services services)
      (wrap-defaults api-defaults)
      #_(wrap-log-request)))))

(defn connect-to-ttn []
  (let [work-channel (ttn-handler)
        id   (mh/generate-id)
        mqtt-url (env :ttn-mqtt-url "tcp://staging.thethingsnetwork.org:1883")
        conn (mh/connect mqtt-url id
              {:username (env :ttn-app-eui) :password (env :ttn-access-password)})]
    ;; Topic: <AppEUI>/devices/<DevEUI>/up
    (mh/subscribe conn {"+/devices/+/up" 0}
      (fn [^String topic _ ^bytes payload]
        (let [json-string (String. payload "UTF-8")]
          (log/debug "Received:" json-string)
          (go (>! work-channel json-string))
          #_(mh/disconnect conn))))
    (log/info "Subscribed to " mqtt-url)))

#_(let [ddb (geo/get-ddb)
      manager (geo/geo-manager ddb)
      ;table (create-table ddb)
      ]
  (log/info #_(.toString table) (.toString manager)))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))
        app (make-app)]
    (log/info "Binding to:" (str port))
    (connect-to-ttn)
    (jetty/run-jetty app {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
