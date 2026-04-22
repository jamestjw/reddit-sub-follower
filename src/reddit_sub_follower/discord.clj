(ns reddit-sub-follower.discord
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [taoensso.timbre :as log]))

(def webhook-max-attempts 5)
(def webhook-initial-backoff-ms 500)
(def webhook-max-backoff-ms 5000)

(defn- parse-json-safe [s]
  (try
    (json/parse-string s true)
    (catch Exception _ nil)))

(defn- retry-after-ms [body]
  (let [parsed (parse-json-safe body)
        retry-after (:retry_after parsed)]
    (when (number? retry-after)
      (long (Math/ceil (* 1000.0 retry-after))))))

(defn msg-formatter [post]
  (let [title (:title post)
        link (str "https://www.reddit.com" (:permalink post))]
    (format "%s\n%s" title link)))

(defn send-webhook! [webhook-url message]
  (loop [attempt 1
         backoff-ms webhook-initial-backoff-ms]
    (let [resp (http/post webhook-url
                          {:headers {"Content-Type" "application/json"}
                           :body (json/generate-string {:content message})
                           :throw-exceptions false})
          status (:status resp)
          body (:body resp)]
      (if (<= 200 status 299)
        true
        (let [retry-after-ms (when (= 429 status) (retry-after-ms body))
              should-retry? (and (< attempt webhook-max-attempts)
                                 (or (= 429 status)
                                     (<= 500 status 599)))]
          (if should-retry?
            (let [sleep-ms (if retry-after-ms
                             (+ retry-after-ms 50)
                             backoff-ms)]
              (Thread/sleep sleep-ms)
              (recur (inc attempt)
                     (min webhook-max-backoff-ms (* 2 backoff-ms))))
            (let [error-data {:event :discord_webhook_delivery_failed
                              :status status
                              :attempt attempt
                              :max-attempts webhook-max-attempts
                              :body body
                              :parsed-body (parse-json-safe body)}]
              (log/error "Discord webhook delivery failed after retries" error-data)
              (throw (ex-info "Discord webhook request failed" error-data)))))))))
