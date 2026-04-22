(ns reddit-sub-follower.db
  (:require [reddit-sub-follower.configs :as configs]
            [reddit-sub-follower.db.sqlite :as sqlite]
            [reddit-sub-follower.db.postgres :as postgres]))

(def ^:private implementations
  {"sqlite" {:init-db! sqlite/init-db!
             :load-all-last-seen sqlite/load-all-last-seen
             :get-last-seen-for-subreddit sqlite/get-last-seen-for-subreddit
             :get-updated-at-for-subreddit sqlite/get-updated-at-for-subreddit
             :update-last-seen! sqlite/update-last-seen!
             :add-seen-post! sqlite/add-seen-post!
             :post-seen? sqlite/post-seen?
             :prune-seen-posts! sqlite/prune-seen-posts!}
   "postgres" {:init-db! postgres/init-db!
               :load-all-last-seen postgres/load-all-last-seen
               :get-last-seen-for-subreddit postgres/get-last-seen-for-subreddit
               :get-updated-at-for-subreddit postgres/get-updated-at-for-subreddit
               :update-last-seen! postgres/update-last-seen!
               :add-seen-post! postgres/add-seen-post!
               :post-seen? postgres/post-seen?
               :prune-seen-posts! postgres/prune-seen-posts!}})

(defn- impl []
  (or (get implementations configs/database-backend)
      (throw (ex-info "Unsupported DATABASE_BACKEND"
                      {:database-backend configs/database-backend
                       :supported-backends (keys implementations)}))))

(defn init-db! []
  ((:init-db! (impl))))

(defn load-all-last-seen []
  ((:load-all-last-seen (impl))))

(defn get-last-seen-for-subreddit [subreddit-name]
  ((:get-last-seen-for-subreddit (impl)) subreddit-name))

(defn get-updated-at-for-subreddit [subreddit-name]
  ((:get-updated-at-for-subreddit (impl)) subreddit-name))

(defn update-last-seen! [subreddit-name last-seen-id]
  ((:update-last-seen! (impl)) subreddit-name last-seen-id))

(defn add-seen-post! [post-id subreddit-name]
  ((:add-seen-post! (impl)) post-id subreddit-name))

(defn post-seen? [post-id subreddit-name]
  ((:post-seen? (impl)) post-id subreddit-name))

(defn prune-seen-posts! [retention-days]
  ((:prune-seen-posts! (impl)) retention-days))
