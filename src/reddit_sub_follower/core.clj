(ns reddit-sub-follower.core
  (:gen-class)
  (:require
   [clojure.core.async    :as async]
   [discljord.connections :as conn]
   [discljord.messaging   :as msg]
   [taoensso.timbre :as log]
   [reddit-sub-follower.reddit :as reddit]
   [reddit-sub-follower.configs :as configs]
   [clojure.edn :as edn]))

(def discord-intents #{:guilds :guild-messages})

(defn discord-msg-formatter [post]
  (let [title (:title post)
        link (str "https://www.reddit.com" (:permalink post))]
    (format "%s\n%s" title link)))

(defn read-last-seen-file [filename]
  (try
    (-> filename slurp edn/read-string)
    (catch Exception _ {})))

(defn do_one_subreddit [token last-seen subreddit-name output-fn]
  (let [[token subreddit-last-seen]
        (reddit/get-new-posts
         {:token token
          :username configs/reddit-username
          :subreddit-name subreddit-name
          :last-seen (get last-seen subreddit-name)
          :filter-fn configs/scrape-filter
          :output-fn output-fn})
        last-seen (assoc last-seen subreddit-name
                         subreddit-last-seen)]
    (spit configs/last-seen-file last-seen)
    [token last-seen]))

(defn -main
  [& args] ; The `& args` allows your program to accept command-line arguments
  (let [event-ch      (async/chan 100)
        connection-ch (conn/connect-bot! configs/discord-token event-ch :intents discord-intents)
        message-ch    (msg/start-connection! configs/discord-token)
        reddit-token (reddit/mk-token)
        output-fn #(msg/create-message! message-ch configs/discord-channel-id :content (discord-msg-formatter %))
        initial-last-seen (read-last-seen-file configs/last-seen-file)]
    (try
      (loop [token reddit-token
             last-seen initial-last-seen]
        (let [[token last-seen]
              (reduce (fn [[token last-seen] subreddit-name]
                        (do_one_subreddit token last-seen subreddit-name output-fn))
                      [token last-seen] configs/subreddit-names)]
          (Thread/sleep configs/scrape-interval-ms)
          (recur token last-seen)))
      (finally
        (msg/stop-connection! message-ch)
        (conn/disconnect-bot!  connection-ch)
        (async/close!           event-ch)))))
