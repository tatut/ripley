(ns todomvc.main
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [ripley.live.atom :refer [atom-source]]
            [compojure.core :refer [routes GET]]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]))

(def todos (atom [{:label "do this" :complete? true :id 1}
                  {:label "and that" :complete? false :id 2}]))

(defn add-todo [todos todo]
  (swap! todos
         (fn [todos]
           (let [id (if (seq todos)
                      (inc (reduce max (map :id todos)))
                      0)]
             (conj todos (merge todo {:id id}))))))

(defn update-todo [todos id update-fn & args]
  (swap! todos (fn [todos]
                 (mapv (fn [todo]
                         (if (= id (:id todo))
                           (apply update-fn todo args)
                           todo))
                       todos))))

(defn mark-complete [todos id]
  (update-todo todos id assoc :complete? true))

(defn mark-incomplete [todos id]
  (update-todo todos id assoc :complete? false))

(defn todo-list [{:keys [mark-complete mark-incomplete]} current-todos]
  (h/html
   [:ul
    [::h/for [{:keys [label id complete?]} current-todos]
     [:li [:input {:type :checkbox
                   :checked complete?
                   :on-change #(if complete?
                                 (mark-incomplete id)
                                 (mark-complete id))}]
      label]]]))

(defn todo-form []
  (h/html
   [:form {:action "#" :on-submit "return false;"}
    [:input#new-todo {:type :text
                      :on-change (js/js #(println "nyt se on: " %) js/change-value)}]
    [:button {:on-click (js/js (fn [todo]
                                 (add-todo todos {:label todo
                                                  :complete? false}))
                               (js/input-value :new-todo))}]]))
(defn todomvc [todos]
  (h/html
   [:html
    [:head]
    [:body
     (h/live-client-script "/__ripley-live")
     [:div.todomvc
      [::h/live {:source (ripley.live.atom/atom-source todos)
                 :component (partial todo-list {:mark-complete (partial mark-complete todos)
                                                :mark-incomplete (partial mark-incomplete todos)})}]
      (todo-form)]]]))

(def todomvc-routes
  (routes
   (context/wrap-live-context
    (GET "/" req
         (h/render-response #(todomvc todos))))
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
  (restart))
