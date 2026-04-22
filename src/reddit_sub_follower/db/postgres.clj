(ns reddit-sub-follower.db.postgres
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [reddit-sub-follower.configs :as configs])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)
           (java.time Instant LocalDateTime OffsetDateTime ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.sql Timestamp)))

(def postgres-ts-formatter
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss[.SSSSSS]"))

(defn- ->datasource []
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (str "jdbc:postgresql://"
                                   configs/database-host ":"
                                   configs/database-port "/"
                                   configs/database-name))
                 (.setUsername configs/database-user)
                 (.setPassword configs/database-password)
                 (.setMaximumPoolSize 5))]
    (HikariDataSource. config)))

(defonce datasource (delay (->datasource)))

(defn init-db! []
  (jdbc/execute! @datasource
                 ["
CREATE TABLE IF NOT EXISTS subreddit_last_seen (
  subreddit_name TEXT PRIMARY KEY,
  last_seen_id TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
)"])
  (jdbc/execute! @datasource
                 ["
CREATE TABLE IF NOT EXISTS seen_posts (
  post_id TEXT NOT NULL,
  subreddit_name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (post_id, subreddit_name)
)"])
  (jdbc/execute! @datasource
                 ["
CREATE OR REPLACE FUNCTION set_subreddit_last_seen_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;"])
  (jdbc/execute! @datasource
                 ["
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_trigger
    WHERE tgname = 'update_subreddit_last_seen_updated_at_trigger'
  ) THEN
    CREATE TRIGGER update_subreddit_last_seen_updated_at_trigger
    BEFORE UPDATE ON subreddit_last_seen
    FOR EACH ROW
    EXECUTE FUNCTION set_subreddit_last_seen_updated_at();
  END IF;
END;
$$;"]))

(defn load-all-last-seen []
  (let [rows (jdbc/execute! @datasource
                            ["SELECT subreddit_name, last_seen_id FROM subreddit_last_seen"]
                            {:builder-fn rs/as-unqualified-lower-maps})]
    (reduce (fn [m {:keys [subreddit_name last_seen_id]}]
              (assoc m subreddit_name last_seen_id))
            {}
            rows)))

(defn get-last-seen-for-subreddit [subreddit-name]
  (let [row (jdbc/execute-one! @datasource
                               ["SELECT last_seen_id AS last_seen_id FROM subreddit_last_seen
                                 WHERE subreddit_name = ?"
                                subreddit-name]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (:last_seen_id row)))

(defn get-updated-at-for-subreddit [subreddit-name]
  (let [row (jdbc/execute-one! @datasource
                               ["SELECT updated_at FROM subreddit_last_seen WHERE subreddit_name = ?"
                                subreddit-name]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (when-let [updated-at (:updated_at row)]
      (cond
        (instance? Instant updated-at) updated-at
        (instance? OffsetDateTime updated-at) (.toInstant ^OffsetDateTime updated-at)
        (instance? LocalDateTime updated-at) (.toInstant ^LocalDateTime updated-at ZoneOffset/UTC)
        (instance? Timestamp updated-at) (.toInstant ^Timestamp updated-at)
        :else (-> (LocalDateTime/parse (str updated-at) postgres-ts-formatter)
                  (.toInstant ZoneOffset/UTC))))))

(defn update-last-seen! [subreddit-name last-seen-id]
  (jdbc/execute-one! @datasource
                     ["
INSERT INTO subreddit_last_seen (subreddit_name, last_seen_id)
VALUES (?, ?)
ON CONFLICT(subreddit_name)
DO UPDATE SET
last_seen_id = excluded.last_seen_id;"
                      subreddit-name last-seen-id]))

(defn add-seen-post! [post-id subreddit-name]
  (sql/insert! @datasource :seen_posts
               {:post_id        post-id
                :subreddit_name subreddit-name}))

(defn post-seen? [post-id subreddit-name]
  (let [result (jdbc/execute! @datasource
                              ["SELECT 1 FROM seen_posts WHERE post_id = ? AND subreddit_name = ?"
                               post-id subreddit-name]
                              {:builder-fn rs/as-unqualified-lower-maps})]
    (not (empty? result))))
