(ns clj-gatling.pipeline
  (:require [clj-gatling.report :refer [combine-with-reporters
                                        generate-with-reporters
                                        as-str-with-reporters
                                        parse-in-batches]]
            [clj-gatling.simulation :as simu]
            [clj-gatling.simulation-util :refer [eval-if-needed
                                                 split-equally
                                                 split-number-equally]]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as string]
            [clojure.core.async :as async :refer [<!! <! >! go-loop]]))

(defn- init-report-generators [reporters results-dir context]
  (map (fn [{:keys [reporter-key generator]}]
         (let [generator-creator (eval-if-needed generator)]
           (assoc (generator-creator {:results-dir results-dir
                                      :context context})
                  :reporter-key reporter-key)))
       reporters))

(defn- init-report-collectors [reporters results-dir context]
  (map (fn [{:keys [reporter-key collector]}]
         (let [collector-creator (eval-if-needed collector)]
           (assoc (collector-creator {:results-dir results-dir
                                      :context context})
                  :reporter-key reporter-key)))
       reporters))

(defn simulation-runner [simulation {:keys [node-id
                                            batch-size
                                            reporters
                                            results-dir
                                            context] :as options}]
  (let [evaluated-simulation (eval-if-needed simulation)
        results (simu/run evaluated-simulation options)
        report-collectors (init-report-collectors reporters results-dir context)
        raw-summary (parse-in-batches evaluated-simulation node-id batch-size results report-collectors)]
    raw-summary))

(defn local-executor [node-id simulation options]
  (println "Starting local executor with id:" node-id)
  (simulation-runner simulation options))


(defn prun
  ([f users-by-node requests-by-node]
   (prun f users-by-node requests-by-node nil))
  ([f users-by-node requests-by-node {:keys [parallelism]}]
   (let [parallelism (or parallelism 10)
         promises (vec (repeatedly (count users-by-node) #(promise)))
         requests-ch (async/to-chan (for [[idx users] (map-indexed vector users-by-node)
                                          :let [requests (nth requests-by-node idx nil)]]
                                      [idx users requests]))]
     (dotimes [_ parallelism]
       (go-loop []
         (when-let [[idx users requests] (<! requests-ch)]
           (let [result (<! (async/thread
                              (f idx users requests)))
                 prom (nth promises idx)]
             (deliver prom result))
           (recur))))
     (map deref promises))))

(defn- assoc-if-not-nil [m k v]
  (if v
    (assoc m k v)
    m))

(defn run [simulation
           {:keys [nodes
                   executor
                   concurrency
                   reporters
                   initialized-reporters
                   requests
                   results-dir
                   context] :as options}]
  (let [users-by-node (split-equally nodes (range concurrency))
        requests-by-node (when requests
                           (split-number-equally nodes requests))
        report-generators (init-report-generators reporters results-dir context)
        report-collectors (init-report-collectors reporters results-dir context)
        results-by-node (prun (fn [node-id users requests]
                                (executor node-id
                                          simulation
                                          (-> options
                                              (dissoc :executor)
                                              (assoc :users users)
                                              (assoc :node-id node-id)
                                              (assoc-if-not-nil :requests requests))))
                              users-by-node
                              requests-by-node
                              options)
        result (reduce (partial combine-with-reporters report-collectors)
                       results-by-node)
        summary (generate-with-reporters report-generators result)]
    (println (string/join "\n" (as-str-with-reporters report-generators summary)))
    summary))

