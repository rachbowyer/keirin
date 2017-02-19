;; Copyright ©️ Rachel Bowyer 2016, 2017. All rights reserved.
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
;; by Brent Boyer, the Criterium bench marking library for Clojure (see above) and
;; http://www.javaworld.com/article/2077496/testing-debugging/java-tip-130--do-you-know-your-data-size-.html


(ns keirin.core
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log])
  (:import (java.lang.management ManagementFactory)))


;;;
;;; Default parameters
;;;

(def ^:private warm-up-executions 10)
(def ^:private warm-up-time 10) ; seconds
(def ^:private default-num-timed-runs 30)
(def ^:private default-num-timed-runs-quick 7)
(def ^:private default-min-execution-time 300) ; millis
(def ^:private max-gc-failures 10)
(def ^:private max-compilation-failures 10)
(def ^:private max-class-loading-failures 5)
(def ^:private max-gc-attempts 100)
(def ^:private default-reporting :display-humane)  ; other option is :underlying-results



;;;
;;; Hash utilities
;;;


(defn- chain-hashes
  "Combine hash codes
   This is designed to be a simple combination of the hashcodes to stop
   the compiler discarding the results of computations. It is not intended
   to be a robust method of combining hash codes."
  [h1 h2]
  (bit-xor (if h1 h1 0)  (if h2 h2 0)))


;;;
;;; Memory utilities
;;;


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


;;;
;;; Stats functions
;;;

(defn- sq [x]
  (* x x))

(defn abs
  ^Double [^Double x]
  (double (Math/abs x)))

(defn- mean
  ^Double [data]
  {:pre [(seq data)]}
  (double (/ (reduce + data) (count data))))

(defn- sample-variance
  "Find the unbiased sample variance"
  ^Double [data]
  {:pre [(seq data)]}
  (let [mean (double (mean data))
        num (count data)]
    (if (> num 1)
      (double (/ (transduce (map #(sq (- % mean))) + data)
                 (dec num)))
      0.0)))

(defn- sample-standard-deviation
  "Sample standard deviation - based on the unbiased sample variance
   although apparantly ssd is still has some bias"
  [data]
  {:pre [(seq data)]}
  (some-> data sample-variance Math/sqrt))

(defn- median
  "Finds the median of a collection of numbers"
  ^Double [data]
  {:pre [(seq data)]}
  (let [sorted-data (sort data)
        len-data (count data)
        mid-point (quot len-data 2)]
    (if (odd? len-data)
      (double (nth sorted-data mid-point))
      (/ (+ (nth sorted-data (dec mid-point)) (nth sorted-data mid-point)) 2.0))))

(defn- MAD
  "Finds the median absolute deviation"
  ^Double [data]
  {:pre [(seq data)]}
  (let [central-point (median data)]
    (median (map (fn [e] (abs (- e central-point)))
                 data))))


;;;
;;; Bench marking utilities - speed
;;;

(defn- parse-gc-file-name [jvm-arg]
  (let [[_ gc-file] (re-find #"^-Xloggc:(.*?)$" jvm-arg)]
    gc-file))

(defn- get-gc-file-name []
  (let [jvm-options (.getInputArguments (ManagementFactory/getRuntimeMXBean))]
        (some parse-gc-file-name jvm-options)))

(defn- read-gc-file [gc-file-name]
  (if (.exists (io/as-file gc-file-name))
    (slurp gc-file-name)
    ""))



;;;
;;; We need an ultra fast place to store the result of running the "payload" function
;;; as storing it is included in the timing loop
;;;

(defprotocol TimedRunResultAccessor
  (set-result! [_ v] )
  (get-hash-code [_]))

(deftype TimedRunResultType [^{:unsynchronized-mutable true :tag Object} v]
  TimedRunResultAccessor
  (set-result! [_ value] (set! v value))
  (get-hash-code [_] (some-> v .hashCode)))

(def timed-run-result (TimedRunResultType. nil))



(defn- execute-once [f]
  (let [start (System/nanoTime)
        ^Object result (f)]

    (let [end (System/nanoTime)]
      {:time-taken (/ (- end start) 1000000.0)
       :result-hash-code (some-> result .hashCode)})))


(defn- execute-many [f n]
  (let [start  (System/nanoTime)]
    (loop [i (long n)]
      (when (pos? i)
        (set-result! timed-run-result (f))
        (recur (unchecked-dec i))))

    (let [end (System/nanoTime)]
      {:time-taken (/ (- end start) 1000000.0 n)
       :result-hash-code (get-hash-code timed-run-result)})))


(defn- bench-one [payload gc-file-name n & {:keys [verbose]}]
  (when verbose
    (println "Requesting GC..."))
  (force-gc)
  (Thread/sleep 300) ;; Let any GC finish
  (when verbose
    (println "GC complete"))

  (let [compilation-bean            (ManagementFactory/getCompilationMXBean)
        compilation-time-supported  (.isCompilationTimeMonitoringSupported compilation-bean)
        class-loading-bean          (ManagementFactory/getClassLoadingMXBean)
        class-loaded-before         (.getTotalLoadedClassCount class-loading-bean)
        class-unloaded-before       (.getUnloadedClassCount class-loading-bean)
        compilation-time-before     (and compilation-time-supported (.getTotalCompilationTime compilation-bean))
        gc-file-before              (read-gc-file gc-file-name)
        {:keys [result-hash-code time-taken]}
        (if (= n 1) (execute-once payload) (execute-many payload n))
        compilation-time-after      (and compilation-time-supported (.getTotalCompilationTime compilation-bean))
        class-loaded-after          (.getTotalLoadedClassCount class-loading-bean)
        class-unloaded-after        (.getUnloadedClassCount class-loading-bean)
        _                           (Thread/sleep 300) ;; Let GC file catch up
        gc-file-end                 (read-gc-file gc-file-name)]


      {:gc-occurred (not= gc-file-before gc-file-end)
       :compilation-occurred (not= compilation-time-before compilation-time-after)
       :class-loading-occured (or (not= class-loaded-before class-loaded-after)
                                  (not= class-unloaded-before class-unloaded-after))
       :time-taken time-taken
       :result-hash-code result-hash-code}))


(defn- iterate-bench [payload gc-file-name n & {:keys [verbose num-timed-runs] :as options}]
  (loop [gc-failure-count             0
         compilation-failure-count    0
         class-loading-failure-count  0
         iters                        0
         times                        []]

    (when verbose
      (println "Starting timed run..."))
    (let [{:keys [gc-occurred compilation-occurred class-loading-occured time-taken result-hash-code]}
          (apply bench-one payload gc-file-name n (-> options seq flatten))
          failure                          (or gc-occurred compilation-occurred class-loading-occured)
          new-gc-failure-count             (cond-> gc-failure-count gc-occurred inc)
          new-compilation-failure-count    (cond-> compilation-failure-count compilation-occurred inc)
          new-class-loading-failure-count  (cond-> class-loading-failure-count class-loading-occured inc)
          new-iters                        (cond-> iters (not failure) inc)
          new-times                        (cond-> times (not failure) (conj {:time-taken       time-taken
                                                                              :result-hash-code result-hash-code}))]

      (when verbose
        (println "Run complete " time-taken "ms. GC occurred " gc-occurred
                 ". Compilation occurred " compilation-occurred
                 ". Class loading occurred " class-loading-occured
                 ". Result hash code " result-hash-code ". "))

      (if (or (>= new-gc-failure-count max-gc-failures)
              (>= new-compilation-failure-count max-compilation-failures)
              (>= new-class-loading-failure-count max-class-loading-failures)
              (>= new-iters num-timed-runs))
        {:data                    new-times
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


(defn- bench-one-set [payload gc-file-name n & {:keys [verbose num-timed-runs]
                                                :or {num-timed-runs default-num-timed-runs}
                                                :as options}]

  (let [{:keys [data gc-failures compilation-failures class-loading-failures]}
        (apply iterate-bench payload gc-file-name n (-> options (merge {:num-timed-runs num-timed-runs}) seq flatten))]

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

        (when (or (empty? data) (not= (count data) num-timed-runs))
          (throw (Exception. (str "Unable to complete " num-timed-runs " timed runs. "
                                  "GC failures " gc-failures ". "
                                  "Compilation failures " compilation-failures ". "
                                  "Class loading failures " class-loading-failures ". "))))
        (let [times (map :time-taken data)]
          {:timed-runs             (count data)
           :gc-failures            gc-failures
           :compilation-failures   compilation-failures
           :class-loading-failures class-loading-failures
           :mean                   (double (mean times))
           :median                 (median times)
           :std                    (sample-standard-deviation times)
           :MAD                    (MAD times)
           :final-gc-time          (/ (- end-time start-time) 1000000000.0)
           :data data})))))


(defn- humane-time
  "t is time in milliseconds"
  [t]
  (let [time-seconds (/ t 1000.0)]
    (cond
      (and (< time-seconds 1) (>= time-seconds 0.001))
      (format "%.2f ms" (* time-seconds 1000.0))

      (and (< time-seconds 0.001) (>= time-seconds 0.000001))
      (format "%.2f µs" (* time-seconds 1000000.0))

      (and (< time-seconds 0.000001))
      (format "%.2f ns" (* time-seconds 1000000000.0))

      :else (format "%.2f seconds" time-seconds))))


(defn- display-humane [{:keys [median MAD]:as _results}]
  (println "Time taken (median)" (humane-time median))
  (println "Mean absolute deviation (MADS) " (humane-time MAD)))


(defn- report-results [results reporting]
  (condp = reporting
    :display-humane   (display-humane results)
    :underlying-results results
    results))



(defn bench*
  [payload & {:keys [verbose min-execution-time reporting]
              :or {min-execution-time default-min-execution-time reporting default-reporting}
              :as options}]

  (let [gc-file-name (get-gc-file-name)]
    (when-not gc-file-name
      (log/warn "JVM option: -Xloggc: is not set. Keirin is unable to detect "
                "if a GC occurs during a timed run"))

    (warm-up! payload verbose)

    ;; Calculate number of times to run the payload function
    (let [{:keys [data] :as result} (iterate-bench payload gc-file-name 1 :num-timed-runs 3 :verbose verbose)
          time-taken                (-> data first :time-taken)]

      (when-not time-taken
        (throw (ex-info "Unable to get a clean run" result)))

      (let [time-taken (if (> time-taken 0) time-taken 1)
            num-executions (if (> time-taken min-execution-time)
                             1
                             (inc (quot min-execution-time time-taken)))]
        (when verbose
          (println "Timed run of function took " time-taken " millis")
          (println "Executing function " num-executions))

        (report-results (apply bench-one-set payload gc-file-name num-executions (-> options seq flatten))
                        reporting)))))

(defmacro bench [payload & options]
  `(bench* (fn [] ~payload) ~@options))


(defn quick-bench* [payload & {:keys [num-timed-runs]
                               :or {num-timed-runs default-num-timed-runs-quick}
                               :as options}]
  (apply bench* payload (-> options (merge {:num-timed-runs num-timed-runs}) seq flatten)))

(defmacro quick-bench [payload & options]
  `(quick-bench* (fn [] ~payload) ~@options))


