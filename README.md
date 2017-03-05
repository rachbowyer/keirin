# Keirin

![Picture of a Keirin race taking place in a banked velodrome. A motorcycle is at the front; behind it are 6 cyclists](https://github.com/rachbowyer/keirin/blob/master/ColwoodKeirin.jpg)

Keirin is a microbenchmarking library for Clojure. Microbenchmarking is tricky on the JVM platform as background tasks such as garbage collection can
distort benchmarks. Keirin takes a five pronged approach to the problem.

1) Takes steps to avoid unpredictable events:
 * warming up the JIT to ensure code is compiled 
 * forcing a GC to reduce the chance of a GC during a test run

2) Detecting background events (GC, code compilation, class loading and unloading) when they occur during a trial run and discarding this run.

3) Reducing the impact of timing inaccuracies: 
 * On each timed run, the benchmarked function is run repeatedly for a minimum time period and the mean time calculated. This reduces the inaccuracies in trying to time a function that runs for a very short duration.

4) Using statistical techniques to minimize the impact of outliers:
 * Multiple timed runs are taken and the median of these (rather than the mean) is used for the final result. The median is more robust than the mean in the event of outliers.
 * MADS (["Median absolute deviation"](https://en.wikipedia.org/wiki/Median_absolute_deviation)) is provided as a measure of the variance, rather than the
 sample standard deviation. Again MADS is more robust against outliers than the sample standard deviation.

5) If required, estimating the timing overhead unavoidably included in the times for the benchmarked function.


## Installation
Keirin is available from Clojars.

[![Clojars Project](https://img.shields.io/clojars/v/keirin.svg)](https://clojars.org/keirin)

Keirin works most optimally if garbage collections are logged to a file. This can be done by adding the following JVM option -Xloggc:gc.out.


## Usage
To make the Keirin functions available type:

     (require '[keirin.core :as k])


And to benchmark some code call **k/bench** e.g. 

    (k/bench (Thread/sleep 100))
    ;; Time taken (median) 102.60 ms
    ;; Mean absolute deviation (MADS)  533.19 µs
    
        
**k/bench** takes five optional arguments: 
 * :calc-timing-overhead calculates the overhead due to the timing loop. See the section *Execution overhead* below for more details.
 * :minimum-execution-time is the minimum time in milliseconds for which the benchmarked function will be run. Defaults to 300ms. If there are problems with garbage collection then this parameter should be reduced.
 * :reporting. Defaults to :display-humane which shows the execution time and the mean absolute deviation. Alternatively :reporting can be :underlying-results, which returns more detailed information as a Clojure hashmap. This can be useful if the results need to be processed by Clojure code e.g. to generate a graph
 * :timed-runs is the number of timed runs required. Defaults to 30.
 * :verbose a boolean, which provides extended output. 

For example: 

    (k/bench (Thread/sleep 500) :num-timed-runs 5 :minimum-execution-time 1000 :reporting :underlying-results :verbose true)
    
    
If :reporting is set to :underlying-results then the following output is generated:

    (k/bench (Thread/sleep 100) :reporting :underlying-results)
    ;; {:timed-runs 30,
    ;;  :gc-failures 0,
    ;;  :compilation-failures 0,
    ;;  :class-loading-failures 0,
    ;;  :mean 102.70095080000002
    ;;  :median 102.37582549999999,
    ;;  :MAD 0.3478315000000052,
    ;;  :std 1.0023317249709214,
    ;;  :final-gc-time 0.108144995,
    ;;  :data [{:time-taken 104.54769399999999, :result-hash-code nil}
    ;;         {:time-taken 101.031879, :result-hash-code nil}
    ;;          ...]}
 
 * :timed-runs is the number of timed runs
 * :gc-failures, :compilation-failures and :class-loading-failures are the number of times that a run failed due to GC, compilation, class loading/unloading respectively
 * :mean is the mean time that **Thread/sleep** ran taken across the trials in the chosen set in milliseconds
 * :median is the median time that **Thread/sleep** ran taken across the trials in the chosen set in milliseconds
 * :MAD median absolute deviation of the time taken across the successful trials in the chosen set of runs
 * :std is the sample standard deviation of the time taken across the successful trials in the chosen set of runs
 * :final-gc-time is the time in milliseconds of a GC run at the end of the set of runs. 
 * :data contains the time taken by each timed run and the hash code of the result of the function


The **k/quick-bench** macro is also provided. This behaves in the same way as the **bench** macro, but only performs 7 rather than 30 timed runs.  

    (k/quick-bench (Thread/sleep 100))
    ;; Time taken (median) 102.85 ms
    ;; Mean absolute deviation (MADS)  426.87 µs


See https://github.com/rachbowyer/keirin-demo for an extended demo using Keirin and the Analemma library to graph the performance
of **vec** as the number of elements in the vector increases.


## Execution overhead

To minimise the volatility of results, and also to increase accuracy for very fast functions, the function under test is run repeatedly for at least 300ms and the total duration of the run is timed. This means unfortunately that there is some overhead added to the results as parts of the timing loop 
are timed as well. 

Also on most (if not all computers) System/nanoTime, which Keirin uses to time the function is not nano second precise and also introduces its own overhead. 

For example:

    (bench (System/nanoTime))
    ;; Time taken (median) 38.38 ns
    ;; Mean absolute deviation (MADS)  0.57 ns

and

     (let [timings (doall (for [_ (range 1000)]
                       (let [start (System/nanoTime)
                             end   (System/nanoTime)]
                             (- end start))))]
  
       (println "Range " (apply min timings) " to " (apply max timings) " nanos"))
     ;; Range  50  to  374  nanos

The overhead included in the number that Keirin calculates depends on the number of times the function under test is executed within a timed run and therefore differs for different functions and also for different values for :minimum-execution-time.

In my tests (YMMV) the amount of the overhead varies from 2 ns to 2 µs with the overhead increasing with slower functions. The maximum overhead I have seen is around 15% for functions that are running in around 25 nanos, dropping to less than 0.001% as the function execution time increases.

Keirin does not attempt to automatically compensate for this overhead, but if the execution overhead is a concern, pass in the flag :calc-timing-overhead and Keirin will provide an estimate of the overhead.


    (bench (vector-of :int 1 2 3 4) :calc-timing-overhead true)
    ;; Time taken (median) 25.90 ns
    ;; Mean absolute deviation (MADS)  0.62 ns
    ;; Timing loop overhead  14.17% (3.67 ns)
    
    (bench (Thread/sleep 100) :calc-timing-overhead true)
    ;; Time taken (median) 103.45 ms
    ;; Mean absolute deviation (MADS)  167.87 µs
    ;; Timing loop overhead  0.00% (1.96 µs)


## Benchmarking best practice

 1) Run on a machine with multiple cores, ample memory, which does not have a GUI and is quiet. This minimizes the chance of the OS or JVM stealing CPU from the benchmarking. For example use an unused server class machine. Machines in the Cloud are not considered reliable for benchmarking purposes.
   
 2) Compile down to a jar and run the code as a jar, rather than run from the REPL as the REPL introduces noise.

 3) Configure the JVM correctly. For example the following options are useful:
  * "-server" - enables server side JVM optimisations 
  * "-Xbatch" - disable background compilation
  * "-Xloggc:gc.out" - logs GC to a file where Keirin can read them
  * "-XX:-TieredCompilation" - disables tiered compilation.
  * "-Xmx" - sets the maximum heap size. Set as large as possible for your machine as this reduces the chance of garabage collections.
  * "-Xms" - sets the minimum heap size. Set to the same value as Xmx as this reduces the change of garbage collections.


## Rationale for Keirin
["Criterium"](https://github.com/hugoduncan/criterium) is considered the gold standard for microbenchmarking on Clojure. Criterium achieves a steady state (code not being compiled etc.) before taking sample times. Criterium then applies statistical analysis to determine the reliability of the results.

Keirin takes a different approach to the problem. Rather than achieve a steady state, Keirin focuses on minimizing noise, detecting it when it occurs, and reducing the impact of the noise on the results. 

The hope with Keirin is to provide more reliable results than Criterium, in particular when there is noise from garbage collections.


## License for Keirin

Distributed under the Eclipse Public License either version 1.0.


## Image of a Keirin race
Courtesy of [FigBug] (https://en.wikipedia.org/wiki/User:FigBug) used under the terms of CC BY-SA 3.0.



