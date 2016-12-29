# Keirin

Proof of concept microbenchmarking library for Clojure.


## Building
To build Keirin, clone this repo, change the current directory to the directory containing project.clj and run:

     lein install

This creates an uberjar and installs it into the local maven.


## Installation
If leinigen is being used, add the following dependency to the project.clj

     [keirin "0.1.0-SNAPSHOT"]

Keirin also requires that garbage collections are logged to a file called "gc.out". In a leinigen project this can be achieved by adding

    :jvm-opts ^:replace ["-Xloggc:gc.out"]

It is also recommended that the JVM is run in "server" mode to ensure that JVM optimizations are applied:

    :jvm-opts ^:replace ["-server" "-Xloggc:gc.out"]


## Usage
To make the Keirin functions available type:

     (require '[keirin.core :as k])


And to benchmark some code call **k/bench** e.g. 

    (k/bench (Thread/sleep 500))
    ;; => {:trials 20, :failed-trials 0, 
           :mean 502.47546124999997, :std 1.5610682393241542}

 * Trials is the number of times that **Thread/sleep** was executed successfully 
 * Failed trials is the number of times that **Thread/sleep** was executed and a garbage collection occurred
 * Mean is the mean time that **Thread/sleep** ran taken across the successful trials in milliseconds
 * Std is the sample standard deviation of the time taken across the successful trials in milliseconds

**k/bench** takes two optional arguments: :trials, the number of times that the benchmarked code is run and :verbose, which provides extended output. For example: 

    (k/bench (Thread/sleep 500) :num-trials 2 :verbose true)


## How it works
Firstly, the benchmarked code is run multiple times to warm up the Hotspot compiler. There are then multiple timed runs of the benchmarked code. Prior to each timed run, Keirin tries to force a GC. And if a GC occurs during the timed section of the run, then this run is not included in the output statistics. Keirin detects if a GC has occurred in the timed section of a run by monitoring the "gc.out" log file.


## Rationale
The gold standard for microbenchmarking in Clojure is ["Criterium"](https://github.com/hugoduncan/criterium). However, I ran into two problems with it during benchmarking for the book I am contributing to, ["The Clojure Standard Library"](https://www.manning.com/books/clojure-standard-library): Criterium can be slow and the results can be impacted by garbage collection.

For example, benchmarking the following code with Criterium:

     (require '[criterium.code :as c])
     (let [data (doall (range 1000000))]
        (c/bench (vec data)))

takes over 10 minutes and produces the following output:

             Execution time mean : 127.417564 ms
    Execution time std-deviation : 238.954458 ms

The sample standard deviation is so high that it is not safe to rely upon the result.

Keirin was designed so that its results are not impacted by the garbage collector, hopefully resulting in more accurate numbers. Running the same example in Keirin:

     (require '[keirin.core :as k])
     (let [data (doall (range 1000000))]
       (k/bench (vec data)))

produces the following output in 40 seconds:

     {:trials 20, 
      :failed-trials 0, 
      :mean 16.745633050000002, 
      :std 0.8349223686048823}


The tight sample standard deviation of 0.83 milliseconds indicates that the time provided by Keirin of 16.7 milliseconds is reasonably accurate.

At this point I am not clear whether this is just an edge case or if Keirin will be more generally useful. Certainly Criterium works for most people most of the time.


## License

Distributed under the Eclipse Public License either version 1.0.
