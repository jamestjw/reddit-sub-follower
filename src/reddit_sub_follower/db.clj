(ns reddit-sub-follower.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [reddit-sub-follower.configs :as configs]
            [taoensso.timbre :as log])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)))

;; --- Database Configuration ---
;; Defines the location of your SQLite file.
(def db-spec {:dbtype "sqlite" :dbname "cache.db"})

(defn- ->datasource
  "Creates a HikariCP pooled connection datasource."
  [db-file-path]
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl (str "jdbc:sqlite:" db-file-path))
                 ;; Add other pool options here if needed
                 (.setMaximumPoolSize 5))]
    (HikariDataSource. config)))

;; Use a delay to create the datasource only when it's first needed.
(defonce datasource (delay (->datasource configs/db-file)))

(defn ensure-trigger-exists
  "Checks if a trigger exists in the SQLite DB, and creates it if it doesn't."
  [trigger-name create-trigger-sql]
  (let [existing-trigger (sql/query @datasource
                                    ["SELECT name FROM sqlite_master WHERE type='trigger' AND name=?"
                                     trigger-name])]

    (if (empty? existing-trigger)
      (do
        (log/info (str "Trigger '" trigger-name "' not found. Creating it..."))
        (jdbc/execute! @datasource [create-trigger-sql]))
      (log/info (str "Trigger '" trigger-name "' already exists. Skipping.")))))

;; --- Database Initialization ---
(defn init-db!
  "Initializes the database. Creates the necessary tables if they don't exist."
  []
  (jdbc/execute! @datasource
                 ["
CREATE TABLE IF NOT EXISTS subreddit_last_seen (
  subreddit_name TEXT PRIMARY KEY NOT NULL,
  last_seen_id   TEXT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
)"])
  (ensure-trigger-exists "update_subreddit_last_seen_updated_at_trigger"
                         "
CREATE TRIGGER update_subreddit_last_seen_updated_at_trigger
AFTER UPDATE ON subreddit_last_seen
BEGIN
   UPDATE subreddit_last_seen SET updated_at=STRFTIME('%Y-%m-%d %H:%M:%f', 'NOW') WHERE subreddit_name = NEW.subreddit_name;
END;"))

;; --- Data Access Functions ---
(defn load-all-last-seen
  "Loads the entire last-seen map from the database.
  Returns a map like {'clojure' 't3_abcde', ...}"
  []
  (with-open [conn (jdbc/get-connection @datasource)]
    (->> (sql/query conn ["SELECT subreddit_name, last_seen_id FROM subreddit_last_seen"])
         ;; The result from the DB is like:
         ;; ({:subreddit_name "clojure", :last_seen_id "t3_abcde"}, ...)
         (reduce (fn [m {:keys [subreddit_name last_seen_id]}]
                   (assoc m subreddit_name last_seen_id))
                 {}))))

(defn get-last-seen-for-subreddit
  "Fetches the last_seen_id for a given subreddit_name from the database.
  Returns the ID as a string, or nil if not found."
  [subreddit-name]
  (let [result-row
        (sql/query @datasource
                   ["SELECT last_seen_id FROM subreddit_last_seen WHERE subreddit_name = ?"
                    subreddit-name])]
    (-> result-row first :subreddit_last_seen/last_seen_id)))

(defn update-last-seen!
  "Upserts a last-seen ID for a given subreddit."
  [subreddit-name last-seen-id]
  (jdbc/execute-one!
   @datasource
   ["
    INSERT INTO subreddit_last_seen (subreddit_name, last_seen_id)
    VALUES (?, ?)
    ON CONFLICT(subreddit_name)
    DO UPDATE SET
    last_seen_id = excluded.last_seen_id;"
    subreddit-name last-seen-id]))
