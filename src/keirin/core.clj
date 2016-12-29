;; Copyright (c) Rachel Bowyer 2016. All rights reserved.
;;
;; This program and the accompanying materials
;; are made available under the terms of the Eclipse Public License v1.0
;; which accompanies this distribution, and is available at
;; http://www.eclipse.org/legal/epl-v10.html

(ns keirin.core
  (:require [clojure.java.io :as io])
  (:import (java.lang.management ManagementFactory)))


;;;
;;; Default parameters
;;;

(def ^:private gc-output-file "gc.out")
(def ^:private gc-attempts 5)
(def ^:private warm-up 5)
(def ^:private default-num-trials 20)
(def ^:private max-failures 10)


;;;
;;; Memory utilities
;;;

(defn request-gc []
  (dotimes [_ gc-attempts]
    (System/runFinalization)
    (System/gc)))

(defn- free-memory
  "Free memory - bytes"
  []
  (.freeMemory (java.lang.Runtime/getRuntime)))


(defn calc-memory-usage [f]
  (System/gc)
  (let [before (free-memory)]
    (f)
    (/ (- before (free-memory)) 1048576.0)))


;;;
;;; Stats functions
;;;

(defn- sq [x]
  (* x x))

(defn- mean[data]
  (/ (reduce + data) (count data)))

(defn- sample-variance [data]
  (let [mean (double (mean data))
        num (count data)]
    (when (> num 1)
      (/ (transduce (map #(sq (- % mean))) + data)
         (dec num)))))

(defn- sample-standard-deviation [data]
  (Math/sqrt (sample-variance data)))


;;;
;;; Bench marking utilities
;;;


(defn- ensure-gc-options! []
  (let [required-option (str "-Xloggc:" gc-output-file)
        jvm-options (.getInputArguments (ManagementFactory/getRuntimeMXBean))]
    (when-not (some #(= % required-option) jvm-options)
      (throw (RuntimeException. (str "Must set JVM option: " required-option))))))

(defn- read-gc-file []
  (if (.exists (io/as-file gc-output-file))
    (slurp gc-output-file)
    ""))

(defn- bench-one [payload & {:keys [verbose]}]
  (when verbose
    (println "Requesting GC..."))
  (request-gc)
  (when verbose
    println "GC requested")

  (let [gc-file-before (read-gc-file)
        start (System/nanoTime)]
    (payload)

    (let [end (System/nanoTime)
          _ (Thread/sleep 300) ;; Let GC file catch up
          gc-file-end (read-gc-file)]

      {:gc-occurred (not= gc-file-before gc-file-end)
       :time-taken (/ (- end start) 1000000.0)})))


(defn- iterate-bench [payload & {:keys [verbose num-trials] :as options}]
  (loop [gc-occurred-count   0
         iters               0
         times               []]

    (when verbose
      (println "Running trial..."))
    (let [{:keys [gc-occurred :time-taken]} (apply bench-one payload (-> options seq flatten))
          new-gc-occurred-count             (cond-> gc-occurred-count gc-occurred inc)
          new-iters                         (cond-> iters (not gc-occurred) inc)
          new-times                         (cond-> times (not gc-occurred) (conj time-taken))]

      (when verbose
        (println "Trial complete"))

      (if (or (> new-gc-occurred-count max-failures)
              (> new-iters num-trials))
        times
        (recur new-gc-occurred-count new-iters new-times)))))

(defn bench* [payload & {:keys [verbose num-trials]
                        :or {num-trials default-num-trials}
                        :as options}]
  (ensure-gc-options!)

  (when verbose
    (println "Warming up JVM..."))
  (dotimes [_ warm-up] (payload))
  (when verbose
    (println "JVM warmed up"))

  (let [results (apply iterate-bench payload (-> options (merge {:num-trials num-trials}) seq flatten))]
    {:trials          (count results)
     :failed-trials   (- num-trials (count results))
     :mean            (double (mean results))
     :std             (sample-standard-deviation results)}))

(defmacro bench [payload & options]
  `(bench* (fn [] ~payload) ~@options))
