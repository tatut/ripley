(ns todomvc.protocols
  "Protocol for the todomvc storage")

(defprotocol TodoStorage
  (live-source [this filter-atom]
    "Get the live component source. Filter atom contains the requested
todo filter: :all (default), :active (incomplete) or :completed")
  (add-todo [this todo] "Add the given todo.")
  (remove-todo [this todo-id] "Remove todo by id.")
  (mark-complete [this todo-id] "Mark todo by id complete")
  (mark-incomplete [this todo-id] "Mark todo by id incomplete")
  (count-source [this] "Return source for active todo count")
  (has-todos-source [this]
    "Returns boolean source that is true if there are any todos")
  (has-completed-todos-source [this]
    "Returns boolean source that is true if there are any completed todos")
  (rename [this todo-id new-label] "Rename this todo with a new label")
  (clear-completed [this] "Remove all completed items")
  (toggle-all [this] "Toggle all todos complete? true/false")
  (all-completed-source [this]
    "Return boolean source that is true if all todos are completed"))

(defprotocol TodoSource
  :extend-via-metadata true
  (set-filter! [this filter-value] "Set the filter for the todos")
  (current-filter-source [this] "Return a source that returns current filter"))
