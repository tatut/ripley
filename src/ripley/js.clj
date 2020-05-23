(ns ripley.js
  "JavaScript helpers")

(defrecord JSCallback [callback-fn js-params])

(defn js
  "Create a JavaScript callback that evaluates JS in browser to get parameters"
  [callback-fn & js-params]
  (->JSCallback callback-fn js-params))

(def change-value
  "JavaScript for current on-change event value"
  "window.event.target.value")

(defn input-value
  "Generate JS for getting the value of an input field by id."
  [id]
  (str "document.getElementById('" (if (keyword? id)
                                     (name id)
                                     id) "').value"))
