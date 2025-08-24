(ns reddit-sub-follower.core
  (:gen-class))

(require '[clj-http.client :as http]
         '[cheshire.core :as json]
         '[clojure.core.async    :as async]
         '[discljord.connections :as conn]
         '[discljord.messaging   :as msg])

(import '[java.time Instant ZonedDateTime ZoneId]
        '[java.time.format DateTimeFormatter])

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

(def oauth-url (format "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=random_string&redirect_uri=%s&duration=permanent&scope=read" oauth-client-id oauth-redirect-uri))
(def user-agent (format "script:Get Subreddit:v1.1 (by /u/%s)" reddit-username))

(def discord-token (or (System/getenv "DISCORD_BOT_TOKEN")
                       (throw (new Exception "missing discord token"))))
(def discord-channel-id (or (System/getenv "DISCORD_DEST_CHANNEL_ID")
                            (throw (new Exception "missing discord chan id"))))
(def discord-intents #{:guilds :guild-messages})

(defrecord Token [access-token refresh-token])

(defn stringify-epoch
  "Converts a Unix epoch timestamp (seconds) to a formatted string in the local timezone."
  [epoch-seconds]
  (let [;; Define the desired output format
        formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z")
        ;; Create an Instant from the epoch seconds
        instant (Instant/ofEpochSecond epoch-seconds)
        ;; Get the system's default timezone
        local-zone (ZoneId/systemDefault)
        ;; Create a ZonedDateTime object by applying the timezone to the Instant
        zoned-datetime (ZonedDateTime/ofInstant instant local-zone)]
    ;; Format the ZonedDateTime object into a string
    (.format zoned-datetime formatter)))

(defn exchange-code-for-tokens [code]
  (let [payload {:grant_type "authorization_code" :code code :redirect_uri oauth-redirect-uri}
        resp (http/post "https://www.reddit.com/api/v1/access_token"
                        {:form-params payload
                         :basic-auth [oauth-client-id oauth-client-secret]})
        body (->  resp
                  :body
                  (json/parse-string true))]
    (->Token (:access_token body) (:refresh_token body))))

; (defn obtain-oauth-code [client_id]
;   (let [url (format "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=random_string&redirect_uri=http://localhost&duration=permanent&scope=read" client_id)]))

(defn get-new-posts [token last-seen output-fn formatter]
  (try
    (let [url (format "https://oauth.reddit.com/r/%s/new" subreddit-name)
          headers {:user-agent user-agent}
          resp (http/get url {:oauth-token (:access-token token)
                              :headers headers
                              :query-params {:before last-seen :limit 100}})
          body (->  resp
                    :body
                    (json/parse-string true)
                    :data)
          last-seen (if-some [post (-> body :children first)] (-> post :data :name) last-seen)]
      (doseq [post (map :data (-> body :children reverse))]
        (when (scrape-filter (:title post))
          (output-fn (formatter post))))
      last-seen)
    (catch java.net.ConnectException e
      (do
        (println (str "caught connection exception: " (.getMessage e)))
        last-seen))))

; (defn -main
;   [args]
;   (let [token (if (and oauth-access-token oauth-refresh-token)
;                 (->Token oauth-access-token oauth-refresh-token) ; TODO: Check if these are valid
;                 (exchange-code-for-tokens oauth-auth-code))]
;     (loop [last-seen nil]
;       (let [last-seen  (get-new-posts token last-seen)]
;         (println (format "Last seen (%s): %s" (stringify-epoch (epoch-seconds)) last-seen))
;         (Thread/sleep scrape-interval-ms)
;         (recur last-seen)))))
;
; (-main *command-line-args*)

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
                       (->Token oauth-access-token oauth-refresh-token) ; TODO: Check if these are valid
                       (exchange-code-for-tokens oauth-auth-code))
        output-fn #(msg/create-message! message-ch discord-channel-id :content %)]
    (try
      ; (loop []
      ;   (let [[event-type event-data] (async/<!! event-ch)]
      ;     (println "🎉 NEW EVENT! 🎉")
      ;     (println "Event type:" event-type)
      ;     (println "Event data:" (pr-str event-data))
      ;     (recur)))
      (loop [last-seen nil]
        (let [last-seen (get-new-posts reddit-token last-seen output-fn discord-msg-formatter)]
          (Thread/sleep scrape-interval-ms)
          (recur last-seen)))
      (finally
        (msg/stop-connection! message-ch)
        (conn/disconnect-bot!  connection-ch)
        (async/close!           event-ch)))))
