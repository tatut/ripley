(ns ripley.live.context
  "Live context"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go go-loop <! >! timeout]]
            [org.httpkit.server :as server]
            [ring.middleware.params :as params]
            [cheshire.core :as cheshire]
            [ring.util.io :as ring-io]
            [clojure.java.io :as io]
            [ripley.impl.output :refer [*html-out*]]))

(set! *warn-on-reflection* true)

(def ^:dynamic *live-context* nil)
(def ^:dynamic *component-id* nil)

(defmacro with-component-id [id & body]
  `(binding [*component-id* ~id]
     ~@body))

(defn- cleanup-before-render
  "Cleanup component from context before it is rerendered.
  This will remove stale callbacks and children. Recursively cleans up child components."
  [state id]
  (let [{child-component-ids :children
         callback-ids :callbacks} (get-in state [:components id])
        state (reduce cleanup-before-render state child-component-ids)]
    (-> state
        (update-in [:components id] assoc :children #{} :callbacks #{})
        (update :components #(reduce dissoc % child-component-ids))
        (update :callbacks #(reduce dissoc % callback-ids)))))

(defn- start-component
  "Starts go block for live component updates."
  [{state :state :as ctx}
   id
   {:keys [source component patch]
    :or {patch :replace}}
   wait-ch]

  (let [ch (p/to-channel source)]
    (go
      (when wait-ch
        (<! wait-ch))
      (loop [val (<! ch)]
        (when (= :replace patch)
          (swap! (:state ctx) cleanup-before-render id))
        (if (= val :ripley.live/tombstone)
          ;; source says this component should be removed, send deletion patch to client
          ;; and close the source
          (do (server/send! (:channel @state) (str id ":D"))
              (p/close! source))

          (when val
            (binding [*live-context* ctx
                      *html-out* (java.io.StringWriter.)
                      *component-id* id]

              (try
                (component val)
                (server/send! (:channel @state)
                              (str id
                                   (case patch
                                     :replace ":R:"
                                     :append ":A:"
                                     :prepend ":P:")
                                   (str *html-out*)))
                (catch Exception e
                  (println "Component render threw exception: " e))))
            (recur (<! ch))))))))


(defrecord DefaultLiveContext [state]
  p/LiveContext
  (register! [this source component opts]
    ;; context is per rendered page, so will be registrations will be called from a single thread
    (let [{wait-ch :wait-ch
           id :next-id} @state]
      (swap! state
             #(let [state (-> %
                              (update :next-id inc)
                              (update :components assoc id
                                      (merge {:source source
                                              :component component
                                              :children #{}
                                              :callbacks #{}}
                                             (select-keys opts [:patch]))))]
                (if *component-id*
                  ;; Register new component as child of if we are inside a component
                  (update-in state [:components *component-id* :children] conj id)
                  state)))
      (start-component this id
                       (merge
                        {:source source :component component}
                        (select-keys opts [:patch]))
                       wait-ch)
      id))

  (register-callback! [this callback]
    (let [id (:next-id @state)]
      (swap! state #(let [state (-> %
                                    (update :next-id inc)
                                    (update :callbacks assoc id callback))]
                      (if *component-id*
                        (update-in state [:components *component-id* :callbacks] conj id)
                        state)))
      id))

  (deregister! [this id]
    (let [[source _comp] (get-in @state [:components id])]
      (p/close! source)
      (swap! state update :components dissoc id)
      nil))

  (send! [this payload]
    (let [ch (:channel @state)]
      (server/send! ch payload))))

(defonce current-live-contexts (atom {}))

(defn- cleanup-ctx [ctx]
  (let [{:keys [context-id components]} @(:state ctx)]
    (swap! current-live-contexts dissoc context-id)
    (doseq [{source :source} (vals components)]
      (p/close! source))))

(defn render-with-context
  "Return input stream that calls render-fn with a new live context bound."
  [render-fn]
  (let [id (java.util.UUID/randomUUID)
        wait-ch (async/chan)
        state (atom {:next-id 0
                     :status :not-connected
                     :components {}
                     :callbacks {}
                     :context-id id
                     :wait-ch wait-ch})
        ctx (->DefaultLiveContext state)]
    (ring-io/piped-input-stream
     (fn [out]
       (with-open [w (io/writer out)]
         (binding [*live-context* ctx
                   *html-out* w]
           (try
             (render-fn)
             (finally
               (when-not (zero? (:next-id @(:state ctx)))
                 ;; Some live components were rendered by the page,
                 ;; add this context as awaiting websocket.
                 (swap! current-live-contexts assoc id ctx)
                 (go
                   (<! (timeout 30000))
                   (when (= :not-connected (:status @state))
                     (println "Removing context that wasn't connected within 30 seconds")
                     (async/close! wait-ch)
                     (cleanup-ctx ctx))))))))))))

(defn current-context-id []
  (:context-id @(:state *live-context*)))


(defn connection-handler [uri]
  (params/wrap-params
   (fn [req]
     (when (= uri (:uri req))
       (let [id (-> req :query-params (get "id") java.util.UUID/fromString)
             ctx (get @current-live-contexts id)]
         (if-not ctx
           {:status 404
            :body "No such live context"}
           (do
             ;; Close the wait-ch so the registered live component go-blocks will start running
             (async/close! (:wait-ch @(:state ctx)))
             (server/with-channel req channel
               (doto channel
                 (server/on-close (fn [_status]
                                    (cleanup-ctx ctx)))
                 (server/on-receive (fn [^String data]
                                      (let [idx (.indexOf data ":")
                                            id (Long/parseLong (if (pos? idx)
                                                                 (subs data 0 idx)
                                                                 data))
                                            args (if (pos? idx)
                                                   (seq (cheshire/decode (subs data (inc idx))))
                                                   nil)
                                            callback (-> ctx :state deref :callbacks (get id))]
                                        (if-not callback
                                          (println "Got callback with unrecognized id: " id)
                                          (apply callback args))))))
               (swap! (:state ctx) assoc
                      :channel channel
                      :status :connected)))))))))
