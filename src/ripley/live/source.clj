(ns ripley.live.source
  "Convert to source"
  (:require [ripley.live.protocols :as p]
            ripley.live.atom
            ripley.live.async))

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
