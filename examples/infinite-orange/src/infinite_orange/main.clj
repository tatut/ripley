(ns infinite-orange.main
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [compojure.core :refer [routes GET]]
            [org.httpkit.server :as server]
            [cheshire.core :as cheshire]
            [ripley.live.context :as context]
            [infinite-orange.hn :as hn]
            [ripley.live.collection :refer [infinite-scroll]]))

(defn news-item [{:keys [title] :as item}]
  (h/html
   [:div.card
    [:header.card-header
     [:p.card-header-title.is-centered title]]
    [:div.card-content
     ]]))

(defn news []
  (infinite-scroll {:render news-item
                    :next-batch (hn/top-stories-batches 10)
                    :immediate? false}))

(defn index []
  (h/html
   [:html
    [:head
     [:link {:rel :stylesheet
             :href "https://cdn.jsdelivr.net/npm/bulma@0.8.2/css/bulma.min.css"}]]
    [:body
     (h/live-client-script "/__ripley-live")
     [:div.infinite-orange
      (news)]]]))

(def news-routes
  (routes
   (GET "/" _req
        (h/render-response #(index)))
   (context/connection-handler "/__ripley-live")))

(defonce server (atom nil))

(defn- restart []
  (swap! server
         (fn [old-server]
           (when old-server (old-server))
           (println "Starting infinite orange server")
           (server/run-server news-routes {:port 3000}))))

(defn -main [& args]
  (restart))
