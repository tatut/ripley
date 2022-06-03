(ns counter.main
  (:require [org.httpkit.server :as server]
            [compojure.core :refer [routes GET]]
            [ripley.html :as h]
            [ripley.live.context :as context]))


(def server (atom nil))

(def counter (atom 0))

(defn counter-app [counter]
  (h/html
   [:div
    "Counter value: " [::h/live {:source counter
                                 :component #(h/html
                                              [:span %])}]
    [:button {:on-click #(swap! counter inc)} "increment"]
    [:button {:on-click #(swap! counter dec)} "decrement"]]))

(defn counter-page []
  (h/html
   [:html
    [:head
     [:title "Ripley counter"]]
    [:body
     (h/live-client-script "/__ripley-live")
     (counter-app counter)]]))

(def counter-routes
  (routes
   (GET "/" _req
        (h/render-response counter-page))
   (context/connection-handler "/__ripley-live")))

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
