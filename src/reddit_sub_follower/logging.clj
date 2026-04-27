(ns reddit-sub-follower.logging
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.util Date]))

(defn- normalize-log-key [k]
  (cond
    (keyword? k) (-> (name k) (str/replace "-" "_"))
    (string? k) (str/replace k "-" "_")
    :else k))

(defn- normalize-log-value [v]
  (cond
    (map? v) (reduce-kv (fn [m k value]
                          (assoc m (normalize-log-key k) (normalize-log-value value)))
                        {}
                        v)
    (sequential? v) (mapv normalize-log-value v)
    (keyword? v) (name v)
    (instance? Throwable v) (.getMessage ^Throwable v)
    :else v))

(defn- log-event-fields [vargs]
  (if (and (= 1 (count vargs))
           (map? (first vargs)))
    (normalize-log-value (first vargs))
    {:message (str/join " " (map pr-str vargs))}))

(defn- format-log-timestamp [timestamp_ instant]
  (or (some-> timestamp_ deref)
      (cond
        (instance? Instant instant) (str instant)
        (instance? Date instant) (str (.toInstant ^Date instant))
        (some? instant) (str instant)
        :else nil)))

(defn- json-log-output-fn [{:keys [level ?ns-str ?line ?err timestamp_ instant vargs]}]
  (let [ts (format-log-timestamp timestamp_ instant)
        payload (cond-> {:ts ts
                         :level (name level)
                         :logger ?ns-str
                         :line ?line}
                  ?err (assoc :exception_message (.getMessage ^Throwable ?err))
                  true (merge (log-event-fields vargs)))]
    (json/generate-string payload)))

(defn setup-logging! []
  (log/merge-config! {:output-fn json-log-output-fn}))
