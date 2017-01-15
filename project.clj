(defproject mapthethings-server "1.0.0"
  :description "MapTheThings-Server is the server side of MapTheThings, a global coverage map for The Things Network"
  :url "http://mapthethings.herokuapp.com"
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/mit-license.php"}
  ;:repositories {"local" "file:${project.basedir}/local_repo"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.391"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/data.csv "0.1.3"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/core.cache "0.6.5"]
                 [ch.hsr/geohash "1.3.0"]
                 [com.twilio.sdk/twilio "7.4.0"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [compojure "1.5.2"]
                 [ring/ring-jetty-adapter "1.5.0"]
                 [ring/ring-defaults "0.2.1"]
                 [environ "1.1.0"]
                 [org.clojure/data.codec "0.1.0"]
                 ;;ClojureScript
                 [org.clojure/clojurescript "1.9.229"]
                 [cljs-ajax "0.5.8"]
                 [prismatic/dommy "1.1.0"]
                 [clojurewerkz/machine_head "1.0.0-beta9"]

                 ; Assert Jackson common versions (brought in by Amazonica & cljs-ajax)
                 [com.fasterxml.jackson.core/jackson-core "2.7.5"]
                 [com.fasterxml.jackson.core/jackson-databind "2.7.0"]
                 [com.fasterxml.jackson.core/jackson-annotations "2.7.8"]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-cbor "2.7.8"]
                 [com.fasterxml.jackson.dataformat/jackson-dataformat-smile "2.7.8"]

                 [amazonica "0.3.76"]
                 [clj-time "0.12.0"]]
                 ;[com.amazonaws.geo/dynamodb-geo "1.0.0"]

  :min-lein-version "2.0.0"
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :plugins [
            [lein-environ "1.1.0"]
            [lein-figwheel "0.5.8"]]

  :hooks [lein-environ.plugin/hooks]
  :uberjar-name "mapthethings-server-standalone.jar"
  :main mapthethings-server.web
  :aot [mapthethings-server.web]
  :cljsbuild {
              :builds [ { :id "mapthethings-server"
                         :source-paths ["src/cljs"]
                         :figwheel true
                         :compiler {:main "mapthethings-server.app"
                                    :asset-path "js/out"
                                    :output-to "resources/public/js/app.js"
                                    :output-dir "resources/public/js/out"}}]}

  :profiles {
             :uberjar {:aot :all}
             :production {:env {:production true}}
             :profiles {:dev {:source-paths ["dev"]
                                :dependencies []}}
             :dev {:source-paths ["dev"]
                   :dependencies [[javax.servlet/servlet-api "2.5"]
                                  [ring/ring-mock "0.3.0"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [org.clojure/java.classpath "0.2.3"]]}})
