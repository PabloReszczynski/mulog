(ns com.brunobonacci.mulog.agents
  (:require [com.brunobonacci.mulog.buffer
             :refer [ring-buffer] :as rb])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor
            TimeUnit ScheduledFuture Future]))



(defn scheduled-thread-pool
  [core-pool-size]
  (ScheduledThreadPoolExecutor. core-pool-size))



(def timer-pool
  (scheduled-thread-pool 2))



(defn recurring-task
  [delay-millis task]
  (let [^ScheduledFuture ftask
        (.scheduleAtFixedRate
         ^ScheduledThreadPoolExecutor timer-pool
         (fn [] (try (task) (catch Exception x))) ;; TODO log
         delay-millis delay-millis TimeUnit/MILLISECONDS)]
    (fn [] (.cancel ftask true))))



(defn buffer-agent
  [capacity]
  (agent (rb/ring-buffer capacity)
         :error-mode :continue))
