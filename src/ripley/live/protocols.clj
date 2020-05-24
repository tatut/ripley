(ns ripley.live.protocols
  "Protocols for live components.")

(defprotocol LiveContext
  (register! [this source component opts]
    "Register new live component in this context. Must return id for component.")
  (register-callback! [this callback]
    "Register new callback. Must return callback id.")
  (deregister! [this id]
    "Deregister a previously registered component by id. Returns nil.
Live component should deregister once no more live updates are coming.
When all live components on a page have been deregistered, the context and socket
can be closed.")
  (send! [this payload]
    "Send command to connected client."))

(defprotocol Source
  (to-channel [this] "Return core.async channel where this source can be read.")
  (immediate? [this] "Return true this source can be read immediately when first rendering.")
  (close! [this] "Close this source and cleanup any server resources."))
