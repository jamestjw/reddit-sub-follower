(ns reddit-sub-follower.core
  (:gen-class)
  (:require
   [taoensso.timbre :as log]
   [reddit-sub-follower.discord :as discord]
   [reddit-sub-follower.reddit :as reddit]
   [reddit-sub-follower.configs :as configs]
   [reddit-sub-follower.db :as db]))

(import '[java.time Instant Duration])

(defn no-update-for-too-long? [subreddit-name]
  (let [updated-at (db/get-updated-at-for-subreddit subreddit-name)]
    (if-not updated-at
      false
      (let [reset-time (.minus (Instant/now) (Duration/ofSeconds configs/no-data-reset-interval-secs))]
        (.isBefore updated-at reset-time)))))

(defn do_one_subreddit [token subreddit-name output-fn]
  (letfn [(post-filter [id title]
            (and (configs/scrape-query-filter title)
                 (not (db/post-seen? id subreddit-name))))
          (handle-post [post]
            (try
              (output-fn post)
              (db/add-seen-post! (:name post) subreddit-name)
              (catch Exception e
                (log/error "Failed to process post notification"
                           {:event :post_notification_failed
                            :subreddit subreddit-name
                            :post-id (:name post)
                            :message (.getMessage e)
                            :details (ex-data e)}))))]
    (let [prev-last-seen
          (if (no-update-for-too-long? subreddit-name)
            nil ; Try fetching latest
            (db/get-last-seen-for-subreddit subreddit-name))
          [token new-last-seen]
          (reddit/get-new-posts
           {:token token
            :username configs/reddit-username
            :subreddit-name subreddit-name
            :last-seen prev-last-seen
            :filter-fn post-filter
            :output-fn handle-post})]
      (when (not= prev-last-seen new-last-seen)
        (db/update-last-seen! subreddit-name new-last-seen))
      token)))

(defn scrape-once [token output-fn]
  (reduce (fn [token subreddit-name]
            (try
              (do_one_subreddit token subreddit-name output-fn)
              (catch Exception e
                (log/error "Failed to scrape"
                           {:event :subreddit_scrape_failed
                            :subreddit subreddit-name
                            :message (.getMessage e)
                            :details (ex-data e)})
                token)))
          token
          configs/subreddit-names))

(defn -main
  [& args]
  (configs/validate-configs!)
  (let [reddit-token (reddit/mk-token)
        output-fn #(discord/send-webhook! configs/discord-webhook-url (discord/msg-formatter %))
        run-once? (some #{"--once" "-1"} args)]
    (db/init-db!)
    (if run-once?
      (scrape-once reddit-token output-fn)
      (loop [token reddit-token]
        (let [next-token (scrape-once token output-fn)]
          (Thread/sleep configs/scrape-interval-ms)
          (recur next-token))))))
