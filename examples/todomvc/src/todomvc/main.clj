(ns todomvc.main
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [compojure.core :refer [routes GET]]
            [compojure.route :refer [resources]]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [ripley.live.collection :refer [live-collection]]
            [todomvc.atom :as atom-storage]
            [todomvc.pg :as pg-storage]
            [todomvc.protocols :as p]
            [todomvc.mentions :as mentions]
            [re-html-template.core :refer [define-html-template]]))

(re-html-template.core/set-global-options!
 {:file "todomvc.html"
  :wrap-hiccup '(ripley.html/html %)})

(define-html-template todo-item
  [{:keys [mark-complete mark-incomplete remove]} {:keys [label id complete?]}]
  {:selector ".todo-list .view"}

  :input.toggle {:prepend-children (list "foo")
                 :set-attributes {:checked complete?
                                  :on-change #(if complete?
                                           (mark-incomplete id)
                                           (mark-complete id))}}
  :label {:replace-children label})

(define-html-template todo-form [storage]
  {:selector ".new-todo"}

  :.new-todo {:set-attributes
              {:on-keypress (js/js-when js/enter-pressed?
                                        #(p/add-todo storage {:label % :complete? false})
                                        "window.event.target.value")}})

(define-html-template footer [storage todos-source]
  {:selector "footer.footer"}

  [:.todo-count :strong] {:replace [::h/live
                                    (p/count-source storage)
                                    #(h/out! (str %))]}

  {:data-filter "all"}
  {:set-attributes {:on-click #(p/set-filter! todos-source :all)}}

  {:data-filter "active"}
  {:set-attributes {:on-click #(p/set-filter! todos-source :active)}}

  {:data-filter "completed"}
  {:set-attributes {:on-click #(p/set-filter! todos-source :completed)}})

(define-html-template todomvc [storage todos-source]
  {:selector "html"}
  :head {:prepend-children [:link {:rel :stylesheet :href "todomvc.css"}]}
  :body {:prepend-children (h/live-client-script "/__ripley-live")}

  ;; The new todo form
  :.new-todo {:replace (todo-form storage)}

  ;; List of todos as a live collection
  :ul.todo-list
  {:replace
   (live-collection
    {:container-element :ul.todo-list
     :child-element :li
     :source todos-source
     :key :id
     :render (partial todo-item
                      {:mark-complete (partial p/mark-complete storage)
                       :mark-incomplete (partial p/mark-incomplete storage)
                       :remove (partial p/remove-todo storage)})})}

  ;; Footer filter links
  :footer.footer {:replace (footer storage todos-source)})

(defonce storage nil)

(def todomvc-routes
  (routes
   (GET "/" _req
        (h/render-response
         #(todomvc storage
                   (p/live-source storage (atom :all)))))
   (resources "/")
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
