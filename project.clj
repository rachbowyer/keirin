;; Copyright (c) Rachel Bowyer 2016. All rights reserved.
;;
;; This program and the accompanying materials
;; are made available under the terms of the Eclipse Public License v1.0
;; which accompanies this distribution, and is available at
;; http://www.eclipse.org/legal/epl-v10.html

(defproject keirin "0.1.0-SNAPSHOT"
  :description "Microbenchmarking library for Clojure"
  :url "https://github.com/rachbowyer/keirin"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  ;:jvm-opts ^:replace ["-server" "-verbose:gc"]
  :jvm-opts ^:replace ["-server" "-Xloggc:gc.out"]
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :main ^:skip-aot keirin.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
