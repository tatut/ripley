(ns todomvc.main
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [ripley.live.atom :refer [atom-source]]
            [ripley.live.poll :refer [poll-source]]
            [compojure.core :refer [routes GET]]
            [org.httpkit.server :as server]
            [org.httpkit.client :as client]
            [ripley.live.context :as context]
            [ripley.live.collection :refer [live-collection]]
            [todomvc.atom :as atom-storage]
            [todomvc.pg :as pg-storage]
            [todomvc.protocols :as p]
            [todomvc.mentions :as mentions]
            [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.core.async :as async]))

(defn todo-item [{:keys [mark-complete mark-incomplete remove]} {:keys [label id complete?]}]
  (h/html
   [:li
    [:input {:type :checkbox
             :checked complete?
             :on-change #(if complete?
                           (mark-incomplete id)
                           (mark-complete id))}]
    [:span {:style {:text-decoration (if complete?
                                       :line-through
                                       :none)}}
     label]

    [:button {:on-click #(remove id)} "remove"]]))

(defn todo-list [{:keys [mark-complete mark-incomplete]} current-todos]
  (h/html
   [:ul {:style {:margin "1rem"}}
    [::h/for [{:keys [label id complete?]} current-todos]
     [:li
      [:input {:type :checkbox
               :checked complete?
               :on-change #(if complete?
                             (mark-incomplete id)
                             (mark-complete id))}]
      [:span {:style {:text-decoration (if complete?
                                         :line-through
                                         :none)}}
       label]]]]))



(defn todo-form [storage]
  (h/html
   [:form {:action "#" :on-submit "return false;"}
    (mentions/mentions-input :new-todo)

    [:button {:on-click (js/js (fn [todo]
                                 (p/add-todo storage {:label todo
                                                      :complete? false}))
                               (js/input-value :new-todo))}
     "Add todo"]]))

(defn todomvc [storage]
  (h/html
   [:html
    [:head]
    [:body
     (h/live-client-script "/__ripley-live")
     [:div.todomvc
      [:ul
       (live-collection {:source (p/live-source storage)
                         :key :id
                         :render (partial todo-item {:mark-complete (partial p/mark-complete storage)
                                                     :mark-incomplete (partial p/mark-incomplete storage)
                                                     :remove (partial p/remove-todo storage)})})]
      (todo-form storage)]

     [:footer
      "Time is now: " #_[::h/live {:source (poll-source 500 #(java.util.Date.))
                                 :component #(h/out! (str %))}]]]]))

(defonce storage nil)

(def todomvc-routes
  (routes
   (GET "/" _req
        (h/render-response #(todomvc storage)))
   (context/connection-handler "/__ripley-live")))

(defonce server (atom nil))

(defn- restart []
  (swap! server
         (fn [old-server]
           (when old-server
             (old-server))
           (println "Starting todomvc server")
           (server/run-server todomvc-routes {:port 3000}))))

(defn -main [& args]
  (let [storage-type (or (first args) "atom")]
    (println "Using storage: " storage-type)
    (alter-var-root #'storage (constantly (case storage-type
                                            "pg" @pg-storage/storage
                                            "atom" @atom-storage/storage)))
    (restart)))
