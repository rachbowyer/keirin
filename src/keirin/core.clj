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
            [clojure.pprint :as pprint])
  (:import (java.lang.management ManagementFactory)))


;;;
;;; Default parameters
;;;

(def ^:private warm-up-executions 10)
(def ^:private warm-up-time 10) ; seconds
(def ^:private gc-attempts 5)
(def ^:private default-num-timed-runs 30)
(def ^:private default-num-timed-runs-quick 7)
(def ^:private default-min-execution-time 300) ; millis
(def ^:private max-gc-failures 10)
(def ^:private max-compilation-failures 10)
(def ^:private max-class-loading-failures 5)
(def ^:private max-gc-attempts 100)
(def ^:private max-gc-attempts-memory 1000)

(def ^:private mem-used-iterations 20)
(def ^:private mem-used-gc-iterations 10)
(def ^:private mem-used-max-retry-count 1000)
(def ^:private mem-used-exec-estimates [1 4 16 64 256 1024])
(def ^:private mem-used-estimate-iterations 5)
(def ^:private mem-used-estimate-min 1)
(def ^:private mem-used-estimate-max 1000)

(def ^:private megabyte (* 1024.0 1024.0))

(defn chain-hashes
  "Combine hash codes"
  [h1 h2]
  (bit-xor (if h1 h1 0)  (if h2 h2 0)))


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
  (some-> data sample-variance Math/sqrt))


(defn- median [data]
  (let [sorted-data (sort data)
        len-data (count data)
        mid-point (quot len-data 2)]
    (if (odd? len-data)
      (nth sorted-data mid-point)
      (/ (+ (nth sorted-data (dec mid-point)) (nth sorted-data mid-point)) 2.0))))


;;;
;;; Bench marking utilities - speed
;;;


; For unit test
;(parse-gc-file-name "-Xloggc:gc.out")
;=> "gc.out"

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

(defprotocol TimedRunResultAccessor
  (set-result [_ v] )
  (get-hash-code [_]))

(deftype TimedRunResultType [^{:unsynchronized-mutable true :tag Object} v]
  TimedRunResultAccessor
  (set-result [_ value] (set! v value))
  (get-hash-code [_] (some-> v .hashCode)))

(def timed-run-result (TimedRunResultType. nil))


(defn- execute-once [f]
  (let [start (System/nanoTime)
        result (f)]

    (let [end (System/nanoTime)]
      {:time-taken (/ (- end start) 1000000.0)
       :result-hash-code (some-> result .hashCode)})))


(defn- execute-many [f n]
  (let [start  (System/nanoTime)]

    (loop [i 0]
      (when (< i n)
        (set-result timed-run-result (f))
        (recur (unchecked-inc i))))

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
           ;:mean                    (double (mean data))
           :median                 (median times)
           :std                    (sample-standard-deviation times)
           :final-gc-time          (/ (- end-time start-time) 1000000000.0)
           :data data})))))


(defn bench*
  [payload & {:keys [verbose min-execution-time]
              :or {min-execution-time default-min-execution-time}
              :as options}]

  (let [gc-file-name (get-gc-file-name)]
    (when-not gc-file-name
      (println "WARNING. JVM option: -Xloggc: is not set. Keirin is unable to detect "
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

        (apply bench-one-set payload gc-file-name num-executions (-> options seq flatten))))))

(defmacro bench [payload & options]
  `(bench* (fn [] ~payload) ~@options))


(defn quick-bench* [payload & {:keys [num-timed-runs]
                               :or {num-timed-runs default-num-timed-runs-quick}
                               :as options}]
  (apply bench* payload (-> options (merge {:num-timed-runs num-timed-runs}) seq flatten)))

(defmacro quick-bench [payload & options]
  `(quick-bench* (fn [] ~payload) ~@options))



;;;
;;; Bench marking utilities - memory
;;; Just a proof of concept at the moment. Using to verify functions that should be linear in
;;; memory usage are linear. Resulting graphs are not considered reliable enough to be published
;;;

(defn- warm-up-heap [f]
  (f))

(defn memory-used-many [f n]
  (loop [retry-count 0]
    (let [results (object-array n)
          _ (dotimes [_ (min n mem-used-gc-iterations)]
              (force-gc max-gc-attempts-memory))
          before (heap-used)]

      (dotimes [i n]
        (aset results i (f)))

      (dotimes [_ (min n mem-used-gc-iterations)]
        (force-gc max-gc-attempts-memory))

      (let [after (heap-used)
            mem-used (double (/ (- after before) n))]

        (if (or (>= mem-used 0.0)
                (> retry-count mem-used-max-retry-count))
          {:memory-used-bytes mem-used
           :result-hash-code  (some-> results .hashCode)}
          (recur (inc retry-count)))))))


(defn estimate-num-executions-needed [f]
  (loop [hash-codes  0
         estimates        mem-used-exec-estimates]
    (let [[estimate & other-estimates] estimates
          results         (->> (range 0 mem-used-estimate-iterations)
                               (map (fn [_] (memory-used-many f 1))))
          memory          (map :memory-used-bytes results)
          all-hashes      (conj (map ::result-hash-code results) hash-codes)
          new-hash-codes  (reduce chain-hashes all-hashes)
          median          (median memory)
          sstd            (sample-standard-deviation memory)
          relative-sstd   (when (> median) (/ sstd (Math/abs median)))]

      (println "estimate " estimate)
      (println "results " results)
      (println "memory " memory)
      (println "median " median)
      (println "sstd " sstd)
      (println "relative-sstd " relative-sstd)

      (if (or (empty? other-estimates)
              (and relative-sstd (< relative-sstd 0.1)))
        {:estimate (int (min (max (/ (* megabyte) median) mem-used-estimate-min)
                             mem-used-estimate-max))
         :result-hash-code new-hash-codes }
        (recur new-hash-codes other-estimates)))))



(defn memory-used* [f]
  (let [warmup                                (warm-up-heap f)
        {num-executions :estimate
         exs-hash-code  :result-hash-code}    (estimate-num-executions-needed f)
        results                               (->> (range 0 mem-used-iterations)
                                                   (map (fn [_] (memory-used-many f 1))))
        m                                     (median (map :memory-used-bytes results))
        warm-up-hashcode                      (some-> warmup .hashCode)
        all-hash-codes                        (conj (map :result-hash-code results) exs-hash-code warm-up-hashcode)
        final-hash-code                       (reduce chain-hashes all-hash-codes)]
    {:median-bytes        m
     :median-megabytes    (/ m megabyte)
     :data results
     :final-hash-code final-hash-code}))

(defmacro memory-used
  [payload]
  `(memory-used* (fn [] ~payload)))




