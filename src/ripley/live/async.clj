(ns ripley.live.async
  "core.async source and pub sub"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go go-loop <! >!]]))

(defonce ^:private pubsub-ch (async/chan))
(defonce ^:private pubsub (async/pub pubsub-ch :event/topic))

(defn subscribe-source
  "Source that subscribes to the given topic and runs function to produce results.
  Optionally immediately processes an event."
  ([topics event-value-fn]
   (subscribe-source topics event-value-fn nil))
  ([topics event-value-fn immediate-event]
   (assert (or (keyword? topics)
               (and (coll? topics)
                    (every? keyword? topics)))
           "Topics must be a single keyword topic or collection of keyword topics")
   (let [ch (async/chan)
         sub-ch (async/chan)
         topics (if (keyword? topics)
                  [topics]
                  topics)]

     ;; If immediate use requested, send an empty event
     (when immediate-event
       (go
         (>! ch (<! (async/thread (event-value-fn immediate-event))))))

     (go-loop [in (<! sub-ch)]
       (when in
         (>! ch (<! (async/thread (event-value-fn in))))
         (recur (<! sub-ch))))

     (doseq [topic topics]
       (async/sub pubsub topic sub-ch))

     (reify p/Source
       (to-channel [_] ch)
       (immediate? [_] (boolean immediate-event))
       (close! [_]
         (doseq [topic topics]
           (async/unsub pubsub topic sub-ch))
         (async/close! sub-ch)
         (async/close! ch))))))

(defn publish [event-or-topic]
  (let [event
        (cond
          (keyword? event-or-topic)
          {:event/topic event-or-topic}

          (and (map? event-or-topic)
               (contains? event-or-topic :event/topic))
          event-or-topic

          :else (throw (ex-info "Publish must be called with map containing :event/topic or a keyword topic."
                                {:invalid-publish-value event-or-topic})))]
    (go (>! pubsub-ch event))))
