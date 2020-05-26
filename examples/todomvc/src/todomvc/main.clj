(ns todomvc.main
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [compojure.core :refer [routes GET]]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [ripley.live.collection :refer [live-collection]]
            [todomvc.atom :as atom-storage]
            [todomvc.pg :as pg-storage]
            [todomvc.protocols :as p]
            [todomvc.mentions :as mentions]))

(defn todo-item [{:keys [mark-complete mark-incomplete remove]} {:keys [label id complete?]}]
  (h/html
   [:<>
    [:td
     [:input {:type :checkbox
              :checked complete?
              :on-change #(if complete?
                            (mark-incomplete id)
                            (mark-complete id))}]]
    [:td
     [:span {:style {:text-decoration (if complete?
                                        :line-through
                                        :none)}}
      label]]
    [:td
     [:a.delete {:on-click #(remove id)} "remove"]]]))


(defn todo-form [storage]
  (h/html
   [:form {:action "#" :on-submit "return false;"}
    #_(mentions/mentions-input :new-todo)
    [:input.input#new-todo {:type :text :placeholder "What needs to be done?"}]
    [:button {:on-click (js/js (fn [todo]
                                 (p/add-todo storage {:label todo
                                                      :complete? false}))
                               (js/input-value :new-todo))}
     "Add todo"]]))

(defn todomvc [storage]
  (h/html
   [:html
    [:head
     [:link {:rel :stylesheet :href "https://cdn.jsdelivr.net/npm/bulma@0.8.2/css/bulma.min.css"}]]
    [:body
     (h/live-client-script "/__ripley-live")
     [:div.todomvc
      [:table.table
       [:thead [:tr [:td " "] [:td "What to do?"] [:td " "]]]
       (live-collection {:container-element :tbody
                         :child-element :tr
                         :source (p/live-source storage)
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
