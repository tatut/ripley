(ns counter.main
  (:require [org.httpkit.server :as server]
            [ring.middleware.params :as params]
            [compojure.core :refer [routes GET]]
            [ripley.html :as h]
            [ripley.live.context :as context]))

(def ^:dynamic *increment-by* 1)

(def server (atom nil))

(def counter (atom 0))

(defn counter-app [counter]
  (h/html
   [:div
    "Counter value: "
    [::h/live-let [v counter]
     [:span "Current value: " v ", change by " *increment-by*]]
    [:button {:on-click #(swap! counter + *increment-by*)} "increment"]
    [:button {:on-click #(swap! counter - *increment-by*)} "decrement"]]))

(defn counter-page [{{:strs [by]} :params :as _req}]
  (binding [*increment-by* (or (some-> by Long/parseLong) 1)]
    (h/html
     [:html
      [:head
       [:title "Ripley counter"]]
      [:body
       (h/live-client-script "/__ripley-live")
       (counter-app counter)]])))

(def counter-routes
  (-> (routes
       (GET "/" req
         (h/render-response
          {}
          (partial counter-page req)
          {:bindings #{#'*increment-by*}}))
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
