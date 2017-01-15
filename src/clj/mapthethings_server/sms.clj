(ns mapthethings-server.sms
  (:require [compojure.core :refer [defroutes GET PUT POST DELETE ANY]]
            [clojure.tools.logging :as log]
            [environ.core :refer [env]]
            [mapthethings-server.geo :as geo]
            [mapthethings-server.grids :as grids]
            [mapthethings-server.data :as data]
            [clojurewerkz.machine-head.client :as mh]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer close! thread
                     alts! alts!! timeout]])
  (:import [com.twilio Twilio]
           [com.twilio.rest.api.v2010.account Message]
           [com.twilio.type PhoneNumber])
  (:gen-class))

; Fmt phonelen    phone-digits       msg-bytes)
;  03    0B    16 46 95 76 94 40   M e s s a g e)

(defn init []
  (Twilio/init (env :twilio-account-sid) (env :twilio-auth-token)))

(def from_phone (PhoneNumber. (env :twilio-phone-number)))

(defn send-message [{:keys [phone message]}]
  (let [to_phone (PhoneNumber. phone)
        creator (Message/creator to_phone from_phone message)
        message (.create creator)]
    (.getSid message)))

(defn error-response [code msg]
  {:status code
   :headers {"Content-Type" "text/plain"}
   :body msg})

(defn handle-incoming [req]
  (let [[msg, error-msg] ["Response" nil]]
    (if msg
      {:status 200 :body (str
                          "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>"
                          #_(get-in req [:params :Body])
                          "</Response>")}
      (error-response 400 error-msg))))

(defn handle-status [req]
  (let [[msg, error-msg] ["Success" nil]]
    (if msg
      {:status 204} ; HTTP "No Content"}
      (error-response 400 error-msg))))
