;; Copyright ©️ Rachel Bowyer 2016, 2017, 2020. All rights reserved.
;;
;; This program and the accompanying materials
;; are made available under the terms of the Eclipse Public License v1.0
;; which accompanies this distribution, and is available at
;; http://www.eclipse.org/legal/epl-v10.html

(defproject rachbowyer/keirin "1.1.1"
  :description "Microbenchmarking library for Clojure"
  :url "https://github.com/rachbowyer/keirin"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.logging "0.3.1"]]

  :target-path "target/%s"

  :profiles {:dev {:global-vars {*warn-on-reflection* false *assert* true}
                   :jvm-opts ^:replace ["-Dclojure.compiler.direct-linking=true" "-server"
                                        "-Xlog:gc:gc.out" "-XX:-TieredCompilation" "-Xbatch"]}

             :uberjar {:jvm-opts ^:replace ["-Dclojure.compiler.direct-linking=true"]
                       :aot :all}})
