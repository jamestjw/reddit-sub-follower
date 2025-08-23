(require '[babashka.http-client :as http]
         '[cheshire.core :as json])

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

(def oauth-url (format "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=random_string&redirect_uri=%s&duration=permanent&scope=read" oauth-client-id oauth-redirect-uri))
(def user-agent (format "script:Get Subreddit:v1.1 (by /u/%s)" reddit-username))

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

(defn get-new-posts [token last-seen]
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
      (let [created-at (-> post :created_utc stringify-epoch)
            title (:title post)
            name (:name post)
            link (str "https://www.reddit.com" (:permalink post))]
        (print (format "%s (%s):\n%s\n%s\n\n" name created-at title link))))
    last-seen))

(defn epoch-seconds []
  (long (/ (System/currentTimeMillis) 1000)))

(defn -main
  [args]
  (let [token (if (and oauth-access-token oauth-refresh-token)
                (->Token oauth-access-token oauth-refresh-token) ; TODO: Check if these are valid
                (exchange-code-for-tokens oauth-auth-code))]
    ; (println (format "access token: %s\nrefresh token: %s" (:access-token token) (:refresh-token token)))
    (loop [last-seen nil]
      (let [last-seen  (get-new-posts token last-seen)]
        (println (format "Last seen (%s): %s" (stringify-epoch (epoch-seconds)) last-seen))
        (Thread/sleep scrape-interval-ms)
        (recur last-seen)))))

(-main *command-line-args*)
