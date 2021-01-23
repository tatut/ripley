(ns ripley.live.source
  "Working with Sources and converting to Source.

  Source is the main abstraction in ripley that provides
  a way to listen for new changes in a live value.

  Source can be anything that has a value and a way to get
  changes."
  (:require [ripley.live.protocols :as p]
            ripley.live.atom
            ripley.live.async
            [clojure.core.async :as async :refer [go-loop <! >!]]
            [clojure.string :as str]))

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

(defrecord ComputedSource [f unlisten-sources input-values listeners]
  p/Source
  (current-value [_] (apply f @input-values))
  (listen! [_ listener]
    (swap! listeners conj listener)
    #(swap! listeners disj listener))
  (close! [_]
    (reset! listeners #{})
    ;; Don't close sources (they may have other listeners)
    ;; unregister our listeners from them
    (doseq [unlisten unlisten-sources]
      (unlisten)))

  Object
  (toString [_]
    (str "#<computed f: " f ", " (count unlisten-sources) " sources")))

(defn computed
  "Returns new source whose value is computed from the value of
  one or more other sources.

  sources are the sources to listen for changes.
  Calls ripley.live.source/source on all input sources so
  anything that can be converted into a source with
  ripley.live.source/to-source can be used as input.

  f is the function to call with the source values. If any
  of the sources changes, the function will be called.
  The function must have an arity that has the same amount
  of parameters as there are input sources.
  The function must be pure and non blocking.

  (computed + a b) will return a new source that
  calls + on the values of a and b sources when either
  one changes."
  [f & sources]
  (let [sources (mapv source sources)
        input-values (atom (mapv p/current-value sources))
        listeners (atom #{})

        update! (fn [i value]
                  (let [old-value (apply f @input-values)
                        new-input-values (swap! input-values assoc i value)
                        new-value (apply f new-input-values)]
                    (when (not= old-value new-value)
                      (doseq [listener @listeners]
                        (listener new-value)))))]

    ;; Add listeners for all input sources


    (->ComputedSource
     f
     (doall
      (map-indexed (fn [i s]
                     (p/listen! s (partial update! i)))
                   sources))
     input-values
     listeners)))

(defn listen-with-previous!
  "Utility for listening to a source while tracking the
  previous value as well.

  The given listener function is called with both the previous
  and the current values:

  (listener prev-value curr-value)"
  [source listener]
  (let [prev-value (atom (p/current-value source))]
    (p/listen! source
               (fn [curr-value]
                 (let [prev @prev-value]
                   (reset! prev-value curr-value)
                   (listener prev curr-value))))))
