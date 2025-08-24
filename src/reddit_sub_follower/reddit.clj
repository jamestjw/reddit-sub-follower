(ns reddit-sub-follower.reddit
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [reddit-sub-follower.configs :as configs]))

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
          resp (http/post "https://www.reddit.com/api/v1/access_token"
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

(defn get-new-posts
  "Fetches new Reddit posts, processes them, and returns the latest post ID."
  [{:keys [token username subreddit-name last-seen filter-fn output-fn]}]
  (letfn [(fetch
            ([] (fetch 0))
            ([num-retries]
             (try
               (let [url (format "https://oauth.reddit.com/r/%s/new" subreddit-name)
                     user-agent (mk-user-agent username)
                     headers {:user-agent user-agent}
                     resp (http/get url {:oauth-token (:access-token token)
                                         :headers headers
                                         :query-params {:before last-seen :limit 100}})
                     body (-> resp :body (json/parse-string true) :data)
                     new-last-seen (if-some [post (-> body :children first)]
                                     (-> post :data :name)
                                     last-seen)]
                 ;; Process posts in chronological order (oldest to newest)
                 (doseq [post (map :data (-> body :children reverse))]
                   (when (filter-fn (:title post))
                     (output-fn post)))
                 ;; Return the ID of the newest post for the next iteration
                 new-last-seen)
               (catch java.io.IOException e
                 (log/errorf "caught connection exception: %s\n" (.getMessage e))
                 (if (< num-retries 3)
                   (do (log/error "retrying...\n") (fetch (+ 1 num-retries)))
                   ;; On multiple errors, return the old last-seen ID to retry from the same point
                   last-seen)))))]
        (fetch)))
