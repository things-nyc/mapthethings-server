(ns mapthethings-server.sms-test
  (:require [clojure.test :refer :all]
            [environ.core :refer [env]]
            [clojure.string :refer [blank? join trim]]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [mapthethings-server.sms :as sms]
            [mapthethings-server.web :refer :all]))

; Msg post vars: "ToCountry=US&ToState=NY&SmsMessageSid=SMef9c491e0c81290ef08e0919e7fd0ed4&NumMedia=0&ToCity=&FromZip=10099&SmsSid=SMef9c491e0c81290ef08e0919e7fd0ed4&FromState=NY&SmsStatus=received&FromCity=NEW+YORK&Body=Another+test.&FromCountry=US&To=%2B16468915672&MessagingServiceSid=MG1cae3db044c47be12b81f8c22ca76bca&ToZip=&NumSegments=1&MessageSid=SMef9c491e0c81290ef08e0919e7fd0ed4&AccountSid=ACdedd47977cc2f95cef984fcee3c7949e&From=%2B16460000000&ApiVersion=2010-04-01"

#_
(deftest send-message-test
  (testing "sending a Twilio message"
    (sms/init)
    (let [msg-sid (sms/send-message {:phone (env :twilio-test-number) :message "Testing"})]
      (is (not (nil? msg-sid))))))

(deftest msg-inbound-test
  (testing "handle inbound message"
    (let [app (make-app)
          post-vars "ToCountry=US&ToState=NY&SmsMessageSid=SMef9c491e0c81290ef08e0919e7fd0ed4&NumMedia=0&ToCity=&FromZip=10099&SmsSid=SMef9c491e0c81290ef08e0919e7fd0ed4&FromState=NY&SmsStatus=received&FromCity=NEW+YORK&Body=Another+test.&FromCountry=US&To=%2B16468915672&MessagingServiceSid=MG1cae3db044c47be12b81f8c22ca76bca&ToZip=&NumSegments=1&MessageSid=SMef9c491e0c81290ef08e0919e7fd0ed4&AccountSid=ACdedd47977cc2f95cef984fcee3c7949e&From=%2B16460000000&ApiVersion=2010-04-01"
          req (header (request :post "/sms/in" post-vars) "content-type" "application/x-www-form-urlencoded")
          response (app req)]
      (is (= 200 (:status response)))
      ; (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Message>Message Received</Message></Response>" (:body response)))
      (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>" (:body response))))))

(deftest msg-status-test
  (testing "handle status message"
    (let [app (make-app)
          post-vars "ToCountry=US&ToState=NY&SmsMessageSid=SMef9c491e0c81290ef08e0919e7fd0ed4&NumMedia=0&ToCity=&FromZip=10099&SmsSid=SMef9c491e0c81290ef08e0919e7fd0ed4&FromState=NY&SmsStatus=received&FromCity=NEW+YORK&Body=Another+test.&FromCountry=US&To=%2B16468915672&MessagingServiceSid=MG1cae3db044c47be12b81f8c22ca76bca&ToZip=&NumSegments=1&MessageSid=SMef9c491e0c81290ef08e0919e7fd0ed4&AccountSid=ACdedd47977cc2f95cef984fcee3c7949e&From=%2B10000000&ApiVersion=2010-04-01"
          response (app (request :post "/sms/status" post-vars))]
      (is (= 204 (:status response))))))
