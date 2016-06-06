(defproject clj-cas-client "0.0.7"
  :description "Makes it possible to wrap a Cas Client middleware around Ring handlers"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.jasig.cas.client/cas-client-core "3.4.1"]
                 [ring "1.4.0"]]
  :aot [clj-cas-client.core])
