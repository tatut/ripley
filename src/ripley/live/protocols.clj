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
  (register-cleanup! [this cleanup-fn]
    "Register cleanup fn to run after the current live context client disconnects.
All cleanup functions are run in the order they are registered and take no
parameters.")
  (send! [this payload]
    "Send command to connected client."))

(defprotocol Source
  (current-value [this]
    "Return the current value when first rendering a live component from this source.
If this source is not immediate (doesn't have value until later), it should
return nil or some application level marker value.

Avoid blocking as it will slow down page rendering.")
  (listen! [this callback]
    "Add new listener callback for this source. When ever the source value
changes, the listener will be called with the new value.
The source is responsible for deduping values and not calling listeners
again if another change has the same value as the last one.

Returns 0-arity function that will remove the listener when called.")

  (close! [this] "Close this source and cleanup any server resources."))

;; Source where a value can be written to.
;; Many sources are just for listening for values, but some have read/write
;; behaviour (like atoms)
(defprotocol Writable
  (write! [this new-value] "Write new value for this source."))
