(ns ripley.live.form
  "Live form that has key/value components.
  Validates data and routes changes to fields."
  (:require [clojure.spec.alpha :as s]))

(s/def ::name keyword?)
(s/def ::field (s/or :name ::name
                     :options (s/keys :req-un [::name])))

(defn field
  "Register and render a live component for a field.
  Render function is called with a map of:
  {:set!  <callback to set value>
   :value <source containing current value>
   :has-error? <boolean source if field is invalid>
   :error <source for error message, nil if no error>}

  Field error can also be rendered separately from the input
  with field-error function."
  [field-or-opts render-field-fn]
  )

(defn field-error
  "Register and render an error display for field.
  Rendere function is called with map containing two sources
  {:has-error? <boolean source if field is invalid>
   :error <source for error message, nil if no error>"
  [field-or-opts render-field-error-fn])

(defn form
  "Form with key/value data.

  Body should render HTML for the form. All input
  components must be wrapped with `field`.


  Options:
  :spec   defines spec for the form map (eg. s/keys)


  "
  [{:keys [spec]} render-form-fn])

(comment
  (s/def ::registration-form
    (s/keys :req [::username]))
  (form
   {:spec ::registration-form
    :on-save (partial user-db/register-user! db)}
   (fn [form-ctx]
     (h/html
      [:div.registration-form
       [:div.grid
        [:div.field
         [:label {:for "username"} "Username:"]
         (field ::username
                (fn [{:keys [id set! value]}]
                  (h/html
                   [:input {:id id
                            :value [::h/live value]
                            :on-blur (js/js set! (js/input-value id))}])))
         ]]]))
        ))
