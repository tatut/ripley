(ns ripley.live.impl.undertow
  (:require [ripley.live.protocols :as p]
            [ring.adapter.undertow.websocket :as ws]
            [ring.middleware.params :as params]))

(defn send-fn! [channel-delay data]
  (let [channel @channel-delay]
    (ws/send data channel)))

(defn handler [_uri _options initialize-connection]
  (params/wrap-params
    (fn [request]
      (let [id        (-> request :query-params (get "id") java.util.UUID/fromString)
            ch*       (object-array 1)
            send!     (partial send-fn! (delay (aget ch* 0)))
            callbacks (initialize-connection id send!)]
        {:undertow/websocket 
         {:on-open
          (fn [{:keys [channel]}]
            (aset ch* 0 channel)
            (p/on-open callbacks))
          :on-message
          (fn [{:keys [_channel data]}]
            (p/on-receive callbacks data))
          :on-close-message
          (fn [{:keys [_channel message]}]
            (p/on-close callbacks message))}}))))
