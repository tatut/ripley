(ns ripley.js
  "JavaScript helpers")

(defrecord JSCallback [callback-fn condition js-params])

(defn js
  "Create a JavaScript callback that evaluates JS in browser to get parameters"
  [callback-fn & js-params]
  (->JSCallback callback-fn nil js-params))

(defn js-when
  "Create a conditionally fired JS callback."
  [js-condition callback-fn & js-params]
  (->JSCallback callback-fn js-condition js-params))

(def enter-pressed?
  "JS code for checking if keypress event is enter"
  "window.event.keyCode == 13")

(def change-value
  "JavaScript for current on-change event value"
  "window.event.target.value")

(def keycode
  "JavaScript for current event keycode"
  "window.event.keyCode")

(def keycode-char
  "String.fromCharCode(window.event.keyCode)")

(defn input-value
  "Generate JS for getting the value of an input field by id."
  [id]
  (str "document.getElementById('" (if (keyword? id)
                                     (name id)
                                     id) "').value"))
