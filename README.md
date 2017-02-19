# Keirin

![Picture of a Keirin race taking place in a banked velodrome. A motorcycle is at the front; behind it are 6 cyclists](https://github.com/rachbowyer/keirin/blob/master/ColwoodKeirin.jpg)

Microbenchmarking library for Clojure. Microbenchmarking is tricky on the JVM platform as background tasks such as garbage collection can
distort benchmarks. 

Keirin takes a four pronged approach to the problem.

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
    
        
**k/bench** takes four optional arguments: 
 * :reporting. Defaults to :display-humane which shows the execution time and the mean absolute deviation. Alternatively :reporting can be :underlying-results, which
   returns more detailed information as a Clojure hashmap. This can be useful if the results need to be processed by Clojure code e.g. to generate a graph
 * :timed-runs is the number of timed runs required. Defaults to 30.
 * :minimum-execution-time is the minimum time in milliseconds for which the benchmarked function will be run. Defaults to 300ms. If there are problems with garbage collection then this parameter should be reduced. 
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


## Execution overhead

To minimise volatility of results, the function under test is run repeatedly for at least 300ms and the total duration 
of the run is timed. This means unfortunately that there is some bias added to the results as parts of the timing loop 
are timed as well. Keirin does not attempt to compensate for this as the bias is in nano seconds on typical hardware.
If bias is a concern, then the overhead of the timing loop can be measured by running:

    (bench 1)
    ;; Time taken (median) 2.84 ns
    ;; Mean absolute deviation (MADS)  0.76 ns


## Benchmarking best practice

 1) Run on a machine with multiple cores, ample memory, which does not have a GUI and is quiet. This minimizes the chance of the OS or JVM stealing CPU from the benchmarking.
    For example an unused server class machine. Machines in the Cloud are not considered reliable for benchmarking purposes
   
 2) Compile down to a jar and run the code as a jar, rather than run from the REPL as the REPL introduces noise.

 3) Configure the JVM correctly. For example the following options are useful:
  * "-server" - enables server side JVM optimisations 
  * "-Xbatch" - disable background compilation
  * " -Xloggc:gc.out" - logs GC to a file where Keirin can read them
  * "-XX:CICompilerCount=1" - only 1 thread allowed to compile code
  * "-XX:-TieredCompilation" - disables tiered compilation. Need to allow only 1 thread for the compiled code. 
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



