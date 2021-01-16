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

(defn remove-todo [todos id]
  (swap! todos
         (fn [todos]
           (into [] (filter #(not= (:id %) id)) todos))))

(defn filtered-todos-atom-source [todos-atom filter-atom]
  (let [filtered-todos-atom (atom nil)
        update! (fn [todos filter-value]
                  (reset! filtered-todos-atom
                          (into []
                                (filter (fn [todo]
                                          (case filter-value
                                            :all true
                                            :active (not (:complete? todo))
                                            :completed (:complete? todo))))
                                todos)))]
    (add-watch filter-atom :change-filter
               (fn [_ref _key _old filter-value]
                 (update! @todos-atom filter-value)))
    (add-watch todos-atom :change-todos
               (fn [_ref _key _old todos]
                 (update! todos @filter-atom)))
    (update! @todos-atom @filter-atom)
    (with-meta
      (atom-source filtered-todos-atom)
      {`p/set-filter! (fn [_ v] (reset! filter-atom v))
       `p/current-filter-source (constantly filter-atom)})))

(defn- rename-todo [todos id new-label]
  (update-todo todos id assoc :label new-label))

(defn- clear-completed-todos [todos]
  (swap! todos #(into []
                      (remove :complete?)
                      %)))

(defn- count-items-source [atom pred]
  (atom-source atom #(count (filter pred %))))

(defrecord TodoAtomStorage [todos]
  p/TodoStorage
  (live-source [_ filter-atom]
    (filtered-todos-atom-source todos filter-atom))
  (count-source [_]
    (count-items-source todos (complement :complete?)))
  (has-todos-source [_]
    (atom-source todos (comp boolean seq)))
  (add-todo [_ todo] (add-todo todos todo))
  (remove-todo [_ todo-id] (remove-todo todos todo-id))
  (mark-complete [_ todo-id] (mark-complete todos todo-id))
  (mark-incomplete [_ todo-id] (mark-incomplete todos todo-id))
  (rename [_ todo-id new-label] (rename-todo todos todo-id new-label))
  (clear-completed [_] (clear-completed-todos todos)))

(defonce storage (delay (->TodoAtomStorage todos)))
