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

(defn mentions-popup [on-select {:keys [showing? searching? term results]}]
  (h/html
   [:div {:style {:display (if showing? :block :none)
                  :z-index 999
                  :position :static}}
    [::h/when searching?
     [:div {:style {:background-color :wheat
                    :padding "1rem"
                    :border "solid 1px black"}}
      "Searching... " term]]
    [::h/when (seq results)
     [:div
      [::h/for [{:keys [id login avatar_url] :as user} (take 10 results)]
       [:div {:on-click #(on-select user)}
        [:img {:src avatar_url :width 32 :height 32}]
        login]]]]]))

(defn- search-gh-users [txt]
  (let [results
        (-> "https://api.github.com/search/users"
            (client/get {:query-params {"q" txt}})
            deref :body
            (cheshire/decode keyword) :items)]
    (into []
          (map #(select-keys % [:avatar_url :login :id]))
          results)))

(defn- update-mentions [mentions-atom ch]
  (cond
    (= ch "@")
    (swap! mentions-atom assoc :showing? true)

    (:showing? @mentions-atom)
    (swap! mentions-atom
           #(-> %
                (update :term str ch)
                (assoc :searching? true))))
  (let [{term :term} @mentions-atom]
    (when (not (str/blank? term))
      (async/thread
        (let [users (search-gh-users term)]
          (println "found " (count users) " gh users")
          (swap! mentions-atom assoc
                 :showing? true
                 :searching? false
                 :results users))))))

(defn todo-form [storage]
  (let [mentions (atom {:showing? false
                        :searching? false
                        :term ""
                        :results nil})
        text (atom "")]
    (h/html
     [:form {:action "#" :on-submit "return false;"}
      [::h/live
       {:source (atom-source text)
        :component
        (fn [current-text]
          (h/html
           [:input#new-todo {:type :text
                             :value current-text
                             :on-key-press (js/js (partial update-mentions mentions) js/keycode-char)
                             :on-change (js/js #(reset! text %) js/change-value)}]))}]
      [::h/live {:source (atom-source mentions)
                 :component (partial mentions-popup (fn [user]
                                                      (println "selected: " user)
                                                      (swap! mentions assoc :showing? false)))}]
      [:button {:on-click (js/js (fn [todo]
                                   (reset! text "")
                                   (p/add-todo storage {:label todo
                                                        :complete? false}))
                                 (js/input-value :new-todo))}
       "Add todo"]])))

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
