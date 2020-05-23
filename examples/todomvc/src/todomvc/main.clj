(ns todomvc.main
  (:require [ripley.html :as h]
            [ripley.live.atom :refer [atom-source]]
            [compojure.core :refer [routes GET]]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]))

(def todos (atom [{:label "do this" :complete? true :id 1}
                  {:label "and that" :complete? false :id 2}]))

(defn add-todo [todos todo]
  (swap! todos conj todo))

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

(defn todomvc [todos]
  (h/html
   [:html
    [:head]
    [:body
     (h/live-client-script "/__ripley-live")
     [:div.todomvc
      [:ul
       [::h/live {:source (ripley.live.atom/atom-source todos)
                  :component (partial todo-list {:mark-complete (partial mark-complete todos)
                                                 :mark-incomplete (partial mark-incomplete todos)})}]]]]]))

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
