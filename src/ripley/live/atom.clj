(ns ripley.live.atom
  "Live component that tracks an atom's value"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go >!! >!]]))

(defn atom-source [source-atom]
  (let [key (java.util.UUID/randomUUID)
        ch (async/chan (async/sliding-buffer 1))]
    (>!! ch @source-atom)
    (add-watch source-atom key
               (fn [_ _ old-value new-value]
                 (when (not= old-value new-value)
                   (go
                     (>! ch new-value)))))
    (reify p/Source
      (to-channel [_] ch)
      (immediate? [_] true)
      (close! [_]
        (remove-watch source-atom key)
        (async/close! ch)))))
