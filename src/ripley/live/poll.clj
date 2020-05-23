(ns ripley.live.poll
  "A polling source."
  (:require [clojure.core.async :as async :refer [go-loop >! timeout]]
            [ripley.live.protocols :as p]))

(defn poll-source [interval-ms poll-fn]
  (let [ch (async/chan)
        close-ch (async/chan)]
    (go-loop [[_ port] (async/alts! [(timeout interval-ms) close-ch])]
      (if (= port close-ch)
        (async/close! ch)
        (do (>! ch (poll-fn))
            (recur (async/alts! [(timeout interval-ms) close-ch])))))
    (reify p/Source
      (to-channel [_] ch)
      (immediate? [_] true)
      (close! [_] (async/close! close-ch)))))
