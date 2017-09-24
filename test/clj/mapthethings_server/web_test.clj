(ns mapthethings-server.web-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close!]]
            [clojure.string :refer [blank? join trim]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [cemerick.friend :as friend]
            [mapthethings-server.grids :as grids]
            [mapthethings-server.web :refer :all]))

(deftest main-page-test
  (testing "main page"
    (let [app (make-app)
          response (app (request :get "/"))]
      (is (= 200 (:status response)))
      (is (.contains (:body response) "var mymap = L.map('mapid')")))))

(deftest view-grids-test
  (testing "view grids endpoint"
    (let [app (make-app)
          response (app (request :get "/api/v0/grids/10.0/10.0/8.0/12.0"))
          grid-ids ["E70CQ-v0","B70CQ-v0","A70CQ-v0","F60CQ-v0","4D0CQ-v0","1D0CQ-v0","0D0CQ-v0","5C0CQ-v0"]
          expectation (format "[%s]" (join "," (map (partial format "\"https://s3.amazonaws.com/%s/%s\"" grids/grid-bucket) grid-ids)))]
      (is (= 200 (:status response)))
      (is (= expectation (:body response))))))

(defn fake-twitter-workflow [request]
  ; (prn "fake-twitter-workflow" request)
  (let [fake-cred-fn (get-in request [:cemerick.friend/auth-config :credential-fn])]
    (if-let [user-record (fake-cred-fn {})]
      (cemerick.friend.workflows/make-auth ; Which copies :username to :identity
        user-record
        {:cemerick.friend/workflow :twitter-workflow
         :cemerick.friend/redirect-on-auth? false
         :cemerick.friend/ensure-session true})
      {:status 401
       :headers {"Content-Type" "text/plain"
                 "WWW-Authenticate" (format "FakeTwitterAuth realm=\"%s\"" "FAKE!")}})))

(defn fake-credentials [keys]
  {:identity "fake-id"
    :username "fakename"
    :name "fake name"
    :profile-image "http://domain.com/image"
    :roles #{:mapthethings-server.web/user}})

(deftest msg-sent-test
  (testing "post transmissions endpoint"
    (let [original-authenticate friend/authenticate]
      (with-redefs [friend/authenticate (fn [routes config] (original-authenticate routes (assoc config
                                                                                           :workflows [fake-twitter-workflow]
                                                                                           :credential-fn fake-credentials)))]
        (let [app (make-app)
              msg {"lat" 40.0 "lon" -74.0 "alt" 10.0 "timestamp" "2016-05-25T15:30:26.713Z" "dev_eui" "0123456789ABCDEF" "msg_seq" 12345}
              json (json/write-str msg)
              response (app (request :post "/api/v0/transmissions" json))]
          (is (= 201 (:status response)))
          (is (= "{\"mtt\":true,\"type\":\"attempt\",\"lat\":40.0,\"lon\":-74.0,\"timestamp\":\"2016-05-25T15:30:26.713Z\",\"msg_seq\":12345,\"dev_eui\":\"0123456789ABCDEF\",\"client-ip\":\"localhost\"}" (:body response))))))))
