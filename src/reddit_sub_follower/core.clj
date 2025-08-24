(ns reddit-sub-follower.core
  (:gen-class)
  (:require
   [clojure.core.async    :as async]
   [discljord.connections :as conn]
   [discljord.messaging   :as msg]
   [taoensso.timbre :as log]
   [reddit-sub-follower.utils :as utils]
   [reddit-sub-follower.reddit :as reddit]
   [reddit-sub-follower.configs :as configs]))

(def discord-intents #{:guilds :guild-messages})

(defn discord-msg-formatter [post]
  (let [title (:title post)
        link (str "https://www.reddit.com" (:permalink post))]
    (format "%s\n%s" title link)))

(defn -main
  [& args] ; The `& args` allows your program to accept command-line arguments
  (let [event-ch      (async/chan 100)
        connection-ch (conn/connect-bot! configs/discord-token event-ch :intents discord-intents)
        message-ch    (msg/start-connection! configs/discord-token)
        reddit-token (reddit/mk-token)
        output-fn #(msg/create-message! message-ch configs/discord-channel-id :content (discord-msg-formatter %))
        initial-last-seen (utils/read-first-line configs/last-seen-file)]
    (try
      (loop [token reddit-token
             last-seen initial-last-seen]
        (let [[token last-seen] (reddit/get-new-posts
                                 {:token token
                                  :username configs/reddit-username
                                  :subreddit-name configs/subreddit-name
                                  :last-seen last-seen
                                  :filter-fn configs/scrape-filter
                                  :output-fn output-fn})]
          (log/info "Last seen:" last-seen)
          (spit configs/last-seen-file last-seen)
          (Thread/sleep configs/scrape-interval-ms)
          (recur token last-seen)))
      (finally
        (msg/stop-connection! message-ch)
        (conn/disconnect-bot!  connection-ch)
        (async/close!           event-ch)))))
