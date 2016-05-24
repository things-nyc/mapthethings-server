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
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  (:import [ch.hsr.geohash GeoHash])
  (:gen-class))

(defn splash []
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (slurp (io/resource "map.html"))})

(defn view-grids-response [lat1 lon1 lat2 lon2]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str (mapv grids/s3-url (grids/view-grids lat1 lon1 lat2 lon2)) :escape-slash false)})

(defn ttn-handler []
  (let [in (chan)]
    (go-loop []
      (when-let [ttn-string (<! in)]
        (try
          (log/debug "Received ttn-string" ttn-string)
          (let [ttn (data/ttn-string->clj ttn-string)
                msg (data/ttn->msg ttn)]
            (log/debug "Converted to msg:" msg)
            (grids/handle-msg msg))
          (catch Exception e
            (log/error e "Failed handling TTN message")))
        (recur)))
      ; (close! in) TODO
    in))

(defn ping-handler []
  (let [in (chan)]
    (go-loop []
      (when-let [ping (<! in)]
        (try
          (log/debug "Received ping-string" ping)
          (let []
            (grids/handle-msg ping))
          (catch Exception e
            (log/error e "Failed handling ping.")))
        (recur)))
      ; (close! in) TODO
    in))

(defroutes routes
  (GET "/" [] (splash))
  (GET "/api/v0/refs/:lat1/:lon1/:lat2/:lon2"
    [lat1 lon1 lat2 lon2 :as request]
    (view-grids-response (edn/read-string lat1) (edn/read-string lon1) (edn/read-string lat2) (edn/read-string lon2)))
  (POST "/inbound-email-mime"
    ;curl --form param1=value1 --form Return-Path=value2 http://localhost:5000/inbound-email-mime
    {{inbound-chan :inbound-chan} :services :as request}
    #_(handler request inbound-chan))
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
        services (if ic services (assoc services :inbound-chan (ping-handler)))]
    (-> routes
      (wrap-services services)
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
