(ns clj-gatling.pipeline-test
  (:require [clojure.test :refer :all]
            [clj-gatling.test-helpers :refer :all]
            [clj-containment-matchers.clojure-test :refer :all]
            [clj-gatling.pipeline :as pipeline]))

(defn- stub-executor [node-ids]
  (fn [node-id simulation options]
    (swap! node-ids conj node-id)
    (pipeline/simulation-runner simulation options)))

(deftest running-pipeline
  (let [node-ids (atom #{})
        executor (stub-executor node-ids)
        reporters [a-reporter
                   b-reporter]
        summary (pipeline/run 'clj-gatling.example/test-simu {:executor executor
                                                              :nodes 3
                                                              :context {}
                                                              :results-dir "tmp"
                                                              :concurrency 5
                                                              :requests 25
                                                              :timeout-in-ms 1000
                                                              :batch-size 10
                                                              :reporters reporters})]
    ;Stub reporter returns number of batches parsed per reporter
    (is (equal? summary {:a 3 :b 3}))
    (is (= #{0 1 2} @node-ids))))
