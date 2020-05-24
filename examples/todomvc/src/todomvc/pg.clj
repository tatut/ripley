(ns todomvc.pg
  "PostgreSQL todomvc storage"
  (:require [next.jdbc :as jdbc]

            ;; We need to know when to query again...
            [ripley.live.async :refer [subscribe-source publish]]
            [todomvc.protocols :as p]))

(defn add-todo [ds todo]
  (jdbc/execute-one! ds ["INSERT INTO todos (label, complete) VALUES (?, ?)"
                         (:label todo)
                         (:complete? todo)])
  (publish :todo/created))


(defn mark-complete [ds todo-id]
  (jdbc/execute-one! ds ["UPDATE todos SET complete=true WHERE id=?" todo-id])
  (publish :todo/updated))

(defn mark-incomplete [ds todo-id]
  (jdbc/execute-one! ds ["UPDATE todos SET complete=false WHERE id=?" todo-id])
  (publish :todo/updated))

(defn remove-todo [ds todo-id]
  (jdbc/execute-one! ds ["DELETE FROM todos WHERE id=?" todo-id])
  (publish :todo/deleted))

(defn fetch-todos [ds]
  (mapv (fn [{:todos/keys [id label complete]}]
          {:id id :label label :complete? complete})
        (jdbc/execute! ds ["SELECT id,label,complete FROM todos ORDER BY id ASC"])))

(defrecord TodoPgStorage [ds]
  p/TodoStorage
  (live-source [_] (subscribe-source #{:todo/created :todo/updated :todo/deleted}
                                     (fn [_]
                                       (fetch-todos ds))
                                     {:event/type :todo/created}))
  (add-todo [_ todo] (add-todo ds todo))
  (remove-todo [_ todo-id] (remove-todo ds todo-id))
  (mark-complete [_ todo-id] (mark-complete ds todo-id))
  (mark-incomplete [_ todo-id] (mark-incomplete ds todo-id)))

;; Create the database and initialize it with:
;; > CREATE TABLE todos (id SERIAL, label TEXT, complete boolean);
;;
(def db {:dbtype "postgres" :dbname (System/getProperty "user.name")})

(def storage (delay (->TodoPgStorage (jdbc/get-datasource db))))
