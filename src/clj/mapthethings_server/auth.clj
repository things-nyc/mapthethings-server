(ns mapthethings-server.auth
  (:require [cemerick.friend.workflows :as workflows]
            [environ.core :refer [env]])
            ; [clojure.string :as string]
            ; [clojure.data.codec.base64 :as b64]
            ; [clojure.tools.logging :as log]
            ; [clojure.java.io :as io]
            ; [clojure.data.json :as json]
  (:import [twitter4j Twitter TwitterFactory User]
           [twitter4j.auth AccessToken]
           [twitter4j.conf ConfigurationBuilder]))

(def twitter-config
  (-> (ConfigurationBuilder.)
    (.setOAuthConsumerKey (env :twitter-auth-consumer-key))
    (.setOAuthConsumerSecret (env :twitter-auth-consumer-secret))
    (.build)))

(def twitter-factory (TwitterFactory. twitter-config))

(def twitter-cache (atom {}))

(defn creds-from-twitter-token [token token-secret]
  ; (prn "creds-from-twitter-token:" token ", " token-secret)
  (let [access-token (AccessToken. token token-secret #_user-id)
        twitter (.getInstance twitter-factory access-token)
        user (.verifyCredentials twitter)]
    (when user
      {:identity (str "twitter/" (.getScreenName user))
       :username (.getScreenName user)
       :name (.getName user)
       :profile-image (.getProfileImageURLHttps user)
       :roles #{:mapthethings-server.web/user}})))

(defn twitter-credential [{:keys [token token-secret] :as creds}]
  ; (prn "twitter-credential:" creds)
  (if-let [creds (get @twitter-cache [token token-secret])]
    creds
    (when-let [creds (creds-from-twitter-token token token-secret)]
      (swap! twitter-cache assoc [token token-secret] creds)
      creds)))

(defn twitter-workflow [{{:strs [authorization]} :headers :as request}]
  ; (prn "twitter-workflow" request)
  (when (and authorization (re-matches #"\s*TwitterAuth\s+(.+)" authorization))
    ; Match TwitterAuth username="handle" token="tok" token-secret="secret" realm="api"
    (let [[_ parameters] (re-matches #"\s*TwitterAuth\s+(.+)" authorization)
          creds (reduce (fn [c [_ name value]] (assoc c (keyword name) value))
                  {} (re-seq #"([^\s=]+)=\"([^\"]*)\"" parameters))
          cred-fn (get-in request [:cemerick.friend/auth-config :credential-fn] twitter-credential)]
      (if-let [user-record (cred-fn ^{:cemerick.friend/workflow :twitter-workflow} creds)]
        (workflows/make-auth ; Which copies :username to :identity
          user-record
          {:cemerick.friend/workflow :twitter-workflow
           :cemerick.friend/redirect-on-auth? false
           :cemerick.friend/ensure-session true})
        {:status 401
         :headers {"Content-Type" "text/plain"
                   "WWW-Authenticate" (format "TwitterAuth realm=\"%s\"" (:realm creds))}}))))
