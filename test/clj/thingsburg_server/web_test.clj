(ns thingsburg-server.web-test
  (:require [clojure.test :refer :all]
            [clojure.data.json :as json]
            [clojure.core.async
             :refer [>! <! >!! <!! go chan close!]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [thingsburg-server.web :refer :all]))

(deftest splash-test
  (testing "splash endpoint"
    (let [app (make-app)
          response (app (request :get "/"))]
      (is (= 200 (:status response)))
      (is (= "Make requests better." (:body response))))))

#_(deftest inbound-email
  (testing "inbound email endpoint"
    (let [email {
            "sender" "initiator@email.com"
            "Return-Path" "<initiator@email.com>"
            "recipient" "r@req.futurose.com"
            "Subject" "Request for Information"
            "body-plain" "Text plain"
            "body-html" "<html><body><p>This is HTML paragraph.</p></body></html>"
          }
          ic (chan 1)
          app (make-app {:inbound-chan ic})
          req (request :post "/inbound-email" email)
          response (app req)]
      (is (= 200 (:status response)))
      (is (= "" (:body response)))
      (let [inmail (<!! ic)]
        (is (= "initiator@email.com" (:sender inmail))))
      (close! ic))))

#_(deftest inbound-email-mime
  (testing "inbound email endpoint"
    (let [email {
            "sender" "initiator@email.com"
            "Return-Path" "<initiator@email.com>"
            "recipient" "r@req.futurose.com"
            "Subject" "Request for Information"
            "body-mime" (slurp "test/mail-files/simple-email2.mime")
          }
          ic (chan 1)
          app (make-app {:inbound-chan ic})
          req (request :post "/inbound-email-mime" email)
          response (app req)]
      (is (= 200 (:status response)))
      (is (= "" (:body response)))
      (let [inmail (<!! ic)]
        #_(log/debug (str inmail))
        (is (= "initiator@email.com" (:sender inmail))))
      (close! ic))))
