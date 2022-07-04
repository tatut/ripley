(ns ripley.template
  "Implement a client side template."
  (:require [ripley.impl.output :as output]
            [ripley.impl.dynamic :as dynamic]
            [ripley.html :as h]
            [ripley.live.protocols :as p]))


;; Define a proxy map that automatically creates names for
;; keys that are accessed from the map.
;; The same key will return the same name.
(deftype TemplateDataProxy [state]
  clojure.lang.ILookup
  (valAt [this key]
    (let [new-state
          (swap! state
                 (fn [{idx ::idx fields ::fields :as state}]
                   (if (contains? fields key)
                     state
                     {::idx (inc idx)
                      ::fields (assoc fields key idx)})))]
      (str "{{_rl" (-> new-state ::fields key) "}}")))
  (valAt [this key not-found]
    (.valAt this key)))

(defn- ->template-data-proxy []
  (TemplateDataProxy. (atom {::idx 0 ::fields {}})))


(defn use-template
  "Render a template based on the given component.
  The component will be rendered with a special proxy value
  that tracks what fields are accessed.

  Note that components are severely limited.
  They may not have live components and can only have simple
  substitutions without code logic.
  "
  [component selector data-source]
  (let [ctx dynamic/*live-context*
        dp (->template-data-proxy)
        id (when ctx (p/register! ctx nil :_ignore {}))
        body (output/render-to-string component dp)
        fields (apply juxt (map key (sort-by val (::fields @(.-state dp)))))]
    (p/listen! data-source
               #(p/send! ctx [[id "T" (into [selector] (map fields) %)]]))
    (h/html [:template {:data-rl id}
             (h/out! body)])))
