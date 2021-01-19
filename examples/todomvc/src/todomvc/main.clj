(ns todomvc.main
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [compojure.core :refer [routes GET]]
            [compojure.route :refer [resources]]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [ripley.live.collection :refer [live-collection]]
            [ripley.live.hash-route :refer [on-hash-change]]
            [todomvc.atom :as atom-storage]
            [todomvc.pg :as pg-storage]
            [todomvc.protocols :as p]
            [re-html-template.core :refer [html]]
            [clojure.string :as str]))

(re-html-template.core/set-global-options!
 {:file "todomvc.html"
  :wrap-hiccup '(ripley.html/html %)})

(def ^:const focus-and-place-caret
  "let e = this.querySelector('.edit'); e.focus(); e.selectionStart = e.selectionEnd = e.value.length;")

(defn todo-item
  [{:keys [mark-complete mark-incomplete rename remove]}
   {:keys [label id complete?]}]
  (let [edit-atom (atom false)
        save! #(when @edit-atom
                 (let [new-label (str/trim %)]
                   (if (not= new-label label)
                     (rename id (str/trim %))
                     (reset! edit-atom false))))]
    (html
     {:selector ".todo-list li"}

     :li {:set-attributes
          {:class [::h/live {:source edit-atom
                             :component #(str (when % "editing")
                                              (when complete? " completed"))
                             :did-update #(when % [:eval-js focus-and-place-caret])}]}}
     :.view {:set-attributes {:on-dblclick #(reset! edit-atom true)}}
     :input.toggle {:set-attributes {:checked complete?
                                     :on-change #(if complete?
                                                   (mark-incomplete id)
                                                   (mark-complete id))}}
     :label {:replace-children label}
     :.edit {:set-attributes
             {:id (str "edit" id)
              :value label
              :on-blur (js/js save! (js/input-value (str "edit" id)))
              :on-keydown [(js/js-when js/esc-pressed?
                                       #(reset! edit-atom false))
                           (js/js-when js/enter-pressed?
                                       save!
                                       (js/input-value (str "edit" id)))]}}
     :.destroy {:set-attributes
                {:on-click #(remove id)}})))

(defn todo-form [storage]
  (html
   {:selector ".new-todo"}

   :.new-todo
   {:set-attributes
    {:on-keypress [(js/js-when js/enter-pressed?
                               #(p/add-todo storage {:label (str/trim %) :complete? false})
                               "window.event.target.value")
                   ;; Clear it with some js, this could be also
                   ;; done with a source so that it is only cleared
                   ;; once the todo has actually been added
                   "if(window.event.keyCode==13)document.querySelector('.new-todo').value=''"]}}))

(defn todo-count [c]
  (h/html
   [:span.todo-count
    [:strong c]
    [::h/if (= 1 c)
     " item "
     " items "]
    "left"]))

(defn footer [storage todos-source]
  (html
   {:selector "footer.footer"}

   :.todo-count
   {:replace [::h/live (p/count-source storage) todo-count]}

   :.clear-completed
   {:wrap [::h/when (p/has-completed-todos-source storage) %]
    :set-attributes {:on-click #(p/clear-completed storage)}}

   ;; We could handle changing the filter here with :on-click, but we'll
   ;; do it when the hash route changes and just leave these
   ;; as links.
   ;; This approach makes back button also work and automatically
   ;; sets the active filter when reloading the page with a hash

   {:data-filter "all"}
   {:set-attributes
    {:class [::h/live
             (p/current-filter-source todos-source)
             #(when (= :all %) "selected")]}}

   {:data-filter "active"}
   {:set-attributes
    {:class [::h/live
             (p/current-filter-source todos-source)
             #(when (= :active %) "selected")]}}

   {:data-filter "completed"}
   {:set-attributes
    {:class [::h/live
             (p/current-filter-source todos-source)
             #(when (= :completed %) "selected")]}}))

#_(defn warning [storage]
  (h/html
   [:div {:style [::h/live (p/count-source storage)
                  (fn [c]
                    {:background-color
                     (case c
                       0 "green"
                       1 "gray"
                       2 "yellow"
                       "red")})]}
    "You have " [::h/live (p/count-source storage)
                 #(h/html [:span %])] " todos!"]))

(defn todomvc [storage todos-source]
  (html
   {:selector "html"}
   :head {:prepend-children
          [:link {:rel :stylesheet :href "todomvc.css"}]}
   :body {:set-attributes {:on-load "document.querySelector('.new-todo').focus()"}
          :prepend-children [:<>
                             (h/live-client-script "/__ripley-live")
                             (on-hash-change
                              #(case %
                                 "#/active" (p/set-filter! todos-source :active)
                                 "#/" (p/set-filter! todos-source :all)
                                 "#/completed" (p/set-filter! todos-source :completed)
                                 (println "other route:" %)))]}

   ;;:.todoapp {:prepend-children (warning storage)}

   ;; The new todo form
   :.new-todo {:replace (todo-form storage)}

   ;; Skip whole main section when there are no todos
   :.main {:wrap [::h/when (p/has-todos-source storage) %]}

   ;; Toggle all
   :.toggle-all
   {:set-attributes {:checked [::h/live (p/all-completed-source storage)]
                     :on-change #(p/toggle-all storage)}}

   ;; List of todos as a live collection
   :ul.todo-list
   {:replace
    (live-collection
     {:container-element :ul.todo-list
      :source todos-source
      :key :id
      :render (partial todo-item
                       {:mark-complete (partial p/mark-complete storage)
                        :mark-incomplete (partial p/mark-incomplete storage)
                        :remove (partial p/remove-todo storage)
                        :rename (partial p/rename storage)})})}


   ;; Footer filter links (skip if no todos)
   :.footer {:wrap [::h/when (p/has-todos-source storage) %]
             :replace (footer storage todos-source)}))

(defonce storage nil)

(def todomvc-routes
  (routes
   (GET "/" _req
        (let [storage (storage)]
          (h/render-response
           #(todomvc storage
                     (p/live-source storage (atom :all))))))
   (resources "/")
   (context/connection-handler "/__ripley-live")))

(defonce server (atom nil))

(defn- restart
  ([] (restart 3000))
  ([port]
   (swap! server
          (fn [old-server]
            (when old-server
              (old-server))
            (println "Starting todomvc server")
            (server/run-server todomvc-routes {:port port})))))

(defn -main [& [storage-type port]]
  (let [storage-type (or storage-type "atom-per-session")
        port (if port
               (Integer/parseInt port)
               3000)]
    (println "Using storage: " storage-type)
    (alter-var-root #'storage (constantly
                               (case storage-type
                                 "pg" (fn [] @pg-storage/storage)
                                 "atom" (fn [] @atom-storage/storage)
                                 "atom-per-session"
                                 (fn []
                                   (let [a (atom [])]
                                     #_(add-watch a ::debug
                                                  (fn [_ _ old new]
                                                    (log/debug "CHANGE: " old " => " new)))
                                     (atom-storage/->TodoAtomStorage a))))))
    (restart port)))
