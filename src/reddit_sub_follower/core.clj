(ns reddit-sub-follower.core
  (:gen-class)
  (:require
    [taoensso.timbre :as log]
    [reddit-sub-follower.logging :as logging]
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
                (log/error {:event :post_notification_failed
                            :subreddit subreddit-name
                            :post-id (:name post)
                            :error-message (.getMessage e)
                            :error-details (ex-data e)}))))]
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
                (log/error {:event :subreddit_scrape_failed
                            :subreddit subreddit-name
                            :error-message (.getMessage e)
                            :error-details (ex-data e)})
                token)))
          token
          configs/subreddit-names))

(defn prune-seen-posts! []
  (let [deleted-count (db/prune-seen-posts! configs/seen-posts-retention-days)]
    (when (pos? deleted-count)
      (log/info {:event :seen_posts_pruned
                 :deleted-count deleted-count
                 :retention-days configs/seen-posts-retention-days
                 :database-backend configs/database-backend}))
    deleted-count))

(defn maybe-run-seen-posts-cleanup [last-cleanup-ms]
  (let [now-ms (System/currentTimeMillis)
        cleanup-interval-ms (* 1000 configs/seen-posts-cleanup-interval-secs)
        should-cleanup? (or (nil? last-cleanup-ms)
                            (>= (- now-ms last-cleanup-ms) cleanup-interval-ms))]
    (if should-cleanup?
      (do
        (prune-seen-posts!)
        now-ms)
      last-cleanup-ms)))

(defn -main
  [& args]
  (logging/setup-logging!)
  (configs/validate-configs!)
  (let [reddit-token (reddit/mk-token)
        output-fn #(discord/send-webhook! configs/discord-webhook-url (discord/msg-formatter %))
        run-once? (some #{"--once" "-1"} args)]
    (db/init-db!)
    (if run-once?
      (do
        (maybe-run-seen-posts-cleanup nil)
        (scrape-once reddit-token output-fn))
      (loop [token reddit-token
             last-cleanup-ms nil]
        (let [last-cleanup-ms (maybe-run-seen-posts-cleanup last-cleanup-ms)
              next-token (scrape-once token output-fn)]
          (Thread/sleep configs/scrape-interval-ms)
          (recur next-token last-cleanup-ms))))))
