(ns ripley.integration.manifold
  "Integrate manifold deferreds and streams into ripley sources.

  Requires dependency:
  manifold/manifold {:mvn/version \"0.1.9-alpha4\"}"
  (:require [manifold.deferred :as deferred]
            [manifold.stream :as stream]
            [ripley.live.source :as source]
            [ripley.live.protocols :as p]))

(defn deferred-source
  "Create new source from a manifold deferred value.

  Optional options can have:
  :error-value-fn  if provided transform error into a value and send
                   that to the source as well.
                   By default errors are not sent.
  "
  ([d] (deferred-source {} d))
  ([{:keys [error-value-fn]} d]
   (let [[source _] (source/source-with-listeners
                     #(when (deferred/realized? d)
                        @d))]
     (deferred/on-realized
       d
       #(p/write! source %)
       (fn [e]
         (when error-value-fn
           (p/write! source (error-value-fn e)))))
     source)))

(defn stream-source
  "Create new source that consumes values from manifold stream.
  "
  ([s] (stream-source {} s))
  ([_opts s]
   (let [[source _]
         (source/source-with-listeners
          #(deref (stream/try-take! s 0)))
         consumed (stream/consume
                   #(p/write! source %) s)]
     (deferred/chain consumed (fn [_]
                                (p/close! source)))
     source)))
