(ns ripley.live.atom
  "Live component that tracks an atom's value"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go >!! >!]]))

(defrecord AtomSource [key source-atom channel]
  p/Source
  (to-channel [_] channel)
  (immediate? [_] true)
  (close! [_]
    (remove-watch source-atom key)
    (async/close! channel)))

(defn atom-source
  "Create a source that tracks changes made to the given atom.
  The source will return the same channel on each call to to-channel
  so the same source can't be used for multiple live components."
  ([source-atom]
   (atom-source source-atom nil))
  ([source-atom process-value]
   (let [key (java.util.UUID/randomUUID)
         ch (async/chan 1
                        (when process-value
                          (map process-value)))]
     (add-watch source-atom key
                (fn [_ _ old-value new-value]
                  (when (not= old-value new-value)
                    (go
                      (>! ch new-value)))))
     (>!! ch @source-atom)
     (->AtomSource key source-atom ch))))
