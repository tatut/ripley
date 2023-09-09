(ns ripley.live.connection
  "Protocol for a live connection.")

(defprotocol Callbacks
  "Protocol for callbacks about connection status."
  (on-close [this status]
    "Connection closed with status")
  (on-receive [this data]
    "Callback for data received.
     Data must be a string. Other types of messages are not supported.")
  (on-open [this]
    "Connection opened."))

(defprotocol Connection
  "Protocol for a page connection to a particular live context.
  This is usually a WebSocket connection."

  (send! [this data]
    "Send (string) data to client."))

(defprotocol ConnectionHandler
  "Protocol for a connection HTTP handler. This
  should take an HTTP request (like a ring request map)
  and a callbacks instance and return a connection."

  (connect [this request get-callbacks-for-id]
    "Return a Connection for the given request.
    Must call the given [[get-callbacks-for-id]] fn with the
    context id parameter to get a Callbacks instance for this
    connection."))
