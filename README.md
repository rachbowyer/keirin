# Keirin

![Picture of a Keirin race taking place in a banked velodrome. A motorcycle is at the front; behind it are 6 cyclists](https://github.com/rachbowyer/keirin/blob/master/ColwoodKeirin.jpg)

Proof of concept microbenchmarking library for Clojure. Microbenchmarking is tricky on the JVM platform as background tasks such as garabage collection can
distort benchmarks. 

Keirin takes a three pronged approach to the problem.

 1) Takes steps to avoid unpredicable events:
  * warming up the JIT to ensure code is compiled 
  * forcing a GC to reduce the chance of a GC during a test run

 2) Detecting background events (GC, code compilation, class loading and unloading) when they occur during a trial run and discarding this run.

 3) Using statistical techniques to minimize the impact of outliers:
   * Performing multiple sets of runs and choosing the run with the lowest sample standard deviation
   * Using the median rather than the mean.


## Building
To build Keirin, clone this repo, change the current directory to the directory containing project.clj and run:

     lein install

This creates an uberjar and installs it into the local maven.


## Installation
If leinigen is being used, add the following dependency to the project.clj

     [keirin "0.1.0-SNAPSHOT"]

Keirin works most optimally if garbage collections are logged to a file called "gc.out". This can be done by adding the following JVM option -Xloggc:gc.out.


## Usage
To make the Keirin functions available type:

     (require '[keirin.core :as k])


And to benchmark some code call **k/bench** e.g. 

    (k/bench (Thread/sleep 500))
    ;; => {:trials 10,
    ;;     :gc-failures 0, 
    ;;     :compilation-failures 0, 
    ;;     :class-loading-failures 0,
    ;;     :median 501.9638185,
    ;;     :std 1.1136650738771838}

 * :trials is the number of times that **Thread/sleep** was executed successfully in the set of runs that was chosen 
 * :gc-failures, :compilation-failures and :class-loading-failures are the number of times that a run failed due to GC, compilation, class loading/unloading respectively
 * :median is the median time that **Thread/sleep** ran taken across the trials in the chosen set in milliseconds
 * :std is the sample standard deviation of the time taken across the successful trials in the chosen set of runs

**k/bench** takes two optional arguments: :trials, the number of times that the benchmarked code is run and :verbose, which provides extended output. For example: 

    (k/bench (Thread/sleep 500) :num-trials 2 :verbose true)


## Benchmarking best practice

 1) Run on a machine with at least 4 cores, which does not have a GUI and is quiet e.g. an Amazon EC2 instance. This minimizes the chance of the OS stealing CPU from the benchmarking.
 
 2) Compile down to a jar and run the code as a jar, rather than run from the REPL as the REPL introduces noise.

 3) Configure the JVM correctly. For example the following options are useful:
   "-server" - enables server side JVM optimisations 
   "-Xbatch" - disable background compilation
   "-XX:CICompilerCount=1" - only 1 thread allowed to compile code
   "-XX:-TieredCompilation" - disables tierd compilation. Need to allow only 1 thread for the compiled code. 


## Rationale for Keirin
["Criterium"](https://github.com/hugoduncan/criterium) is considered the gold standard for microbenchmarking on Clojure. Criterium attempts to achieve a steady state (code not being compiled etc.) before taking sample times. Criterium then applies statistical analysis to determine the reliability of the results.

Keirin takes a different approach to the problem. Rather than achieve a steady state, Keirin focuses on minimizing noise, detecting it when it occurs, and reducing the impact of the noise on the results. 

The hope with Keirin is to provide more reliably results and faster results than Criterium in less than ideal environments - e.g. from a REPL on a low powered Macbook.


## Todo
 1) Compile with direct linking enabled
 2) Do a final GC and add the time of this to the results
 3) New runtime parameter - :num-of-sets 
 4) Experiment with the default of 6 sets of 10 runs.


## License for Keirin

Distributed under the Eclipse Public License either version 1.0.


## Image of a Keirin race
Courtesy of [FigBug] (https://en.wikipedia.org/wiki/User:FigBug) used under the terms of CC BY-SA 3.0.



