(defproject thingsburg-server "1.0.0-SNAPSHOT"
  :description "Thingsburg"
  :url "http://thingsburg-server.herokuapp.com"
  :license {:name "Proprietary Copyright (c) 2016 Frank Leon Rose"}
  ;:repositories {"local" "file:${project.basedir}/local_repo"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/core.cache "0.6.5"]
                 [ch.hsr/geohash "1.3.0"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                 javax.jms/jms
                                                 com.sun.jmdk/jmxtools
                                                 com.sun.jmx/jmxri]]
                 [compojure "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [ring/ring-defaults "0.2.0"]
                 [nilenso/mailgun "0.1.0-SNAPSHOT"]
                 [environ "1.0.3"]
                 [hickory "0.6.0"]
                 [org.clojure/data.codec "0.1.0"]
                 ;;ClojureScript
                 [org.clojure/clojurescript "1.7.122"]
                 [cljs-ajax "0.5.1"]
                 [prismatic/dommy "1.1.0"]
                 [clojurewerkz/machine_head "1.0.0-beta9"]
                 [amazonica "0.3.57"]
                 ;[com.amazonaws.geo/dynamodb-geo "1.0.0"]
                 ]
  :min-lein-version "2.0.0"
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :plugins [
    [lein-environ "1.0.3"]
    [lein-figwheel "0.4.1"]
    ]
  :hooks [lein-environ.plugin/hooks]
  :uberjar-name "thingsburg-server-standalone.jar"
  :main thingsburg-server.web
  :aot [thingsburg-server.web]
  :cljsbuild {
              :builds [ { :id "thingsburg-server"
                         :source-paths ["src/cljs"]
                         :figwheel true
                         :compiler {:main "thingsburg-server.app"
                                    :asset-path "js/out"
                                    :output-to "resources/public/js/app.js"
                                    :output-dir "resources/public/js/out"} } ]
              }
  :profiles {
    :uberjar {:aot :all}
    :production {:env {:production true}}
    :dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                          [ring/ring-mock "0.3.0"]
                          ]}})
