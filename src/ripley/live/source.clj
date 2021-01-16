(ns ripley.live.source
  "Convert to source"
  (:require [ripley.live.protocols :as p]
            ripley.live.atom
            ripley.live.async
            [clojure.core.async :as async :refer [go-loop <! >!]]))

(defmulti to-source class)

(defmethod to-source clojure.lang.Atom [a]
  (ripley.live.atom/atom-source a))

(defmethod to-source clojure.core.async.impl.channels.ManyToManyChannel [ch]
  (ripley.live.async/ch->source ch false))

(defn source
  "If x is a source, return it. Otherwise try to create a source from it."
  [x]
  (if (satisfies? p/Source x)
    x
    (to-source x)))

(defn map-source
  "Returns new source that whose value is processed with fun."
  [fun source]
  (let [orig-channel (p/to-channel source)
        ch (async/chan 1)]
    (go-loop [v (<! orig-channel)]
      (if (nil? v)
        (async/close! ch)
        (let [mapped-value (fun v)]
          (>! ch mapped-value)
          (recur (<! orig-channel)))))
    (reify p/Source
      (to-channel [_] ch)
      (immediate? [_] (p/immediate? source))
      (close! [_] (p/close! source)))))
