(ns thingsburg-server.web
  (:require [compojure.core :refer [defroutes GET PUT POST DELETE ANY]]
            [compojure.route :as route]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.defaults :refer :all]
            [environ.core :refer [env]]
            [thingsburg-server.geo :as geo]
            [thingsburg-server.grids :as grids]
            [clojurewerkz.machine-head.client :as mh]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]])
  (:gen-class))

(defn splash []
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Make requests better."})

(defn inbound-processor []
  (let [in (chan)]
    (go (while true
      (try
        (let [work (<! in)]
          ; Process work
          )
        (catch Exception e
          (log/error e "Failed to do work")))))
      ; (close! in) TODO
    in))

(defroutes routes
  (GET "/" [] (splash))
  (POST "/inbound-email"
    ;curl --data "param1=value1&param2=value2" http://localhost:5000/inbound-email --header "X-MyHeader: 123"
    {{inbound-chan :inbound-chan} :services :as request}
    #_(handler request inbound-chan))
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
        services (if ic services (assoc services :inbound-chan (inbound-processor)))]
    (-> routes
      (wrap-services services)
      (wrap-defaults api-defaults)
      #_(wrap-log-request)))))

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))
        app (make-app)]
    (log/info "Binding to:" (str port))
    #_(let [ddb (geo/get-ddb)
          manager (geo/geo-manager ddb)
          ;table (create-table ddb)
          ]
      (log/info #_(.toString table) (.toString manager)))
    (let [id   (mh/generate-id)
          conn (mh/connect "tcp://staging.thethingsnetwork.org:1883" id
                {:username (env :ttn-app-eui) :password (env :ttn-access-password)})]
      ;; Topic: <AppEUI>/devices/<DevEUI>/up
      (mh/subscribe conn {"+/devices/+/up" 0} (fn [^String topic _ ^bytes payload]
                                     (log/info "Received:" (String. payload "UTF-8"))
                                     #_(mh/disconnect conn)
                                     #_(System/exit 0))))
    (jetty/run-jetty app {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
