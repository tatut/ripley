(ns ripley.live.context
  "Live context"
  (:require [ripley.live.protocols :as p]
            [ripley.live.connection :as c]
            [clojure.core.async :as async :refer [go <! >! timeout]]
            [org.httpkit.server :as server]))


(def ^:dynamic *live-context* nil)

(defrecord DefaultLiveContext [state]
  p/LiveContext
  (register! [this source component]
    ;; context is per rendered page, so will be registrations will be called from a single thread
    (let [id (:next-id @state)]
      (println "after reg " (swap! state
                                   #(-> %
                                        (update :next-id inc)
                                        (update :components assoc id [source component]))))
      id))

  (deregister! [this id]
    (let [[source _comp] (get-in @state [:components id])]
      (p/close! source)
      (swap! state update :components dissoc id)
      nil))

  #_(send! [this id content]
    (if-let [c (:connection @state)]
      (c/send! c id content)
      (println "Client not connected yet... PENDING: should we queue?"))))

(defonce current-live-contexts (atom {}))

(defn- cleanup-ctx [ctx]
  (let [{:keys [context-id components]} @(:state ctx)]
    (swap! current-live-contexts dissoc context-id)
    (doseq [[source _comp] (vals components)]
      (p/close! source))))

(defn wrap-live-context [handler]
  (fn [req]
    (let [id (java.util.UUID/randomUUID)
          state (atom {:next-id 0
                       :status :not-connected
                       :components {}
                       :context-id id})
          ctx (->DefaultLiveContext state)]
      (try
        (binding [*live-context* ctx]
          (handler req))
        (finally
          (println "CTX AFTER RENDER: " (pr-str ctx))
          (when-not (zero? (:next-id @(:state ctx)))
            ;; Some live components were rendered by the page,
            ;; add this context as awaiting websocket.
            (swap! current-live-contexts assoc id ctx)
            (go
              (<! (timeout 30000))
              (when (= :not-connected (:status @state))
                (println "Removing context that wasn't connected within 30 seconds")
                (cleanup-ctx ctx)))))))))

(defn connection-handler [uri]
  (fn [req]
    (println "CONNECTION HANDLER: " (pr-str req))
    (when (= uri (:uri req))
      (let [id (:id (:query-params req))
            ctx (get @current-live-contexts id)]
        (if-not ctx
          {:status 404
           :body "No such live context"}
          (server/with-channel req channel
            (doto channel
              (server/on-close (fn [_status]
                                 (cleanup-ctx ctx)))
              (server/on-receive (fn [data]
                                   (println "GOT: " data))))
            (swap! (:state ctx) assoc
                   :connection channel
                   :status :connected)))))))
