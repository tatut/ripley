(ns ripley.live.poll
  "A polling source."
  (:require [clojure.core.async :as async :refer [go timeout]]
            [ripley.live.protocols :as p]))

(defn poll-source [interval-ms poll-fn]
  (let [listeners (atom #{})
        close-ch (async/chan)
        current-value (poll-fn)]

    (go
      (loop [old-value current-value
             [_ port] (async/alts! [(timeout interval-ms) close-ch])]
        (when (not= port close-ch)
          (let [new-value (poll-fn)]
            (if (not= old-value new-value)
              (do
                (doseq [listener @listeners]
                  (listener new-value))
                (recur new-value (async/alts! [(timeout interval-ms) close-ch])))
              (recur old-value (async/alts! [(timeout interval-ms) close-ch])))))))

    (reify p/Source
      (current-value [_] current-value)
      (listen! [_ listener]
        (swap! listeners conj listener)
        #(swap! listeners disj listener))
      (close! [_]
        (reset! listeners #{})
        (async/close! close-ch)))))
