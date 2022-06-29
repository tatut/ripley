(ns ripley.live.atom
  "Live component that tracks an atom's value"
  (:require [ripley.live.protocols :as p]
            [clojure.tools.logging :as log]))

(defrecord AtomSource [input-atom process-value key listeners]
  p/Source
  (current-value [_] (process-value @input-atom))
  (listen! [_ listener]
    (swap! listeners conj listener)
    #(swap! listeners disj listener))
  (close! [_]
    (reset! listeners #{})
    (remove-watch input-atom key))
  p/Writable
  (write! [_ new-value]
    (reset! input-atom new-value))
  Object
  (toString [_]
    (str "#<atom-source val: " @input-atom
         ", " (count @listeners) " listeners>")))

(defn atom-source
  "Create a source that tracks changes made to the given atom.
  The source will return the same channel on each call to to-channel
  so the same source can't be used for multiple live components."
  ([atom-or-opts]
   (let [{input-atom :atom
          :keys [process-value label]
          :or {process-value identity}}
         (if (map? atom-or-opts)
           atom-or-opts
           {:atom atom-or-opts})

         key (java.util.UUID/randomUUID)
         listeners (atom #{})
         update! (fn [old-value new-value]
                   (let [old (process-value old-value)
                         new (process-value new-value)]
                     (when (not= old new)
                       (doseq [listener @listeners]
                         (listener new)))))]

     (add-watch input-atom key
                (fn [_ _ old-value new-value]
                  (when (not= old-value new-value)
                    (when label
                      (log/debug label "atom-source changed, v:" new-value))
                    (update! old-value new-value))))

     (->AtomSource input-atom process-value key listeners)))
  ([atom process-value]
   (atom-source {:atom atom
                 :process-value process-value})))
