(ns openlayers.main
  "Example to use OpenLayers WebComponents"
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [ripley.live.source :as source]
            [compojure.core :refer [routes GET]]
            [compojure.route :refer [resources]]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [ripley.live.collection :as collection]
            [clojure.data.csv :as csv]))

(defonce server (atom nil))

(defonce db (atom {:stops [#:stop{:id "1" :lat 65.14747300000002 :lon 25.651736}
                           #:stop{:id "2" :lat 65.079199 :lon 25.410581}
                           #:stop{:id "3" :lat 65.154798 :lon 25.35048}
                           #:stop{:id "4" :lat 64.922673 :lon 25.806241}
                           #:stop{:id "5" :lat 64.82947900000002 :lon 25.964341}
                           #:stop{:id "6" :lat 64.99338933619902 :lon 25.45932918283235}
                           #:stop{:id "7" :lat 64.9977671502742 :lon 25.458080530059522}
                           #:stop{:id "8" :lat 64.993596 :lon 25.495647}
                           #:stop{:id "9" :lat 64.86412300000002 :lon 25.502785}
                           #:stop{:id "10" :lat 64.951872 :lon 25.508297}]}))

;; Import stops from Oulu (or any) GTFS CSV
(def gtfs-stops-file "resources/stops.txt")
(defn import-stops []
  (swap! db assoc :stops
         (vec (for [[id _ name lat lon & _]
                    (rest (csv/read-csv (slurp gtfs-stops-file)))
                    :when (and id name lat lon)]

                {:stop/id id
                 :stop/name name
                 :stop/lat (Double/parseDouble lat)
                 :stop/lon (Double/parseDouble lon)}))))


(defn find-stops [{stops :stops} [lon-min lat-min lon-max lat-max :as ex]]
  (if-not ex
    []
    (vec
     (for [{:stop/keys [lat lon] :as stop} stops
           :when (and (<= lat-min lat lat-max)
                      (<= lon-min lon lon-max))]
       stop))))

(defn page
  "Main page component."
  []
  (let [[extent set-extent!] (source/use-state nil)]
    (h/out! "<!DOCTYPE html>")
    (h/html
     [:html
      [:head
       [:title "OpenLayers Elements with Ripley"]
       [:link {:rel :stylesheet :href "openlayers.css"}]
       [::h/for [f ["ol-map.js" "ol-layer-openstreetmap.js" "ol-layer-vector.js" "ol-marker-icon.js"]]
        [:script {:src (str "https://unpkg.com/@openlayers-elements/bundle/dist/" f) :type :module}]]
       (h/live-client-script "/_ws")
       (js/export-callbacks
        {:set_extent set-extent!})]
      [:body
       [:div
        "OpenLayers WebComponents with Ripley"
        [:ol-map {:style "width: 100%; height: 90vh;"
                  :projection "EPSG:4326"
                  :zoom "9" :lat "65.0" :lon "24.73"}
         [:script
          "let m = document.querySelector('ol-map'); "
          "m.addEventListener('view-change', _=> set_extent(m.map.getView().calculateExtent()))"]
         [:ol-layer-openstreetmap]

         (collection/live-collection
          {:source (source/computed (fn [db extent]
                                      (find-stops db extent)) db extent)
           :key :stop/id
           :container-element :ol-layer-vector
           :render (fn [{:stop/keys [lon lat]}]
                     (h/html
                      [:ol-marker-icon {:src "bus.png" :lon lon :lat lat}]))})]]]])))

(def openlayers-routes
  (routes
   (GET "/" _req
        (h/render-response page))
   (resources "/")
   (context/connection-handler "/_ws")))

(defn restart
  ([] (restart 3000))
  ([port]
   (swap! server
          (fn [old-server]
            (when old-server
              (old-server))
            (println "Starting OpenLayers server on port " port)
            (server/run-server openlayers-routes {:port port})))))

(defn -main [& _]
  (restart))
