(ns ripley.live.source
  "Working with Sources and converting to Source.

  Source is the main abstraction in ripley that provides
  a way to listen for new changes in a live value.

  Source can be anything that has a value and a way to get
  changes."
  (:require [ripley.live.protocols :as p]
            ripley.live.atom
            ripley.live.async
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defmulti to-source type)

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

(defrecord ComputedSource [f initial-value unlisten-sources input-values listeners]
  p/Source
  (current-value [_] (apply f @input-values))
  (listen! [this listener]
    ;; Call listener immediately with the value, if the value
    ;; is different from the initial value
    (let [cv (p/current-value this)]
      (when (not= initial-value cv)
        (listener cv)))
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
        initial-value (apply f @input-values)
        update! (fn [i value]
                  (let [old-value (apply f @input-values)
                        new-input-values (swap! input-values assoc i value)
                        new-value (apply f new-input-values)]
                    (when (not= old-value new-value)
                      (doseq [listener @listeners]
                        (listener new-value)))))]

    (->ComputedSource
     f
     initial-value
     ;; Add listeners for all input sources
     (doall
      (map-indexed (fn [i s]
                     (p/listen! s (partial update! i)))
                   sources))
     input-values
     listeners)))

(defmacro c=
  "Short hand form for creating computed sources.
  Takes form to compute. Symbols beginning with % refer
  to input sources taken from environment.

  Example:
  ;; Computed source that has two sources: a and b
  ;; and calculates (* a b) whenever either  input
  ;; source changes.
  (c= (* %a %b))
  "
  [form]
  (let [inputs (volatile! {})]
    (walk/prewalk
     (fn [x]
       (when (and (symbol? x)
                  (str/starts-with? (name x) "%"))
         (vswap! inputs assoc x (symbol (subs (name x) 1))))
       x)
     form)
    (let [inputs @inputs]
      (assert (seq inputs)
              "No inputs for computed source.")
      `(computed (fn [~@(map first inputs)]
                   ~form)
                 ~@(map second inputs)))))

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

(defrecord SplitSource [parent unlisten keyset listeners]
  p/Source
  (current-value [_]
    (select-keys (p/current-value parent) keyset))
  (listen! [_ listener]
    (swap! listeners conj listener)
    #(swap! listeners disj listener))
  (close! [_]
    (unlisten)))

(defn split
  "Split a source into multiple subsources based on keysets.
  Returns a vector of subsources in the same order as the
  keysets.

  This can be used to get more granular updates.

  The split sources will update only when the keys selected
  by it are changed in the parent source. The value of the
  parent source should be a map.
  "
  [parent-source & keysets]
  (let [parent-source (source parent-source)]
    (mapv
     (fn [keyset]
       (let [listeners (atom #{})
             unlisten (listen-with-previous!
                       parent-source
                       (fn [old-value new-value]
                         (let [old-value (select-keys old-value keyset)
                               new-value (select-keys new-value keyset)]
                           (when (not= old-value new-value)
                             (doseq [listener @listeners]
                               (listener new-value))))))]
         (->SplitSource parent-source unlisten keyset listeners)))
     keysets)))

(defn split-key
  "Like [[split]] but returns source that takes a single key from parent source."
  [parent-source key]
  (c= (get %parent-source key)))


(defrecord SourceWithListeners [listeners current-value-fn cleanup-fn has-listener?]
  p/Source
  (current-value [_]
    (current-value-fn))
  (listen! [_ listener]
    (deliver has-listener? true)
    (swap! listeners conj listener)
    #(swap! listeners disj listener))
  (close! [_]
    (reset! listeners #{})
    (when cleanup-fn
      (cleanup-fn)))
  p/Writable
  (write! [_ v]
    (doseq [listener @listeners]
      (listener v))))

(defn source-with-listeners
  "Create new source that tracks listeners in a new atom.
  Returns vector of [source listeners-atom has-listener-promise].

  Calling ripley.live.protocols/write! on this source will send
  the written value to all currently registered listeners.

  Meant for implementing new sources."
  ;; FIXME: don't return listeners, pass it to cleanup fn
  ([current-value-fn]
   (source-with-listeners current-value-fn nil))
  ([current-value-fn cleanup-fn]
   (let [listeners (atom #{})
         has-listener? (promise)
         source (->SourceWithListeners listeners
                                       current-value-fn
                                       cleanup-fn
                                       has-listener?)]
     [source listeners has-listener?])))

(defn use-state
  "Create a source for local (per page render) state.

  Returns a vector of [value-source set-state! update-state!] where
  the value-source is a source that can be used to read/listen
  to the value of the state and set-state! is a callback
  for setting the new state. The last update-state! callback
  is for applying an update to the current value (like `swap!`)


  This is meant to be used similar to hooks in some
  frontend frameworks."
  [initial-value]
  (let [state (make-array Object 1)
        [source _] (source-with-listeners #(aget state 0))]
    (aset state 0 initial-value)
    [source
     ;; Callback to set the value
     (fn set-state! [new-state]
       (locking state
         (let [old-state (aget state 0)]
           (when (not= old-state new-state)
             (aset state 0 new-state)
             (p/write! source new-state)))))

     ;; Callback to update the value
     (fn update-state! [update-fn & args]
       (locking state
         (let [old-state (aget state 0)
               new-state (apply update-fn old-state args)]
           (when (not= old-state new-state)
             (aset state 0 new-state)
             (p/write! source new-state)))))]))


(defn future-source
  "Source for future or promise.
  Optionally initial-state can be specified and that will
  be returned as the source value until the future computation
  is done."
  ([d] (future-source d nil))
  ([d initial-value]
   (let [[source _ has-listener?]
         (source-with-listeners
          #(if (realized? d)
             @d
             initial-value))]
     ;; Wait for future to complete in another thread and send value
     (future
       ;; Wait until there is someone listening to avoid losing update.
       ;; This avoids setting the value right after creation and
       ;; before any UI element has had time to add listeners.
       (when @has-listener?
         (p/write! source @d)))
     source)))

(def ^:private future-type (type (future 1)))
(def ^:private promise-type (type (promise)))

(defmethod to-source future-type [f]
  (future-source f))

(defmethod to-source promise-type [p]
  (future-source p))

(defn static
  "Source that does not change. It just holds the given data."
  [data]
  (reify p/Source
    (current-value [_] data)
    (listen! [_ listener]
      (listener data)
      (constantly nil))
    (close! [_]
      nil)))
