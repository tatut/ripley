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


(def ^:dynamic *live-context* nil)
(def ^:dynamic *component-id* nil)

(defmacro with-component-id [id & body]
  `(binding [*component-id* ~id]
     ~@body))

(defrecord DefaultLiveContext [state]
  p/LiveContext
  (register! [this source component]
    ;; context is per rendered page, so will be registrations will be called from a single thread
    (let [id (:next-id @state)]
      (swap! state
             #(let [state (-> %
                              (update :next-id inc)
                              (update :components assoc id {:source source
                                                            :component component
                                                            :children #{}
                                                            :callbacks #{}}))]
                (if *component-id*
                  ;; Register new component as child of if we are inside a component
                  (update-in state [:components *component-id* :children] conj id)
                  state)))
      id))

  (register-callback! [this callback]
    (let [id (str (:next-id @state))]
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
      nil)))

(defonce current-live-contexts (atom {}))

(defn- cleanup-ctx [ctx]
  (let [{:keys [context-id components]} @(:state ctx)]
    (swap! current-live-contexts dissoc context-id)
    (doseq [{source :source} (vals components)]
      (p/close! source))))

(defn render-with-context
  "Return input stream that calls render-fn with a new live context bound."
  [render-fn]
  (println "render-with-context " render-fn)
  (let [id (java.util.UUID/randomUUID)
        state (atom {:next-id 0
                     :status :not-connected
                     :components {}
                     :callbacks {}
                     :context-id id})
        ctx (->DefaultLiveContext state)]
    (ring-io/piped-input-stream
     (fn [out]
       (println "inside piped-input-stream")
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
                     (cleanup-ctx ctx))))))))))))

(defn current-context-id []
  (:context-id @(:state *live-context*)))



(defn connection-handler [uri]
  (params/wrap-params
   (fn [req]
     (println "CONNECTION HANDLER: " (pr-str req))
     (println uri " == " (:uri req) "? " (= uri (:uri req)))
     (when (= uri (:uri req))
       (let [id (-> req :query-params (get "id") java.util.UUID/fromString)
             ctx (get @current-live-contexts id)]
         (println "ID: " id "; CTX: " ctx)
         (if-not ctx
           {:status 404
            :body "No such live context"}
           (server/with-channel req channel
             (doto channel
               (server/on-close (fn [_status]
                                  (cleanup-ctx ctx)))
               (server/on-receive (fn [data]
                                    (let [idx (.indexOf data ":")
                                          id (if (pos? idx)
                                               (subs data 0 idx)
                                               data)
                                          args (if (pos? idx)
                                                 (seq (cheshire/decode (subs data (inc idx))))
                                                 nil)
                                          callback (-> ctx :state deref :callbacks (get id))]
                                      (if-not callback
                                        (println "Got callback with unrecogniced id: " id)
                                        (apply callback args))))))
             (swap! (:state ctx) assoc
                    :connection channel
                    :status :connected)
             ;; PENDING: watch for new live components
             ;; - needs to bind live context when rerendering
             ;; - how to remove old callbacks (needs to be recorded within a live component)
             (doseq [[id {:keys [source component]}] (:components @(:state ctx))
                     :let [ch (p/to-channel source)]]
               (go-loop [val (<! ch)]
                 (when val
                   (try
                     (server/send! channel
                                   (str id ":R:"
                                        (str
                                         (binding [*html-out* (java.io.StringWriter.)]
                                           (component val)
                                           (str *html-out*))))))
                   (recur (<! ch))))))))))))
