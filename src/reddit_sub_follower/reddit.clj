(ns reddit-sub-follower.reddit
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [reddit-sub-follower.configs :as configs]))

(def access-token-url "https://www.reddit.com/api/v1/access_token")
(defn mk-user-agent [username]
  (format "script:Get Subreddit:v1.1 (by /u/%s)" username))

(defn mk-oauth-url [client-id redirect-uri]
  (format "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=random_string&redirect_uri=%s&duration=permanent&scope=read"
          client-id redirect-uri))

(defrecord Token [access-token refresh-token client-id client-secret redirect-uri])

(defn exchange-code-for-tokens [code client-id client-secret redirect-uri]
  (if (nil? code)
    (throw (new Exception "missing auth code"))
    (let [payload {:grant_type "authorization_code" :code code :redirect_uri redirect-uri}
          resp (http/post access-token-url
                          {:form-params payload
                           :basic-auth [client-id client-secret]})
          body (->  resp
                    :body
                    (json/parse-string true))]
      (->Token (:access_token body) (:refresh_token body) client-id client-secret redirect-uri))))

(defn mk-token []
  (let [token
        (if (and configs/oauth-access-token configs/oauth-refresh-token)
          (->Token configs/oauth-access-token configs/oauth-refresh-token
                   configs/oauth-client-id configs/oauth-client-secret
                   configs/oauth-redirect-uri) ; TODO: Check if these are valid
          (exchange-code-for-tokens
           configs/oauth-auth-code configs/oauth-client-id
           configs/oauth-client-secret configs/oauth-redirect-uri))]
    (spit configs/oauth-access-token-file (:access-token token))
    (spit configs/oauth-refresh-token-file (:refresh-token token))
    token))

(defn refresh-reddit-token
  "Uses a refresh token to get a new access token from the Reddit API."
  [token]
  (try
    (let [opts {:form-params {:grant_type    "refresh_token"
                              :refresh_token (:refresh-token token)}
                :headers     {"User-Agent" (mk-user-agent configs/reddit-username)
                              :content-type "application/x-www-form-urlencoded"}
                :basic-auth  [(:client-id token) (:client-secret token)]}
          response  (http/post access-token-url opts)
          body      (json/parse-string (:body response) true)
          access-token (:access_token body)]

      (if-not access-token
        (do
          (log/error "Refresh response did not contain an access token. Body:" body)
          (throw (ex-info "Could not refresh token" {:response-body body})))

        (do
          (spit configs/oauth-access-token-file access-token)
          (assoc token :access-token access-token))))

    (catch Exception e
      (let [error-data (ex-data e)
            status     (:status error-data)
            body       (:body error-data)]
        (log/error "HTTP error occurred during token refresh, status:" status)
        (log/error "Response Body:" body)
        ;; Re-throw the exception or return the original token to signal failure
        (throw (ex-info "Failed to refresh token" {:status status :body body} e))))))

(defn get-new-posts
  "Fetches new Reddit posts, processes them, and returns the latest post ID."
  [{:keys [token username subreddit-name last-seen filter-fn output-fn]}]
  (letfn [(fetch
            ([token] (fetch token 0))
            ([token num-retries]
             (try
               (let [url (format "https://oauth.reddit.com/r/%s/new" subreddit-name)
                     user-agent (mk-user-agent username)
                     headers {:user-agent user-agent}
                     resp (http/get url {:oauth-token (:access-token token)
                                         :headers headers
                                         :query-params {:before last-seen :limit 100}})
                     body (-> resp :body (json/parse-string true) :data)
                     num-posts (-> body :children count)
                     new-last-seen (if-some [post (-> body :children first)]
                                     (-> post :data :name)
                                     last-seen)]
                 ;; Process posts in chronological order (oldest to newest)
                 (doseq [post (map :data (-> body :children reverse))]
                   (when (filter-fn (:name post) (:title post))
                     (output-fn post)))

                 (log/infof "Num posts: %d, Last seen (%s): %s" num-posts subreddit-name new-last-seen)
                 ;; Return the ID of the newest post for the next iteration
                 [token new-last-seen])
               (catch java.io.IOException e
                 (log/errorf "caught connection exception: %s\n" (.getMessage e))
                 (if (< num-retries 3)
                   (do (log/error "retrying...\n") (fetch token (+ 1 num-retries)))
                   ;; On multiple errors, return the old last-seen ID to retry from the same point
                   [token last-seen]))
               (catch Exception e
                 (let [error-data (ex-data e)
                       status (:status error-data)]
                   ;; Check if the error is a 401/403 and we haven't retried yet
                   (if (or (= 401 status) (= 403 status))
                     (do
                       (log/info "Token expired, refreshing...")
                       (let [new-token (refresh-reddit-token token)]
                         (log/info "Successfully got new token" new-token)
                         ; Retry while resetting the reset counter
                         (fetch new-token)))
                     (throw (new Exception (str "Unexpected error: " (.getMessage e))))))))))]

    (fetch token)))
