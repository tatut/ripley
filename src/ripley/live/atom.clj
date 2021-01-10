(ns ripley.live.atom
  "Live component that tracks an atom's value"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go >!! >!]]))

(defrecord AtomSource [source-atom channels process-value]
  p/Source
  (to-channel [_]
    (let [ch (async/chan 1
                         (when process-value
                           (map process-value)))]
      (>!! ch @source-atom)
      (swap! channels conj ch)
      ch))

  (immediate? [_] true)

  (close! [_]
    (remove-watch source-atom key)
    (doseq [ch @channels]
      (async/close! ch))))

(defn atom-source
  ([source-atom]
   (atom-source source-atom nil))
  ([source-atom process-value]
   (let [key (java.util.UUID/randomUUID)
         channels (atom #{})]
     (add-watch source-atom key
                (fn [_ _ old-value new-value]
                  (when (not= old-value new-value)
                    (go
                      (doseq [ch @channels]
                        (>! ch new-value))))))
     (->AtomSource source-atom channels process-value))))
