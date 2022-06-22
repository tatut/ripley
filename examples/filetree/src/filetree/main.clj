(ns filetree.main
  (:require [org.httpkit.server :as server]
            [compojure.core :refer [routes GET]]
            [ripley.html :as h]
            [ripley.live.context :as context]
            [ripley.live.source :as source]
            [ripley.live.protocols :as p]
            [ripley.js :as js]
            [clojure.string :as str])
  (:import (java.io File)))


(defonce server (atom nil))

;; Some file utilities

(defn- matching-files [path name-filter]
  (mapcat
   (fn [file]
     (if (.isDirectory file)
       (matching-files file name-filter)
       (when (str/includes? (.getName file) name-filter)
         [file])))
   (.listFiles path)))

(defn- paths-leading-to-matching-files [path matching-files]
  (let [->absolute #(.getAbsolutePath %)
        top-path (->absolute path)]
    (if (empty? matching-files)
      #{}
      (into #{top-path}
            (comp
             (mapcat (fn [matching-file]
                       (take-while #(not= top-path (->absolute %))
                                   (drop 1 (iterate #(.getParentFile %) matching-file)))))
             (map ->absolute))
            matching-files))))

;; UI components

(declare folder)

(defn files [{:keys [name-filter] :as ctx} path]
  (h/html
   [:div {:style "padding-left: 1rem;"}
    [::h/live name-filter
     (fn [name-filter]
       (h/html
        [:div
         [::h/for [file (.listFiles path)
                   :let [name (.getName file)]
                   :when (or (.isDirectory file)
                             (str/includes? name name-filter))]
          [::h/if (.isDirectory file)
           (folder ctx file)
           [:div {:style "padding-left: 1.5rem;"} name]]]]))]]))

(defn folder [{:keys [expanded toggle-expanded!] :as ctx} path]
  (let [name (-> path .getCanonicalFile .getName)
        id (.getAbsolutePath path)
        expanded? (source/computed #(contains? % id) expanded)]
    (h/html
     [::h/live expanded?
      (fn [expanded?]
        (h/html
         [:div
          [:div {:style "display: flex;"}
           [:button {:on-click (partial toggle-expanded! id)}
            [::h/if expanded? "-" "+"]]
           [:span name]]

          (when expanded?
            (files ctx path))]))])))

(defn search! [set-name-filter! set-expanded! path new-name-filter]
  ;; Expand all paths and parents that contain matching files
  (let [paths (if (str/blank? new-name-filter)
                #{}
                (paths-leading-to-matching-files
                 path (matching-files path new-name-filter)))]
    (set-expanded! paths)
    (set-name-filter! new-name-filter)))

(defn filetree-app [path]
  (let [[expanded set-expanded!] (source/use-state #{})
        [name-filter set-name-filter!] (source/use-state "")]
    (h/html
     [:div
      [:h3 "Filetree"]
      [:input#name-filter {:type "text"
                           :on-input (js/js-debounced 500
                                                      #(search! set-name-filter! set-expanded! path %)
                                                      (js/input-value :name-filter))}]
      (folder {:name-filter name-filter
               :expanded expanded
               :toggle-expanded! (fn [path]
                                   (let [cur (p/current-value expanded)]
                                     (set-expanded!
                                      ((if (cur path)
                                         disj conj) cur path))))}
              path)])))

(defn filetree-page [path]
  (h/html
   [:html
    [:head
     [:title "Ripley filetree"]]
    [:body
     (h/live-client-script "/__ripley-live")
     (filetree-app path)]]))

(defn filetree-routes [path]
  (routes
   (GET "/" _req
        (h/render-response (partial filetree-page path)))
   (context/connection-handler "/__ripley-live")))

(defn- restart
  ([] (restart 3000 "."))
  ([port path]
   (swap! server
          (fn [old-server]
            (when old-server
              (old-server))
            (println "Starting counter server")
            (server/run-server (filetree-routes (File. path)) {:port port})))))

(defn -main [& _args]
  (restart))
