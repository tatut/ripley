(ns ripley.impl.dynamic
  "Dynamically bound stuff. Should not need to be used directly from app code.")

(def ^:dynamic *live-context* nil)
(def ^:dynamic *component-id* nil)

;; Bound to an atom when live components render that contains
;; the components id. The first element will consume
;; the id and reset this to nil.
(def ^:dynamic *output-component-id* nil)

(defn consume-component-id!
  "Called by HTML rendering to get live id for currently rendering component."
  []
  (when *output-component-id*
    (let [id @*output-component-id*]
      (reset! *output-component-id* nil)
      id)))

(defmacro with-component-id [id & body]
  `(let [id# ~id]
     (binding [*component-id* id#
               *output-component-id* (atom id#)]
       ~@body)))
