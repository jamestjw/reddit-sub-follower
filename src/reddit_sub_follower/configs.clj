(ns reddit-sub-follower.configs
  (:require
   [reddit-sub-follower.utils :as utils]
   [clojure.string :as str]))

(def cache-directory (or (System/getenv "CACHE_DIRECTORY") "./"))
(def db-file (or (System/getenv "DB_FILE")
                 (utils/join-paths cache-directory ".cache.db")))
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

(def scrape-filter (if-some [f (System/getenv "REDDIT_SCRAPE_FILTER_REGEX")]
                     (let [regex (re-pattern (str "(?i)" f))]
                       #(re-find regex %))
                     (constantly true)))

(def discord-token (System/getenv "DISCORD_BOT_TOKEN"))
(def discord-channel-id (System/getenv "DISCORD_DEST_CHANNEL_ID"))
(def discord-intents #{:guilds :guild-messages})

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
  (when-not discord-token
    (throw (new Exception "missing discord token")))
  (when-not discord-channel-id
    (throw (new Exception "missing discord channel ID"))))
