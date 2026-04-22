(ns reddit-sub-follower.configs
  (:require
   [reddit-sub-follower.utils :as utils]
   [clojure.string :as str]))

(def cache-directory (or (System/getenv "CACHE_DIRECTORY") "./"))
(def database-backend (or (System/getenv "DATABASE_BACKEND") "sqlite"))
(def db-file (or (System/getenv "DB_FILE")
                 (utils/join-paths cache-directory ".cache.db")))
(def database-host (System/getenv "DATABASE_HOST"))
(def database-port (System/getenv "DATABASE_PORT"))
(def database-name (System/getenv "DATABASE_NAME"))
(def database-user (System/getenv "DATABASE_USER"))
(def database-password (System/getenv "DATABASE_PASSWORD"))
(def oauth-access-token-file (utils/join-paths cache-directory ".accesstoken"))
(def oauth-refresh-token-file (utils/join-paths cache-directory ".refreshtoken"))

(def reddit-username (System/getenv "REDDIT_USERNAME"))

(def subreddit-names (if-let [name (System/getenv "SUBREDDIT_NAME")]
                       (str/split name #",")
                       []))

(def oauth-client-id (System/getenv "REDDIT_OAUTH_CLIENT_ID"))

(def oauth-client-secret (System/getenv "REDDIT_OAUTH_CLIENT_SECRET"))

(def oauth-redirect-uri (System/getenv "REDDIT_OAUTH_REDIRECT_URI"))

; TODO: initiate the login flow to get the code from the app if it doesn't exist
(def oauth-auth-code (System/getenv "REDDIT_OAUTH_CODE"))

(def oauth-access-token (or (utils/read-first-line oauth-access-token-file)
                            (System/getenv "REDDIT_OAUTH_ACCESS_TOKEN")))
(def oauth-refresh-token (or (utils/read-first-line oauth-refresh-token-file)
                             (System/getenv "REDDIT_OAUTH_REFRESH_TOKEN")))

(def scrape-interval-ms (if-some [s (System/getenv "REDDIT_SCRAPE_INTERVAL_SECONDS")]
                          (* 1000 (Integer/parseInt s)) 60000))

(def scrape-query-filter (if-some [f (System/getenv "REDDIT_SCRAPE_FILTER_REGEX")]
                           (let [regex (re-pattern (str "(?i)" f))]
                             #(re-find regex %))
                           (constantly true)))

(def no-data-reset-interval-secs
  (Integer/parseInt (or (System/getenv "NO_DATA_RESET_INTERVAL_SECONDS") "3600")))

(def seen-posts-retention-days
  (Integer/parseInt (or (System/getenv "SEEN_POSTS_RETENTION_DAYS") "7")))

(def seen-posts-cleanup-interval-secs
  (Integer/parseInt (or (System/getenv "SEEN_POSTS_CLEANUP_INTERVAL_SECONDS") "21600")))

(def discord-webhook-url (System/getenv "DISCORD_WEBHOOK_URL"))

(defn validate-configs! []
  (when-not reddit-username
    (throw (new Exception "missing reddit username")))
  (when (empty? subreddit-names)
    (throw (new Exception "missing subreddit names")))
  (when-not oauth-client-id
    (throw (new Exception "missing client ID")))
  (when-not oauth-client-secret
    (throw (new Exception "missing client secret")))
  (when-not oauth-access-token
    (throw (new Exception "missing access token")))
  (when-not oauth-redirect-uri
    (throw (new Exception "missing redirect uri")))
  (when (<= seen-posts-retention-days 0)
    (throw (new Exception "SEEN_POSTS_RETENTION_DAYS must be > 0")))
  (when (<= seen-posts-cleanup-interval-secs 0)
    (throw (new Exception "SEEN_POSTS_CLEANUP_INTERVAL_SECONDS must be > 0")))
  (when-not (#{"sqlite" "postgres"} database-backend)
    (throw (new Exception "invalid DATABASE_BACKEND, expected one of: sqlite, postgres")))
  (when (= database-backend "postgres")
    (when-not database-host
      (throw (new Exception "missing database host")))
    (when-not database-port
      (throw (new Exception "missing database port")))
    (when-not database-name
      (throw (new Exception "missing database name")))
    (when-not database-user
      (throw (new Exception "missing database user")))
    (when-not database-password
      (throw (new Exception "missing database password"))))
  (when-not discord-webhook-url
    (throw (new Exception "missing discord webhook url"))))
