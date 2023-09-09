(ns ripley.js
  "JavaScript helpers"
  (:require [ripley.live.protocols :as p]
            [ripley.impl.dynamic :as dyn]
            [ripley.html :as h]
            [clojure.string :as str]
            [ripley.impl.util :refer [arity]]))



(defn- wrap-success [fun]
  (case (arity fun)
    1 (fn [a] {:ripley/success (fun a)})
    2 (fn [a b] {:ripley/success (fun a b)})
    3 (fn [a b c] {:ripley/success (fun a b c)})
    4 (fn [a b c d] {:ripley/success (fun a b c d)})
    5 (fn [a b c d e] {:ripley/success (fun a b c d e)})
    6 (fn [a b c d e f] {:ripley/success (fun a b c d e f)})
    7 (fn [a b c d e f g] {:ripley/success (fun a b c d e f g)})
    (fn [& args]
      {:ripley/success (apply fun args)})))

(defn- wrap-failure-call [fun args]
  (try
    (apply fun args)
    (catch Throwable t
      (throw (ex-info (.getMessage t)
                      (merge (ex-data t)
                             {:ripley/failure true}))))))

(defn- wrap-failure [fun]
  (case (arity fun)
    1 (fn [a] (wrap-failure-call fun [a]))
    2 (fn [a b] (wrap-failure-call fun [a b]))
    3 (fn [a b c] (wrap-failure-call fun [a b c]))
    4 (fn [a b c d] (wrap-failure-call fun [a b c d]))
    5 (fn [a b c d e] (wrap-failure-call fun [a b c d e]))
    6 (fn [a b c d e f] (wrap-failure-call fun [a b c d e f]))
    7 (fn [a b c d e f g] (wrap-failure-call fun [a b c d e f g]))
    (fn [& args] (wrap-failure-call fun args))))

;; For cases there exists a failure handler, but no success handler
;; and the callback doesn't fail... we still need to notify client
;; so that it removes the handler from its state.
(defn wrap-ignore-success [fun]
  (fn [& args]
    (apply fun args)
    {:ripley/success true}))

(defrecord JSCallback [callback-fn condition js-params debounce-ms
                       on-success on-failure]
  p/Callback
  (callback-js-params [_] js-params)
  (callback-fn [_]
    (cond-> callback-fn
      on-success
      (wrap-success)

      on-failure
      (wrap-failure)

      (and on-failure (not on-success))
      (wrap-ignore-success)))
  (callback-debounce-ms [_] debounce-ms)
  (callback-condition [_] condition)
  (callback-on-success [_] on-success)
  (callback-on-failure [_] on-failure))


(defn js
  "Create a JavaScript callback that evaluates JS in browser to get parameters"
  [callback-fn & js-params]
  (map->JSCallback {:callback-fn callback-fn :js-params js-params}))

(defn js-when
  "Create a conditionally fired JS callback."
  [js-condition callback-fn & js-params]
  (map->JSCallback {:callback-fn callback-fn
                    :condition js-condition
                    :js-params js-params}))


(defn js-debounced
  "Create callback that sends value only when parameters settle (aren't
  changed within given ms). This is useful for input values to prevent
  sending on each keystroke, only when user stops typing."
  [debounce-ms callback-fn & js-params]
  (map->JSCallback {:callback-fn callback-fn
                    :js-params js-params
                    :debounce-ms debounce-ms}))

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

(defn form-values
  "Extract form values as a map of key/value."
  [form-selector]
  (str "(()=>{"
       "let d = {};"
       "for(const e of new FormData(document.querySelector('" form-selector "')).entries())"
       "d[e[0]]=e[1];"
       "return d;"
       "})()"))

(defn eval-js-from-source
  "Output a script element that evaluates JS code from source.
  Evaluates JS whenever source changes. The source values must be valid
  scripts."
  [source]
  (let [id (p/register! dyn/*live-context* source
                        identity
                        {:patch :eval-js})]
    (h/html [:script {:data-rl id}])))

(defn- with [callback field value]
  (map->JSCallback (merge (if (fn? callback)
                            {:callback-fn callback}
                            callback)
                          {field value})))
(defn on-success
  "Add JS code that is run after the callback is processed on the server."
  [callback on-success-js]
  {:pre [(string? on-success-js)]}
  (with callback :on-success on-success-js))

(defn on-failure
  "Add JS code that handles callback failure."
  [callback on-failure-js]
  {:pre [(string? on-failure-js)]}
  (with callback :on-failure on-failure-js))

(defn export-callbacks
  "Output a JS script tag that exposes the given callbacks as global
  JS functions or inside a global object.

  Names must be keywords denoting valid JS function names.

  eg. `(export-callbacks {:foo #(println \"Hello\" %)})`
  will create a JS function called `foo` that takes 1 argument
  and invokes the server side callback with it.
  "
  ([js-name->callback] (export-callbacks nil js-name->callback))
  ([object-name js-name->callback]
   (h/out! "<script>\n")
   (when object-name
     (h/out! (name object-name) "={"))
   (doall
    (map-indexed
     (fn [i [js-name callback]]

       (let [fn-name (name js-name)
             callback-fn (cond
                           (fn? callback) callback
                           (satisfies? p/Callback callback)
                           (p/callback-fn callback)

                           :else (throw (ex-info "Must be function or Callback record"
                                                 {:unexpected-callback callback})))
             argc (arity callback-fn)
             args (map #(str (char (+ 97 %))) (range argc))

             cb (if (fn? callback)
                  (apply js callback args)
                  (assoc callback :js-params args))]
         (if object-name
           (h/out! (when (pos? i) ",") "\n"
                   fn-name ": function(" (str/join "," args) "){"
                   (h/register-callback cb)
                   "}")
           (h/out! "function " fn-name "(" (str/join "," args) "){"
                   (h/register-callback cb)
                   "}\n"))))
     js-name->callback))
   (h/out! (when object-name "}") "</script>")))
