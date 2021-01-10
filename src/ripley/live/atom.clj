(ns ripley.live.atom
  "Live component that tracks an atom's value"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go >!! >!]]))

(defn atom-source [source-atom]
  (let [key (java.util.UUID/randomUUID)
        channels (atom #{})]

    (add-watch source-atom key
               (fn [_ _ old-value new-value]
                 (when (not= old-value new-value)
                   ;;(println source-atom " value: " old-value " => " new-value)
                   (go
                     (doseq [ch @channels]
                       (>! ch new-value))))))
    (reify p/Source
      (to-channel [_]
        (let [ch (async/chan 1)]
          (>!! ch @source-atom)
          (swap! channels conj ch)
          ch))
      (immediate? [_] true)
      (close! [_]
        (remove-watch source-atom key)
        (doseq [ch @channels]
          (async/close! ch))))))
