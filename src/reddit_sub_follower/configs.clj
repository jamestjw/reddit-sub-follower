(ns reddit-sub-follower.configs
  (:require
   [reddit-sub-follower.utils :as utils]
   [clojure.string :as str]))

(def db-file ".cache.db")
(def oauth-access-token-file ".accesstoken")
(def oauth-refresh-token-file ".refreshtoken")

(def reddit-username (or (System/getenv "REDDIT_USERNAME")
                         (throw (new Exception "missing reddit username"))))

(def subreddit-names (-> (or (System/getenv "SUBREDDIT_NAME")
                             (throw (new Exception "missing subreddit name")))
                         (str/split #",")))

(def oauth-client-id (or (System/getenv "REDDIT_OAUTH_CLIENT_ID")
                         (throw (new Exception "missing client ID"))))

(def oauth-client-secret (or (System/getenv "REDDIT_OAUTH_CLIENT_SECRET")
                             (throw (new Exception "missing client secret"))))

(def oauth-redirect-uri (or (System/getenv "REDDIT_OAUTH_REDIRECT_URI")
                            (throw (new Exception "missing redirect uri"))))

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

(def discord-token (or (System/getenv "DISCORD_BOT_TOKEN")
                       (throw (new Exception "missing discord token"))))
(def discord-channel-id (or (System/getenv "DISCORD_DEST_CHANNEL_ID")
                            (throw (new Exception "missing discord channel id"))))
