(ns reddit-sub-follower.db.sqlite
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [reddit-sub-follower.configs :as configs]
            [taoensso.timbre :as log]
            [reddit-sub-follower.utils :as utils])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)))

(defn- ->datasource [db-file-path]
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (str "jdbc:sqlite:" db-file-path))
                 (.setMaximumPoolSize 5))]
    (HikariDataSource. config)))

(defonce datasource (delay (->datasource configs/db-file)))

(defn ensure-trigger-exists [trigger-name create-trigger-sql]
  (let [existing-trigger (jdbc/execute! @datasource
                                        ["SELECT name FROM sqlite_master WHERE type='trigger' AND name=?"
                                         trigger-name]
                                        {:builder-fn rs/as-unqualified-lower-maps})]
    (if (empty? existing-trigger)
      (do
        (log/info (str "Trigger '" trigger-name "' not found. Creating it..."))
        (jdbc/execute! @datasource [create-trigger-sql]))
      (log/info (str "Trigger '" trigger-name "' already exists. Skipping.")))))

(defn init-db! []
  (jdbc/execute! @datasource
                 ["
CREATE TABLE IF NOT EXISTS subreddit_last_seen (
  subreddit_name TEXT PRIMARY KEY NOT NULL,
  last_seen_id   TEXT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
)"])
  (jdbc/execute! @datasource ["
CREATE TABLE IF NOT EXISTS seen_posts (
  post_id TEXT NOT NULL,
  subreddit_name TEXT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (post_id, subreddit_name)
)"])
  (ensure-trigger-exists "update_subreddit_last_seen_updated_at_trigger"
                         "
CREATE TRIGGER update_subreddit_last_seen_updated_at_trigger
AFTER UPDATE ON subreddit_last_seen
BEGIN
   UPDATE subreddit_last_seen
   SET updated_at=STRFTIME('%Y-%m-%d %H:%M:%f', 'NOW')
   WHERE subreddit_name = NEW.subreddit_name;
END;"))

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
                               ["SELECT strftime('%Y-%m-%d %H:%M:%S', updated_at) AS updated_at
                                 FROM subreddit_last_seen WHERE subreddit_name = ?"
                                subreddit-name]
                               {:builder-fn rs/as-unqualified-lower-maps})]
    (when-let [timestamp (:updated_at row)]
      (utils/parse-timestamp timestamp "yyyy-MM-dd HH:mm:ss"))))

(defn update-last-seen! [subreddit-name last-seen-id]
  (jdbc/execute-one!
   @datasource
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
