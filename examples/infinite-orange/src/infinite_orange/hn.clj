(ns infinite-orange.hn
  "Fetch HackerNews items from the API"
  (:require [org.httpkit.client :as client]
            [cheshire.core :as cheshire]
            [ripley.live.source :as source]
            [ripley.live.async :refer [ch->source]]
            [clojure.core.async :as async :refer [go-loop >! >!! <!]]))

(def base-url "https://hacker-news.firebaseio.com/v0/")

(defn- hn-get [& url]
  (-> (client/get (apply str base-url url))
      deref :body
      (cheshire/decode keyword)))

(defn top-stories []
  (hn-get "topstories.json"))

(defn item [id]
  (hn-get "item/" id ".json"))

(defn top-stories-batches [batch-size]
  (let [[first-batch & batches] (partition-all batch-size (top-stories))
        batches (atom batches)]
    (fn []
      (let [next-batch (first @batches)]
        (swap! batches rest)
        (mapv item next-batch)))))
