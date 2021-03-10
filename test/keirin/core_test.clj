;; Copyright ©️ Rachel Bowyer 2017, 2020. All rights reserved.
;;
;; This program and the accompanying materials
;; are made available under the terms of the Eclipse Public License v1.0
;; which accompanies this distribution, and is available at
;; http://www.eclipse.org/legal/epl-v10.html

(ns keirin.core_test
  (:require [keirin.core :as kc]
            [clojure.test :refer :all]))

(def ^:private chain-hashes #'kc/chain-hashes)
(def ^:private sq #'kc/sq)
(def ^:private abs #'kc/abs)
(def ^:private mean #'kc/mean)
(def ^:private sample-variance #'kc/sample-variance)
(def ^:private sample-standard-deviation #'kc/sample-standard-deviation)
(def ^:private median #'kc/median)
(def ^:private MAD #'kc/MAD)
(def ^:private parse-gc-file-name #'kc/parse-gc-file-name)


(deftest chain-hashes-test
  (is (= 0 (chain-hashes nil nil)) "Handles nil")
  (is (= 0 (chain-hashes 2 2)) "xor behaviour")
  (is (= 3 (chain-hashes 1 2)) "xor behaviour"))

(deftest sq-test
  (are [x r] (= r (sq x))
    0   0
    2   4
    -1  1))

(deftest abs-test
  (are [x r] (= r (abs x))
     0    0.0
     -1   1.0
     -1.5 1.5
     2    2.0))

(deftest mean-test
  (are [x r] (= r (mean x))
     [2]          2.0
     [4 2 3 1]    2.5
     [2 4 6 8]    5.0))

(deftest sample-variance-test
  (are [x r] (= r (sample-variance x))
     [1]        0.0
     [1 2]      0.5
     [1 2 3]    1.0
     [1 2 3 4]  (/ 5 3.0)))

(deftest sample-standard-deviation-test
  (are [x r] (= r (sample-standard-deviation x))
     [1]        0.0
     [1 2 3]    1.0
     [2 4 6]    2.0))

(deftest median-test
  (are [x r] (= r (median x))
      [1]         1.0
      [1 2]       1.5
      [1 3 2]     2.0
      [10 2 6 4]  5.0))

(deftest MAD-test
  (are [x r] (= r (MAD x))
      [1 1 1 1]   0.0 ; All elements are 0 from the central point
      [1 2 3 4]   1.0
      [1 2 3 11]  1.0 ; Large outlier does not disturb median or MAD
      [2 8 4 6]   2.0))

(deftest parse-gc-file-name-test
  (is (= "gc.out" (parse-gc-file-name "-Xlog:gc:gc.out"))))