(ns ripley.integration.redis
  "Integrate Redis publish/subscribe to ripley source"
  (:require [taoensso.carmine :as redis]
            [ripley.live.source :as source]))

(defn- default-parse-message [_chan msg] msg)

(defn pubsub-listener-source
  "Create a source that listens to one or more channels.
  Channels can be a string (one channel name) or a collection
  of strings (multiple channels)

  Opts can have the following options
  :conn          connection spec for Carmine redis client
  :parse-message function to parse message into a source value
                 takes 2 arguments: channel name and message payload string
                 by default just returns payload."
  ([channels]
   (pubsub-listener-source {} channels))
  ([{:keys [conn parse-message]
     :or {conn {}
          parse-message default-parse-message}} channels]
   (assert (or (string? channels)
               (and (coll? channels)
                    (seq channels)
                    (every? string? channels)))
           "Channels must be a string or a collection of strings denoting channel names.")
   (let [channels (if (coll? channels)
                    channels
                    [channels])
         listener (atom nil)
         [source listeners] (source/source-with-listeners
                             (constantly nil)
                             #(.close ^java.io.Closeable @listener))
         on-message (fn [[message chan payload]]
                      (when (= message "message")
                        (let [value (parse-message chan payload)]
                          (doseq [listener @listeners]
                            (listener value)))))]
     (reset! listener (redis/with-new-pubsub-listener
                        conn
                        (zipmap channels (repeat on-message))
                        (doseq [ch channels]
                          (redis/subscribe ch))))
     source)))
