(ns todomvc.atom
  "Todomvc backed by an atom"
  (:require [ripley.live.atom :refer [atom-source]]
            [todomvc.protocols :as p]))

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

(defrecord TodoAtomStorage [todos]
  p/TodoStorage
  (live-source [_] (atom-source todos))
  (add-todo [_ todo] (add-todo todos todo))
  (mark-complete [_ todo-id] (mark-complete todos todo-id))
  (mark-incomplete [_ todo-id] (mark-incomplete todos todo-id)))

(defonce storage (delay (->TodoAtomStorage todos)))
