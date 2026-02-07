(ns counter.main
  (:require [org.httpkit.server :as server]
            [ring.middleware.params :as params]
            [compojure.core :refer [routes GET]]
            [ripley.html :as h]
            [ripley.js :as js]
            [ripley.live.context :as context]))

(def ^:dynamic *increment-by* 1)

(def server (atom nil))

(def counter (atom 0))

(defmulti event (fn [[type :as event]]
                  (println "Got event: " (pr-str event))
                  type))

(defmethod event :+ [[_ by]]
  (swap! counter + by))
(defmethod event :- [[_ by]]
  (swap! counter - by))
(defmethod event :set [[_ to]]
  (try
    (reset! counter (Long/parseLong to))
    (catch Exception _e
      ;; send events back to js
      {:events [[:error "Unparseable!"]]})))

(defmethod event :ripley/connected [_]
  (println "Client connected"))
(defmethod event :ripley/disconnected [_]
  (println "Client disconnected"))

(defn counter-app [counter]
  (h/html
   [:div
    "Counter value: "
    [::h/live-let [v counter]
     [:span "Current value: " v ", change by " *increment-by*]]
    [:button {:onclick [:+ *increment-by*]} "increment"]
    [:button {:onclick [:- *increment-by*]} "decrement"]

    [:div
     "Set counter to value:"
     [:input#to {:type :text}]
     [:button {:onclick [:set (js/input-value :to)]} "set"]
     [:button {:onclick (js/js-debounced 1000 [:set (js/input-value :to)])} "set later"]]]))

(defn counter-page [{{:strs [by]} :params :as _req}]
  (binding [*increment-by* (or (some-> by Long/parseLong) 1)]
    (h/html
     [:html
      [:head
       [:title "Ripley counter"]
       (h/live-client-script "/__ripley-live")
       [:script
        ;; Add client side handler for "error" event
        "ripley.event.error = (msg) => { alert(msg); };"]
       ]
      [:body

       (counter-app counter)]])))

(def counter-routes
  (-> (routes
       (GET "/" req
         (h/render-response
          {}
          (partial counter-page req)
          {:bindings #{#'*increment-by*}
           :event-handler event}))
       (context/connection-handler "/__ripley-live"))
      params/wrap-params))

(defn- restart
  ([] (restart 3000))
  ([port]
   (swap! server
          (fn [old-server]
            (when old-server
              (old-server))
            (println "Starting counter server")
            (server/run-server counter-routes {:port port})))))

(defn -main [& _args]
  (restart))

(comment
  (restart))
