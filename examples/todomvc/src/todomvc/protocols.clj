(ns todomvc.protocols
  "Protocol for the todomvc storage")

(defprotocol TodoStorage
  (live-source [this] "Get the live component source")
  (add-todo [this todo] "Add the given todo.")
  (mark-complete [this todo-id] "Mark todo by id complete")
  (mark-incomplete [this todo-id] "Mark todo by id incomplete"))
