(ns reddit-sub-follower.core
  (:gen-class)
  (:require
   [clojure.core.async    :as async]
   [discljord.connections :as conn]
   [discljord.messaging   :as msg]
   [reddit-sub-follower.reddit :as reddit]
   [reddit-sub-follower.configs :as configs]
   [reddit-sub-follower.db :as db]))

(def discord-intents #{:guilds :guild-messages})

(defn discord-msg-formatter [post]
  (let [title (:title post)
        link (str "https://www.reddit.com" (:permalink post))]
    (format "%s\n%s" title link)))

(defn do_one_subreddit [token subreddit-name output-fn]
  (let [prev-last-seen (db/get-last-seen-for-subreddit subreddit-name)
        [token new-last-seen]
        (reddit/get-new-posts
         {:token token
          :username configs/reddit-username
          :subreddit-name subreddit-name
          :last-seen prev-last-seen
          :filter-fn configs/scrape-filter
          :output-fn output-fn})]
    (when (not= prev-last-seen new-last-seen)
      (db/update-last-seen! subreddit-name new-last-seen))
    token))

(defn -main
  [& args] ; The `& args` allows your program to accept command-line arguments
  (let [event-ch      (async/chan 100)
        connection-ch (conn/connect-bot! configs/discord-token event-ch :intents discord-intents)
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
