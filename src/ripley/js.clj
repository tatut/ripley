(ns ripley.js
  "JavaScript helpers")

(defrecord JSCallback [callback-fn condition js-params debounce-ms])

(defn js
  "Create a JavaScript callback that evaluates JS in browser to get parameters"
  [callback-fn & js-params]
  (->JSCallback callback-fn nil js-params nil))

(defn js-when
  "Create a conditionally fired JS callback."
  [js-condition callback-fn & js-params]
  (->JSCallback callback-fn js-condition js-params nil))


(defn js-debounced
  "Create callback that sends value only when parameters settle (aren't
  changed within given ms). This is useful for input values to prevent
  sending on each keystroke, only when user stops typing."
  [debounce-ms callback-fn & js-params]
  (->JSCallback callback-fn nil js-params debounce-ms))

(defn keycode-pressed?
  "Return JS code for checking if keypress event has given keycode"
  [keycode]
  (str "window.event.keyCode == " keycode))

(def enter-pressed?
  "JS for checking if enter was pressed"
  (keycode-pressed? 13))

(def esc-pressed?
  "JS for checking if escape was pressed"
  (keycode-pressed? 27))

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

(def prevent-default
  "window.event.preventDefault()")
