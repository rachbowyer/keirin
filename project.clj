;; Copyright ©️ Rachel Bowyer 2016, 2017. All rights reserved.
;;
;; This program and the accompanying materials
;; are made available under the terms of the Eclipse Public License v1.0
;; which accompanies this distribution, and is available at
;; http://www.eclipse.org/legal/epl-v10.html

(defproject keirin "1.0.0"
  :description "Microbenchmarking library for Clojure"
  :url "https://github.com/rachbowyer/keirin"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/tools.logging "0.3.1"]]

  :target-path "target/%s"
  :profiles {:dev {:global-vars {*warn-on-reflection* true *assert* true}
                   :jvm-opts ^:replace ["-Dclojure.compiler.direct-linking=true" "-server"
                                        "-Xloggc:gc.out" "-XX:-TieredCompilation" "-Xbatch" "-XX:CICompilerCount=1"]}

             :uberjar {:jvm-opts ^:replace ["-Dclojure.compiler.direct-linking=true"]
                       :aot :all}})
