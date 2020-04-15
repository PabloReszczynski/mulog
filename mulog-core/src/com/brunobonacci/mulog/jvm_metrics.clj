(ns ^{:author "Pablo Reszczynski @PabloReszczynski"
      :doc "Module for sampling some JVM metrics"}
 com.brunobonacci.mulog.jvm-metrics
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str])
  (:import  [java.lang.management MemoryMXBean
             MemoryPoolMXBean
             MemoryUsage
             GarbageCollectorMXBean
             RuntimeMXBean
             ThreadMXBean
             ThreadInfo
             ManagementFactory]
            [javax.management MBeanServerConnection ObjectName]))

(s/def :memory/init int?)
(s/def :memory/used int?)
(s/def :memory/max int?)
(s/def :memory/committed int?)
(s/def :memory/usage-ratio ratio?)

(defprotocol HasName
  "Gives the name of the object"
  (get-name [this]))

(extend-type MemoryPoolMXBean
  HasName
  (get-name [this] (.getName this)))

(extend-type GarbageCollectorMXBean
  HasName
  (get-name [this] (.getName this)))

(s/def ::memory
  (s/keys :req [:memory/init
                :memory/used
                :memory/max
                :memory/committed]
          :opt [:memory/usage-ratio]))

(s/def :memory/total ::memory)
(s/def :memory/heap ::memory)
(s/def :memory/non-heap ::memory)
(s/def ::captured-memory
  (s/keys :opt [:memory/total
                :memory/heap
                :memory/non-heap]))

(defn- get-usage-ratio [^MemoryUsage usage]
  (/ (.getUsed usage) (.getMax usage)))

(defn- get-bean-name [bean]
  (-> bean
      get-name
      (str/replace #"\s+" "-")
      str/lower-case))

(s/fdef capture-memory
  :args (s/cat :mxbean (partial instance? MemoryMXBean) :opts ())
  :ret ::captured-memory)

;; FIXME: Maybe this is not the shape we would like.
(defn- capture-memory [^MemoryMXBean mxbean & {:keys [total heap non-heap]}]
  (letfn [(get-total []
            {:total {:init (+ (-> mxbean .getHeapMemoryUsage .getInit)
                              (-> mxbean .getNonHeapMemoryUsage .getInit))
                     :used (+ (-> mxbean .getHeapMemoryUsage .getUsed)
                              (-> mxbean .getNonHeapMemoryUsage .getUsed))
                     :max  (+ (-> mxbean .getHeapMemoryUsage .getMax)
                              (-> mxbean .getNonHeapMemoryUsage .getMax))
                     :committed
                     (+ (-> mxbean .getHeapMemoryUsage .getCommitted)
                        (-> mxbean .getNonHeapMemoryUsage .getCommitted))}})
          (get-heap []
            {:heap {:init (-> mxbean .getHeapMemoryUsage .getInit)
                    :used (-> mxbean .getHeapMemoryUsage .getUsed)
                    :max  (-> mxbean .getHeapMemoryUsage .getMax)
                    :committed
                    (-> mxbean .getHeapMemoryUsage .getCommitted)
                    :usage-ratio (get-usage-ratio (.getHeapMemoryUsage mxbean))}})
          (get-non-heap []
            {:non-heap {:init (-> mxbean .getNonHeapMemoryUsage .getInit)
                        :used (-> mxbean .getNonHeapMemoryUsage .getUsed)
                        :max  (-> mxbean .getNonHeapMemoryUsage .getMax)
                        :committed
                        (-> mxbean .getNonHeapMemoryUsage .getCommitted)
                        :usage-ratio (get-usage-ratio (.getNonHeapMemoryUsage mxbean))}})]
    (cond-> {}
      total (merge (get-total))
      heap  (merge (get-heap))
      non-heap (merge (get-non-heap)))))

(s/fdef capture-memory-pools
  :args (s/cat :pools (s/coll-of (partial instance? MemoryPoolMXBean)))
  :ret (s/map-of keyword? ratio?))

(defn- capture-memory-pools [pools]
  (into {}
        (for [^MemoryPoolMXBean pool pools
              :let [pname (get-bean-name pool)
                    usage (.getUsage pool)]]
          [(keyword (str pname ".usage"))
           (/ (.getUsed usage)
              (if (= (.getMax usage) -1)
                (.getCommitted usage)
                (.getMax usage)))])))

(s/fdef capture-garbage-collector
  :args (s/cat :gc (s/coll-of (partial instance? GarbageCollectorMXBean)))
  :ret  (s/map-of keyword? int?))

(defn- capture-garbage-collector [gc]
  (apply merge
         (for [^GarbageCollectorMXBean mxbean gc
               :let [name (get-bean-name mxbean)]]
           {(keyword (str name ".count")) (.getCollectionCount mxbean)
            (keyword (str name ".time"))  (.getCollectionTime mxbean)})))

(s/def :attrs/name string?)
(s/def :attrs/vendor string?)
(s/fdef capture-jvm-attrs
  :args (s/cat :runtime (partial instance? RuntimeMXBean))
  :ret (s/keys :req [:attrs/name :attrs/vendor]))

(defn- capture-jvm-attrs [^RuntimeMXBean runtime]
  {:name (.getName runtime)
   :vendor (format "%s %s %s (%s)"
                   (.getVmVendor runtime)
                   (.getVmName runtime)
                   (.getVmVersion runtime)
                   (.getSpecVersion runtime))})

(s/fdef capture-jvx-attrs
  :args (s/cat :server (partial instance? MBeanServerConnection)
               :object-name (partial instance? ObjectName)
               :attr-name string?)
  :ret (s/nilable string?))

;; TODO: Rewrite in a better style
(defn- capture-jvx-attrs
  [^MBeanServerConnection server ^ObjectName object-name attr-name]
  (letfn [(get-obj-name []
            (if (.isPattern object-name)
              (let [found-names (.queryNames server object-name nil)]
                (if (= (.size found-names) 1)
                  (-> found-names .iterator .next)
                  object-name))
              object-name))]
    (try
      (.getAttribute server (get-obj-name) attr-name)
      (catch java.io.IOException _ nil)
      (catch javax.management.JMException _ nil))))

(defn detect-deadlocks [^ThreadMXBean threads]
  (let [ids (.findDeadlockedThreads threads)]
    (if (some? ids)
      (apply merge
             (for [^ThreadInfo info (.getThreadInfo threads ids 100)]
               {(keyword (.getThreadName info))
                (.getStackTrace info)}))
      {})))

(defn get-thread-count [^Thread$State state ^ThreadMXBean threads]
  (count
   (filter
    (fn [^ThreadInfo info] (and (some? info) (= (.getThreadState info) state)))
    (into [] (.getThreadInfo threads (.getAllThreadIds threads) 100)))))

(s/fdef capture-thread-states
  :args (s/cat :threads (partial instance? ThreadMXBean))
  :ret map?)

(defn capture-thread-states [^ThreadMXBean threads]
  (let [deadlocks (detect-deadlocks threads)
        base-map {:count (.getThreadCount threads)
                  :daemon.count (.getDaemonThreadCount threads)
                  :deadlock.count (count deadlocks)
                  :deadlocks deadlocks}]
    (merge
     base-map
     (apply merge
            (for [^Thread$State state (Thread$State/values)]
              {(keyword (str (str/lower-case state) ".count"))
               (get-thread-count state threads)})))))

(defn jvm-sample-memory
  "Captures JVM memory metrics"
  [{:keys [total heap non-heap pools]}]
  (let [mxbean (ManagementFactory/getMemoryMXBean)
        captured-memory (capture-memory mxbean
                                        :total total :heap heap :non-heap non-heap)
        poolmxbean (when pools (into [] (ManagementFactory/getMemoryPoolMXBeans)))
        captured-pools (when pools {:pools (capture-memory-pools poolmxbean)})]
    (merge captured-memory captured-pools)))

(defn jvm-sample-gc
  "Captures JVM garbage collector metrics"
  []
  (let [gc (into [] (ManagementFactory/getGarbageCollectorMXBeans))]
    (capture-garbage-collector gc)))

(defn jvm-sample-threads
  "Captures JVM threads metrics"
  []
  (let [threads (ManagementFactory/getThreadMXBean)]
    (capture-thread-states threads)))

(defn jvm-sample-attrs
  "Captures JVM attributes"
  []
  (let [runtime (ManagementFactory/getRuntimeMXBean)]
    (capture-jvm-attrs runtime)))

(defn jvm-sample
  [{:keys [memory gc threads jvm-attrs]}]
  (let [sample-mem (when memory {:memory (jvm-sample-memory memory)})
        gc (when gc {:gc (jvm-sample-gc)})
        threads (when threads {:threads (jvm-sample-threads)})
        jvm-attrs (when jvm-attrs {:jvm-attrs (jvm-sample-attrs)})]
    (merge {} sample-mem gc threads jvm-attrs)))
