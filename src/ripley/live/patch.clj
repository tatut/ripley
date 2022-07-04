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
(defmulti target-parent? identity)
(defmulti render-mode identity)

(defmacro define-patch-method
  [name doc {:keys [type js-eval
                    payload? render-mode target-parent?]
             :or {payload? true
                  render-mode :html
                  target-parent? false}}]
  (let [pl (when payload? ['payload])
        kw-name (keyword name)]
    `(do
       (defmethod js-eval-script
         ~type [_#]
         ~js-eval)

       (defmethod make-patch ~kw-name [_# id# & [~'payload]]
         ~@(when payload?
             [`(assert ~'payload ~(str "Patch method " (keyword name)
                                       " (" type ") requires a payload"))])
         [id# ~type ~@(when payload?
                        ['payload])])

       (defmethod target-parent? ~kw-name [_#] ~target-parent?)
       (defmethod render-mode ~kw-name [_#] ~render-mode)

       (defn ~name
         ~doc
         ([~@pl]
          (~name dynamic/*component-id* ~@pl))
         ([component-id# ~@pl]
          [component-id# ~type ~@pl])))))

(define-patch-method replace
  "Replace the whole element's HTML content."
  {:type "R"
   :js-eval "ripley.R(elt,payload);"})

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

(define-patch-method delete-many
  "Delete multiple elements."
  {:type "D+"
   :js-eval "payload.forEach(id=>{let e=_rg(id); e.parentNode.removeChild(e)});"})

(define-patch-method insert-after
  "Insert HTML after the end of component."
  {:type "F"
   :js-eval "elt.insertAdjacentHTML(\"afterend\",payload);"})

(define-patch-method move-after
  "Move existing live component after the end of component."
  {:type "M"
   :js-eval "elt.insertAdjacentElement(\"afterend\",ripley.get(payload));"})

(define-patch-method move-first
  "Move existing child to be the first child of its parent."
  {:type "<"
   :js-eval "elt.parentElement.insertAdjacentElement(\"afterbegin\",elt);"})

(define-patch-method move-last
  "Move existing child to be the last child of its parent."
  {:type ">"
   :js-eval "elt.parentElement.insertAdjacentElement(\"beforeend\",elt);"})

(define-patch-method child-order
  "Set order of children"
  {:type "O"
   :js-eval "
var fc = elt.insertAdjacentElement(\"afterbegin\",_rg(payload[0]));
for(let i=1;i<payload.length;i++) {
  fc = fc.insertAdjacentElement(\"afterend\",_rg(payload[i]));
}
 "})

(define-patch-method attributes
  "Set element attributes. Nil value removes attribute."
  {:type "@"
   :js-eval "for(var attr in payload) { ripley.setAttr(elt, attr, payload[attr]) }"
   :target-parent? true
   :render-mode :json})

(define-patch-method eval-js
  "Eval js with 'this' bound to the live component element"
  {:type "E"
   :render-mode :json
   :js-eval "(new Function(payload)).call(elt);"})

(define-patch-method template
  "Fill element from template"
  {:type "T"
   :render-mode :json
   :js-eval "ripley.T(elt,payload);"})

(def live-client-script
  (delay
    (-> "live-client-template.js" io/resource slurp
        (str/replace
         "__PATCH__"
         (str "switch(method) {\n"
              (str/join "\n"
                        (for [type (keys (methods js-eval-script))]
                          (str "case \"" type "\":" (js-eval-script type) " break;")))
              "\ndefault: let pf = window[\"ripley_patch_\"+method];
if(pf!==undefined) pf(elt,payload);
else console.error(\"Unrecognized patch method: \", method);"
              "\n}")))))
