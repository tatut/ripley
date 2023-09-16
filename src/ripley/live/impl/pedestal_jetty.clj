(ns ripley.live.impl.pedestal-jetty
  "Pedestal (pedestal.io) + jetty WebSocket context support.
  NOTE: bring your own pedestal dependencies, Ripley does not provide them."
  (:require [io.pedestal.http.jetty.websockets :as ws]
            [clojure.core.async :as async]
            [ripley.live.protocols :as p]
            [clojure.tools.logging :as log])
  (:import (org.eclipse.jetty.websocket.api Session WebSocketConnectionListener WebSocketListener)
           (org.eclipse.jetty.websocket.servlet ServletUpgradeRequest)))

(defn- send-fn! [session-delay data]
  (let [^Session session @session-delay]
    (-> session .getRemote (.sendString data))))

(defn make-ws-listener
  [^ServletUpgradeRequest req {::keys [initialize-connection]}]
  (let [id (-> req .getParameterMap (.get "id"))
        session* (object-array 1)
        send! (partial send-fn! (delay (aget session* 0)))
        callbacks (initialize-connection id send!)]
    (reify
      WebSocketConnectionListener
      (onWebSocketConnect [_this ws-session]
        (aset session* 0 ws-session)
        (p/on-open callbacks))

      (onWebSocketClose [_this status _reason]
        (p/on-close callbacks status))

      (onWebSocketError [_this _cause]
        (p/on-close callbacks -1))

      WebSocketListener
      (onWebSocketText [_this msg]
        (p/on-receive callbacks msg))

      (onWebSocketBinary [_this payload offset length]
        (log/warn "Unexpected binary message, payload: " payload ", offset: " offset ", length: " length)))))


(defn handler
  "Returns a context configurator that you add to the 
  HTTP container options when starting a pedestal service."
  [uri _options initialize-connection]
  (fn [ctx]
    (ws/add-ws-endpoints
     ctx
     {uri {::initialize-connection initialize-connection}}
     {:listener-fn (fn [req _response ws-map]
                     (make-ws-listener req ws-map))})))
