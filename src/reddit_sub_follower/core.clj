(ns reddit-sub-follower.core
  (:gen-class)
  (:require
   [clojure.core.async    :as async]
   [discljord.connections :as conn]
   [discljord.messaging   :as msg]
   [reddit-sub-follower.reddit :as reddit]
   [reddit-sub-follower.configs :as configs]
   [reddit-sub-follower.db :as db]))

(import '[java.time Instant Duration])

(defn discord-msg-formatter [post]
  (let [title (:title post)
        link (str "https://www.reddit.com" (:permalink post))]
    (format "%s\n%s" title link)))

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
            (do (output-fn post)
                (db/add-seen-post! (:name post) subreddit-name)))]
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

(defn -main
  [& args] ; The `& args` allows your program to accept command-line arguments
  (configs/validate-configs!)
  (let [event-ch      (async/chan 100)
        connection-ch (conn/connect-bot! configs/discord-token event-ch :intents configs/discord-intents)
        message-ch    (msg/start-connection! configs/discord-token)
        reddit-token (reddit/mk-token)
        output-fn #(msg/create-message! message-ch configs/discord-channel-id :content (discord-msg-formatter %))]
    (db/init-db!)

    (try
      (loop [token reddit-token]
        (let [token
              (reduce (fn [token subreddit-name]
                        (do_one_subreddit token subreddit-name output-fn))
                      token configs/subreddit-names)]
          (Thread/sleep configs/scrape-interval-ms)
          (recur token)))
      (finally
        (msg/stop-connection! message-ch)
        (conn/disconnect-bot!  connection-ch)
        (async/close!           event-ch)))))
