;; Copyright (c) Rachel Bowyer 2016. All rights reserved.
;;
;; This program and the accompanying materials
;; are made available under the terms of the Eclipse Public License v1.0
;; which accompanies this distribution, and is available at
;; http://www.eclipse.org/legal/epl-v10.html
;;
;; The functions "heap-used" and "force-gc" are adapted from functions
;; that are marked as "Copyright (c) Hugo Duncan" and distributed as part
;; of the Criterium library (https://github.com/hugoduncan/criterium).
;; These functions are used under the terms of the EPL v 1.0.


;; Inspired by the article http://www.ibm.com/developerworks/java/library/j-benchmark1/index.html
;; by Brent Boyer.


(ns keirin.core
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint])
  (:import (java.lang.management ManagementFactory)))


;;;
;;; Default parameters
;;;

(def ^:private warm-up-executions 10)
(def ^:private warm-up-time 10) ; seconds
(def ^:private gc-output-file "gc.out")
(def ^:private gc-attempts 5)
(def ^:private default-num-trials 10)
(def ^:private default-num-sets 6)
(def ^:private max-gc-failures 10)
(def ^:private max-compilation-failures 10)
(def ^:private max-class-loading-failures 5)
(def ^:private max-gc-attempts 100)

;;;
;;; Memory utilities
;;;

(defn request-gc []
  (dotimes [_ gc-attempts]
    (System/runFinalization)
    (System/gc)))


(defn heap-used
  "Report a (inconsistent) snapshot of the heap memory used."
  []
  (let [runtime (Runtime/getRuntime)]
    (- (.totalMemory runtime) (.freeMemory runtime))))

(defn force-gc
  "Force garbage collection and finalisers so that execution time associated
   with this is not incurred later. Up to max-attempts are made."
  ([] (force-gc max-gc-attempts))
  ([max-attempts]
   (loop [memory-used (heap-used)
          attempts 0]
     (System/runFinalization)
     (System/gc)
     (let [new-memory-used (heap-used)]
       (if (and (or (pos? (.. ManagementFactory
                              getMemoryMXBean
                              getObjectPendingFinalizationCount))
                    (> memory-used new-memory-used))
                (< attempts max-attempts))
         (recur new-memory-used (inc attempts)))))))

(defn free-memory
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


(defn- median [data]
  (let [sorted-data (sort data)
        len-data (count data)
        mid-point (quot len-data 2)]
    (if (odd? len-data)
      (nth sorted-data mid-point)
      (/ (+ (nth sorted-data (dec mid-point)) (nth sorted-data mid-point)) 2.0))))


;;;
;;; Bench marking utilities
;;;


(defn- warn-gc-options! []
  (let [required-option (str "-Xloggc:" gc-output-file)
        jvm-options (.getInputArguments (ManagementFactory/getRuntimeMXBean))]
    (when-not (some #(= % required-option) jvm-options)
      (println "WARNING. JVM option: " required-option " is not set. Keirin is unable to detect "
               "if a GC occurs during a trial run"))))

(defn- read-gc-file []
  (if (.exists (io/as-file gc-output-file))
    (slurp gc-output-file)
    ""))

(def trial-result (atom nil))

(defn- bench-one [payload & {:keys [verbose]}]
  (when verbose
    (println "Requesting GC..."))
  (force-gc)
  (Thread/sleep 300) ;; Let any GC finish
  (when verbose
    (println "GC requested"))

  (let [compilation-bean            (ManagementFactory/getCompilationMXBean)
        compilation-time-supported  (.isCompilationTimeMonitoringSupported compilation-bean)
        class-loading-bean          (ManagementFactory/getClassLoadingMXBean)
        class-loaded-before         (.getTotalLoadedClassCount class-loading-bean)
        class-unloaded-before       (.getUnloadedClassCount class-loading-bean)
        compilation-time-before     (and compilation-time-supported (.getTotalCompilationTime compilation-bean))
        gc-file-before              (read-gc-file)
        start                       (System/nanoTime)
        result                      (payload)]

    (let [end                       (System/nanoTime)
          compilation-time-after    (and compilation-time-supported (.getTotalCompilationTime compilation-bean))
          class-loaded-after        (.getTotalLoadedClassCount class-loading-bean)
          class-unloaded-after      (.getUnloadedClassCount class-loading-bean)
          _                         (Thread/sleep 300) ;; Let GC file catch up
          gc-file-end               (read-gc-file)]

      ;; Ensure JVM does not optimise away the call to (payload)
      (reset! trial-result result)

      {:gc-occurred (not= gc-file-before gc-file-end)
       :compilation-occurred (not= compilation-time-before compilation-time-after)
       :class-loading-occured (or (not= class-loaded-before class-loaded-after)
                                  (not= class-unloaded-before class-unloaded-after))
       :time-taken (/ (- end start) 1000000.0)})))


(defn- iterate-bench [payload & {:keys [verbose num-trials] :as options}]
  (loop [gc-failure-count             0
         compilation-failure-count    0
         class-loading-failure-count  0
         iters                        0
         times                        []]

    (when verbose
      (println "Running trial..."))
    (let [{:keys [gc-occurred compilation-occurred class-loading-occured time-taken]}
          (apply bench-one payload (-> options seq flatten))
          failure                          (or gc-occurred compilation-occurred class-loading-occured)
          new-gc-failure-count             (cond-> gc-failure-count gc-occurred inc)
          new-compilation-failure-count    (cond-> compilation-failure-count compilation-occurred inc)
          new-class-loading-failure-count  (cond-> class-loading-failure-count class-loading-occured inc)
          new-iters                        (cond-> iters (not failure) inc)
          new-times                        (cond-> times (not failure) (conj time-taken))]

      (when verbose
        (println "Run complete " time-taken "ms. GC occurred " gc-occurred
                 ". Compilation occurred " compilation-occurred
                 ". Class loading occurred " class-loading-occured "."))

      (if (or (>= new-gc-failure-count max-gc-failures)
              (>= new-compilation-failure-count max-compilation-failures)
              (>= new-class-loading-failure-count max-class-loading-failures)
              (> new-iters num-trials))
        {:data                    times
         :gc-failures             new-gc-failure-count
         :compilation-failures    new-compilation-failure-count
         :class-loading-failures  new-class-loading-failure-count}
        (recur new-gc-failure-count new-compilation-failure-count
               new-class-loading-failure-count new-iters new-times)))))


(defn- warm-up!
  "Does warm-up-executions of benchmarked code and runs for at least warm-up-time seconds.
   Need to run for at least 10secs to ensure that the JVM gets the message and compiles code,
   rather than just interpret it."
  [payload verbose]

  (when verbose
    (println "Warming up JVM..."))

  (let [start-time        (System/nanoTime)
        [iterations time] (loop [i 0]
                            (payload)
                            (let [end-time (System/nanoTime)
                                  execution-time (/ (- end-time start-time) 1000000000.0)]
                              (if (and (>= i warm-up-executions)
                                       (>= execution-time warm-up-time))
                                  [i execution-time]
                                  (recur (inc i)))))]
  (when verbose
    (println "JVM warmed up. Iterations " iterations ". Time taken " time "."))))


(defn- bench-one-set [payload & {:keys [verbose num-trials]
                                 :or {num-trials default-num-trials}
                                 :as options}]
  (let [{:keys [data gc-failures compilation-failures class-loading-failures]}
        (apply iterate-bench payload (-> options (merge {:num-trials num-trials}) seq flatten))]

    ;; Do final GC and measure time - to ensure there is not a big GC mess
    (when verbose
      (println "Starting final GC..."))

    (let [start-time (System/nanoTime)]
      (force-gc)
      (let [end-time (System/nanoTime)]

        (when verbose
          (println "Final GC complete"))

        (when verbose
          (pprint/pprint data))

        {:trials                  (count data)
         :gc-failures             gc-failures
         :compilation-failures    compilation-failures
         :class-loading-failures  class-loading-failures
         ;:mean                    (double (mean data))
         :median                  (median data)
         :std                     (sample-standard-deviation data)
         :final-gc-time           (/ (- end-time start-time) 1000000000.0)}))))


(defn bench*
  "Run multiple sets of trial runs and take the set with the lowest
   standard deviation"
  [payload & {:keys [verbose num-sets] :or {num-sets default-num-sets} :as options}]
  (warn-gc-options!)
  (warm-up! payload verbose)

  (->> (for [i (range num-sets)]
         (do
           (when verbose
             (println "Calculating set " i))
           (apply bench-one-set payload (-> options seq flatten))))
       (sort-by :std)
       (first)))

(defmacro bench [payload & options]
  `(bench* (fn [] ~payload) ~@options))
