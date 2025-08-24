(ns reddit-sub-follower.reddit
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn mk-user-agent [username]
  (format "script:Get Subreddit:v1.1 (by /u/%s)" username))

(defn mk-oauth-url [client-id redirect-uri]
  (format "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=random_string&redirect_uri=%s&duration=permanent&scope=read"
          client-id redirect-uri))

(defrecord Token [access-token refresh-token])

(defn exchange-code-for-tokens [code client-id client-secret redirect-uri]
  (let [payload {:grant_type "authorization_code" :code code :redirect_uri redirect-uri}
        resp (http/post "https://www.reddit.com/api/v1/access_token"
                        {:form-params payload
                         :basic-auth [client-id client-secret]})
        body (->  resp
                  :body
                  (json/parse-string true))]
    (->Token (:access_token body) (:refresh_token body))))

; (defn obtain-oauth-code [client_id]
;   (let [url (format "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=random_string&redirect_uri=http://localhost&duration=permanent&scope=read" client_id)]))

(defn get-new-posts [token username subreddit-name last-seen filter-fn output-fn]
  (try
    (let [url (format "https://oauth.reddit.com/r/%s/new" subreddit-name)
          user-agent (mk-user-agent username)
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
        (when (filter-fn (:title post))
          (output-fn post)))
      last-seen)
    (catch java.net.ConnectException e
      (do
        (println (str "caught connection exception: " (.getMessage e)))
        last-seen))))

; (defn obtain-oauth-code [client_id]
;   (let [url (format "https://www.reddit.com/api/v1/authorize?client_id=%s&response_type=code&state=random_string&redirect_uri=http://localhost&duration=permanent&scope=read" client_id)]))
