(ns reddit-sub-follower.core
  (:gen-class)
  (:require
   [clojure.core.async    :as async]
   [discljord.connections :as conn]
   [discljord.messaging   :as msg]
   [taoensso.timbre :as log]
   [reddit-sub-follower.utils :as utils]
   [reddit-sub-follower.reddit :as reddit]))

(def reddit-username (or (System/getenv "REDDIT_USERNAME")
                         (throw (new Exception "missing reddit username"))))

(def subreddit-name (or (System/getenv "SUBREDDIT_NAME")
                        (throw (new Exception "missing subreddit name"))))

(def oauth-client-id (or (System/getenv "REDDIT_OAUTH_CLIENT_ID")
                         (throw (new Exception "missing client ID"))))

(def oauth-client-secret (or (System/getenv "REDDIT_OAUTH_CLIENT_SECRET")
                             (throw (new Exception "missing client secret"))))

(def oauth-redirect-uri (or (System/getenv "REDDIT_OAUTH_REDIRECT_URI")
                            (throw (new Exception "missing redirect uri"))))

; TODO: initiate the login flow to get the code from the app if it doesn't exist
(def oauth-auth-code (or (System/getenv "REDDIT_OAUTH_CODE")
                         (throw (new Exception "missing auth code"))))

(def oauth-access-token (System/getenv "REDDIT_OAUTH_ACCESS_TOKEN"))
(def oauth-refresh-token (System/getenv "REDDIT_OAUTH_REFRESH_TOKEN"))

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
(def discord-intents #{:guilds :guild-messages})

(def last-seen-file ".lastseen")

(defn discord-msg-formatter [post]
  (let [title (:title post)
        link (str "https://www.reddit.com" (:permalink post))]
    (format "%s\n%s" title link)))

(defn -main
  [& args] ; The `& args` allows your program to accept command-line arguments
  (let [event-ch      (async/chan 100)
        connection-ch (conn/connect-bot! discord-token event-ch :intents discord-intents)
        message-ch    (msg/start-connection! discord-token)
        reddit-token (if (and oauth-access-token oauth-refresh-token)
                       (reddit/->Token oauth-access-token oauth-refresh-token) ; TODO: Check if these are valid
                       (reddit/exchange-code-for-tokens
                        oauth-auth-code
                        oauth-client-id
                        oauth-client-secret
                        oauth-redirect-uri))
        output-fn #(msg/create-message! message-ch discord-channel-id :content (discord-msg-formatter %))
        initial-last-seen (utils/read-first-line last-seen-file)]
    (try
      (loop [last-seen initial-last-seen]
        (let [last-seen (reddit/get-new-posts reddit-token reddit-username subreddit-name
                                              last-seen scrape-filter output-fn)]
          (log/info "Last seen:" last-seen)
          (spit last-seen-file last-seen)
          (Thread/sleep scrape-interval-ms)
          (recur last-seen)))
      (finally
        (msg/stop-connection! message-ch)
        (conn/disconnect-bot!  connection-ch)
        (async/close!           event-ch)))))
