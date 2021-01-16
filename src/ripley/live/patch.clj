(ns ripley.live.patch
  "Define patch methods components can use and how they are encoded in messages
  and evaluated."
  (:refer-clojure :exclude [replace])
  (:require [ripley.impl.dynamic :as dynamic]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defmulti js-eval-script identity)
(defmulti make-patch "Create a patch message by keyword name"
  (fn [patch-method _id & [_payload]]
    patch-method))

(defmacro define-patch-method [name doc {:keys [type js-eval
                                                               payload?]
                                                        :or {payload? true}}]
  (let [pl (when payload? ['payload])]
    `(do
       (defmethod js-eval-script
         ~type [_#]
         ~js-eval)

       (defmethod make-patch ~(keyword name) [_# id# & [~'payload]]
         ~@(when payload?
             [`(assert ~'payload ~(str "Patch method " (keyword name)
                                       " (" type ") requires a payload"))])
         [id# ~type ~@(when payload?
                        ['payload])])

       (defn ~name
         ~doc
         ([~@pl]
          (~name dynamic/*component-id* ~@pl))
         ([component-id# ~@pl]
          [component-id# ~type ~@pl])))))

(define-patch-method replace
  "Replace the whole element's HTML content."
  {:type "R"
   :js-eval "elt.outerHTML = payload;"})

(define-patch-method append
  "Append HTML to the end of element."
  {:type "A"
   :js-eval "elt.innerHTML += payload;"})

(define-patch-method prepend
  "Prepend HTML to the start of element."
  {:type "P"
   :js-eval "elt.innerHTML = payload + elt.innerHTML;"})

(define-patch-method delete
  "Delete element."
  {:type "D" :payload? false
   :js-eval "elt.parentElement.removeChild(elt);"})

(define-patch-method insert-after
  "Insert HTML after the end of component."
  {:type "F"
   :js-eval "elt.insertAdjacentHTML(\"afterend\",payload);"})

(define-patch-method move-after
  "Move existing live component after the end of component."
  {:type "M"
   :js-eval "elt.insertAdjacentElement(\"afterend\",document.getElementById(\"__rl\"+payload));"})

(define-patch-method set-attributes
  "Set element attributes."
  {:type "@"
   :js-eval "for(var attr in attrs) { elt.settAttribute(attr,attrs[attr]) }"})

(define-patch-method eval-js
  "Eval js with 'this' bound to the live component element"
  {:type "E"
   :js-eval "(new Function(payload)).call(elt);"})

(def live-client-script
  (delay
    (-> "live-client-template.js" io/resource slurp
        (str/replace
         "__PATCH__"
         (str "switch(payload) {\n"
              (str/join "\n"
                        (for [type (keys (methods js-eval-script))]
                          (str "case \"" type "\":" (js-eval-script type) " break;")))
              "\ndefault: console.error(\"Unrecognized patch method: \", method);"
              "\n}")))))
