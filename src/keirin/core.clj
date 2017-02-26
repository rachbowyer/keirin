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
(def ^:private min-execution-time-threshold 0.75)  ; %age of the min execution time that must be achieved


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


(defn execute-many [f n]
  (let [start  (System/nanoTime)]
    (loop [i (long n)]
      (when (pos? i)
        (set-result! timed-run-result (f))
        (recur (unchecked-dec i))))

    (let [end (System/nanoTime)]
      {:time-taken (/ (- end start) 1000000.0 n)
       :result-hash-code (get-hash-code timed-run-result)})))


(defn- bench-one [payload gc-file-name n & {:keys [no-force-gc verbose]}]
  (when verbose
    (println "Requesting GC..."))
  (when-not no-force-gc
    (force-gc))
  (Thread/sleep 300) ;; Let any GC finish
  (when verbose
    (println "GC complete"))

  (let [compilation-bean            (ManagementFactory/getCompilationMXBean)
        compilation-time-supported  (.isCompilationTimeMonitoringSupported compilation-bean)
        class-loading-bean          (ManagementFactory/getClassLoadingMXBean)
        class-loaded-before         (.getTotalLoadedClassCount class-loading-bean)
        class-unloaded-before       (.getUnloadedClassCount class-loading-bean)
        compilation-time-before     (and compilation-time-supported (.getTotalCompilationTime compilation-bean))
        gc-file-before              (some-> gc-file-name read-gc-file)
        {:keys [result-hash-code time-taken]}
        (if (= n 1) (execute-once payload) (execute-many payload n))
        compilation-time-after      (and compilation-time-supported (.getTotalCompilationTime compilation-bean))
        class-loaded-after          (.getTotalLoadedClassCount class-loading-bean)
        class-unloaded-after        (.getUnloadedClassCount class-loading-bean)
        _                           (Thread/sleep 300) ;; Let GC file catch up
        gc-file-end                 (some-> gc-file-name read-gc-file)]


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
                                  execution-time (/ (- end-time start-time) 1e9)]
                              (if (and (>= i warm-up-executions)
                                       (>= execution-time warm-up-time))
                                  [i execution-time]
                                  (recur (inc i)))))]
  (when verbose
    (println "JVM warmed up. Iterations " iterations ". Time taken " time "."))))


(defn- bench-one-set [payload gc-file-name n & {:keys [verbose num-timed-runs min-execution-time no-force-gc]
                                                :or {num-timed-runs default-num-timed-runs}
                                                :as options}]

  (let [{:keys [data gc-failures compilation-failures class-loading-failures]}
        (apply iterate-bench payload gc-file-name n (-> options (merge {:num-timed-runs num-timed-runs}) seq flatten))]

    ;; Do final GC and measure time - to ensure there is not a big GC mess
    (when verbose
      (println "Starting final GC..."))

    (let [start-time (System/nanoTime)]
      (when-not no-force-gc
        (force-gc))
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
        (let [times           (map :time-taken data)
              result          {:num-executions         n
                               :timed-runs             (count data)
                               :gc-failures            gc-failures
                               :compilation-failures   compilation-failures
                               :class-loading-failures class-loading-failures
                               :mean                   (double (mean times))
                               :median                 (median times)
                               :std                    (sample-standard-deviation times)
                               :MAD                    (MAD times)
                               :final-gc-time          (/ (- end-time start-time) 1000000000.0)
                               :data data}
              execution-time  (* (apply min times) n)]

          (when (and min-execution-time (< execution-time (* min-execution-time min-execution-time-threshold)))
            (throw (Exception. (str "Did not run payload function for long enough to get accurate results. "
                                    "Ran payload function for only " execution-time "ms, when the minimum "
                                    "required execution time is " min-execution-time "ms."))))
          result)))))


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


(defn- display-humane [{:keys [timing-overhead median MAD]:as _results}]
  (println "Time taken (median)" (humane-time median))
  (println "Mean absolute deviation (MADS) " (humane-time MAD))
  (when timing-overhead
    (let [as-percentage (* 100 (/ timing-overhead median))]
     (println "Timing loop overhead "
              (str (format "%3.2f" as-percentage) "% (" (humane-time timing-overhead) ")")))))


(defn- report-results [results reporting]
  (condp = reporting
    :display-humane     (display-humane results)
    :underlying-results results
    results))


(defn- estimate-num-executions
  "Estimate the number of times to execute the payload function in order to achive the required
   minimum execution time.

   Timing an empty block with two successive calls to System/nanoTime on a Macbook Pro can take
   as long as 300 nanos in the REPL and 150 nanos in compiled code. This introduces a considerable
   error for a function that only takes 10 nanos to run, but the error reduces as the number of excecutions
   increases.

   However, on the otherhand slower functions may allocate a considerable amount of memory. If they are run too
   often then GCs may occur in the timing run invalidating.

   The strategy is to start with an intial estimate. If the minium required execution time is not reached, then re
   -estimate based on the time executing this one function. The additional executions will reduce the timing error
   and likely lead to the payload function appearing to speed up. Therefore the new estimate will probably also
   be too low but more accurate. Eventually it will stabalize giving an accurate estimate.

   Critical that we get fairly accurate results - one slow run could throw the execution count right
   off - and impact results. So use min of 3 for each time"

  [payload gc-file-name min-execution-time verbose]

  (loop [n 1]
    (when verbose
      (println "Trying " n " executions"))
    (let [{:keys [data gc-failures compilation-failures class-loading-failures] :as result}
          (iterate-bench payload gc-file-name n :num-timed-runs 3 :verbose verbose)
          _ (when-not (= 3 (count data))
              (throw (Exception. (str "Unable to get clean runs estimating number of executions. "
                                      "GC failures: " gc-failures ". "
                                      "Compilation failures: " compilation-failures "."
                                      "Class loading failures: " class-loading-failures ". "))))
          time-taken        (->> data (map :time-taken) (apply min))
          time-taken        (max time-taken 0e-6) ; In case we get back 0 for a fast function
          total-time-taken  (* time-taken n)]

      (if (> total-time-taken min-execution-time)
        n
        (let [new-n (inc (int (quot min-execution-time time-taken)))
              new-n (max new-n (inc n))] ; fail safe to ensure monotoncially increasing
          (recur new-n))))))

(defn- calculate-timing-overhead
  [n gc-file-name & {:keys [verbose] :as options}]
  (let [one   (Integer. 1)
        dummy (fn [] one)]

    (when verbose
      (println "Beginning to calculate timing loop overhead..."))

    (warm-up! dummy verbose)
    (force-gc)

    (let [new-options (-> options (merge {:min-execution-time nil :no-force-gc true}) seq flatten)
          results (apply bench-one-set dummy gc-file-name n new-options)
          overhead (:median results)]
      (when verbose
        (println "Timing loop overhead " (humane-time overhead)))
      overhead)))

(defn bench*
  [payload & {:keys [verbose min-execution-time reporting calc-timing-overhead]
              :or {min-execution-time default-min-execution-time reporting default-reporting}
              :as options}]

  (let [gc-file-name (get-gc-file-name)]
    (when-not gc-file-name
      (log/warn "JVM option: -Xloggc: is not set. Keirin is unable to detect "
                "if a GC occurs during a timed run"))

    (warm-up! payload verbose)

    ;; Calculate number of times to run the payload function
    (let [num-executions        (estimate-num-executions payload gc-file-name min-execution-time verbose)
          _                     (when verbose
                                  (println "Estimated number of executions " num-executions))
          new-options           (-> options (merge {:min-execution-time min-execution-time}) seq flatten)
          results               (apply bench-one-set payload gc-file-name num-executions new-options)

          timing-overhead       (when calc-timing-overhead
                                  (apply calculate-timing-overhead num-executions gc-file-name new-options))

          results-with-overhead (cond-> results timing-overhead (assoc :timing-overhead timing-overhead))]

      (report-results results-with-overhead reporting))))

(defmacro bench [payload & options]
  `(bench* (fn [] ~payload) ~@options))


(defn quick-bench* [payload & {:keys [num-timed-runs]
                               :or {num-timed-runs default-num-timed-runs-quick}
                               :as options}]
  (apply bench* payload (-> options (merge {:num-timed-runs num-timed-runs}) seq flatten)))

(defmacro quick-bench [payload & options]
  `(quick-bench* (fn [] ~payload) ~@options))


