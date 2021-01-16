(ns ripley.live.atom
  "Live component that tracks an atom's value"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go >!! >!]]
            [taoensso.timbre :as log]))

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
  ([atom-or-opts]
   (let [{:keys [atom process-value label]}  (if (map? atom-or-opts)
                                              atom-or-opts
                                              {:atom atom-or-opts})
         key (java.util.UUID/randomUUID)
         ch (async/chan 1
                        (when process-value
                          ;; When process value is specified we must also
                          ;; deduplicate as the processed value may not
                          ;; change even if atom value changes and we
                          ;; don't want to rerender then.
                          (comp (map process-value)
                                (dedupe)))
                        (fn [ex]
                          (log/warn ex "Exception in atom source channel")))]
     (add-watch atom key
                (fn [_ _ old-value new-value]
                  (when (not= old-value new-value)
                    (when label
                      (log/debug label "atom-source changed, v:" new-value))
                    (go
                      (>! ch new-value)))))
     (>!! ch @atom)
     (->AtomSource key atom ch)))
  ([atom process-value]
   (atom-source {:atom atom
                 :process-value process-value})))
