(ns ripley.live.context
  "Live context"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go go-loop <! >! timeout]]
            [org.httpkit.server :as server]
            [ring.middleware.params :as params]
            [cheshire.core :as cheshire]
            [ring.util.io :as ring-io]
            [clojure.java.io :as io]
            [ripley.impl.output :refer [*html-out*]]
            [ripley.live.patch :as patch]
            [ripley.impl.dynamic :as dynamic]
            [taoensso.timbre :as log])
  (:import (java.util.concurrent Executors TimeUnit ScheduledExecutorService Future)))

(set! *warn-on-reflection* true)

(defn- cleanup-before-render
  "Cleanup component from context before it is rerendered.
  This will remove stale callbacks and children. Recursively cleans up child components."
  [unlisten? state id]
  (let [{child-component-ids :children
         callback-ids :callbacks
         unlisten :unlisten} (get-in state [:components id])
        state (reduce (partial cleanup-before-render true) state child-component-ids)]
    ;; Any child components that a parent rerender will overwrite
    ;; must unlisten from their sources, otherwise we will get
    ;; content for non-existant elements if it changes.
    (when (and unlisten? unlisten)
      (unlisten))
    (-> state
        (update-in [:components id] assoc
                   :children #{}
                   :callbacks #{}
                   :unlisten (if unlisten? nil unlisten))
        (update :components #(reduce dissoc % child-component-ids))
        (update :callbacks #(reduce dissoc % callback-ids)))))

(defn- update-component!
  "Callback for source changes that send updates to component."
  [{:keys [state send-fn!] :as ctx}
   id
   {:keys [source component patch parent did-update should-update?]
    :or {patch :replace
         should-update? (constantly true)}}
   val]
  (log/trace "component " id " has " val)
  (when (= :replace patch)
    (swap! (:state ctx) (partial cleanup-before-render false) id))
  (if (= val :ripley.live/tombstone)
    ;; source says this component should be removed, send deletion
    ;; patch to client (unless it targets parent, like attributes)
    ;; and close the source
    (do
      (when-not (patch/target-parent? patch)
        (send-fn! (:channel @state)
                  [(patch/delete id)]))
      (p/close! source))

    (when (and (some? val) ;; PENDING: allow nil as value now that we are not using channels
               (should-update? val))
      (let [target-id (if (patch/target-parent? patch)
                        parent
                        id)
            payload (try
                      (case (patch/render-mode patch)
                        :json (component val)
                        :html (binding [dynamic/*live-context* ctx
                                        *html-out* (java.io.StringWriter.)]
                                (dynamic/with-component-id id
                                  (component val)
                                  (str *html-out*))))
                      (catch Exception e
                        (println "Component render threw exception: " e)))
            patches [(patch/make-patch patch target-id payload)]]
        (send-fn! (:channel @state)
                  (if-let [[patch payload]
                           (when did-update
                             (did-update val))]
                    ;; If there is a did-update handler, send that as well
                    (conj patches (patch/make-patch patch target-id payload))
                    patches))))))


(defrecord DefaultLiveContext [send-fn! state]
  p/LiveContext
  (register! [this source component opts]
    ;; context is per rendered page, so will be registrations will be called from a single thread
    (let [parent-component-id dynamic/*component-id*
          {id :last-component-id}
          (swap! state
                 #(let [id (:next-id %)
                        state (-> %
                                  (assoc :last-component-id id)
                                  (update :next-id inc)
                                  (update :components assoc id
                                          (merge {:source source
                                                  :component component
                                                  :children #{}
                                                  :callbacks #{}}
                                                 (select-keys opts [:patch :did-update]))))]
                    (if parent-component-id
                      ;; Register new component as child of if we are inside a component
                      (update-in state [:components parent-component-id :children] conj id)
                      state)))]
      ;; Source may be missing (some parent components are registered without their own source)
      ;; If source is present, add listener for it
      (when source
        (let [unlisten
              (p/listen!
               source
               (partial update-component!
                        this id
                        (merge
                         {:source source :component component}
                         (select-keys opts [:patch :parent :did-update :should-update?]))))]
          (swap! state assoc-in [:components id :unlisten] unlisten)))
      id))

  (register-callback! [_this callback]
    (let [parent-component-id dynamic/*component-id*
          {id :last-callback-id}
          (swap! state
                 #(let [id (:next-id %)
                        state (-> %
                                  (assoc :last-callback-id id)
                                  (update :next-id inc)
                                  (update :callbacks assoc id callback))]
                    (if parent-component-id
                      (update-in state [:components parent-component-id :callbacks] conj id)
                      state)))]
      id))
  (register-cleanup! [_this cleanup-fn]
    (swap! state update :cleanup (fnil conj []) cleanup-fn)
    nil)

  (deregister! [_this id]
    (let [{:keys [source unlisten]} (get-in @state [:components id])]
      (when unlisten (unlisten))
      (when source (p/close! source))
      (swap! state update :components dissoc id)
      nil))

  (send! [_this payload]
    (let [ch (:channel @state)]
      (send-fn! ch payload))))

(defonce current-live-contexts (atom {}))

(defn- cleanup-ctx [ctx]
  (let [{:keys [context-id components cleanup ping-task]} @(:state ctx)]
    (when ping-task
      (.cancel ^Future ping-task false))
    (swap! current-live-contexts dissoc context-id)
    (doseq [{source :source} (vals components)
            :when source]
      (p/close! source))
    (doseq [c cleanup]
      (c))))

(defn initial-context-state
  "Return initial state for a new context"
  [wait-ch]
  {:next-id 0
   :status :not-connected
   :components {}
   :callbacks {}
   :context-id (java.util.UUID/randomUUID)
   :wait-ch wait-ch})

(defn- default-send-fn! [ch data]
  (let [data (cheshire/encode data)]
    (server/send!
     ch
     (if (server/websocket? ch)
       data
       (str "data: " data "\n\n"))
     false)))

(defonce ping-executor
  (delay (Executors/newScheduledThreadPool 0)))

(defn- schedule-ping [ch ping-interval]
  (.scheduleWithFixedDelay
   ^ScheduledExecutorService @ping-executor
   #(server/send! ch "!")
   ping-interval ping-interval
   TimeUnit/SECONDS))

(defn- queued-send-fn [state-atom send-fn!]
  (fn [ch data]
    (if (nil? ch)
      ;; No connection yet, queue this send
      (swap! state-atom update :send-queue (fnil conj []) data)
      (send-fn! ch data))))

(defn render-with-context
  "Return input stream that calls render-fn with a new live context bound."
  [render-fn]
  (let [wait-ch (async/chan)
        {id :context-id :as initial-state} (initial-context-state wait-ch)
        state (atom initial-state)
        ctx (->DefaultLiveContext
             (queued-send-fn state default-send-fn!) state)]
    (swap! current-live-contexts assoc id ctx)
    (ring-io/piped-input-stream
     (fn [out]
       (with-open [w (io/writer out)]
         (binding [dynamic/*live-context* ctx
                   *html-out* w]
           (try
             (render-fn)
             (catch Throwable t
               (println "Exception while rendering!" t))
             (finally
               (if (empty? (:components @(:state ctx)))
                 ;; No live components rendered, remove context immediately
                 (do
                   (log/debug "No live components, remove context with id: " id)
                   (swap! current-live-contexts dissoc id))

                 ;; Some live components were rendered by the page,
                 ;; add this context as awaiting websocket.
                 (go
                   (<! (timeout 30000))
                   (when (= :not-connected (:status @state))
                     (log/info "Removing context that wasn't connected within 30 seconds")
                     (async/close! wait-ch)
                     (cleanup-ctx ctx))))))))))))

(defn current-context-id []
  (:context-id @(:state dynamic/*live-context*)))

(defn- post-callback [ctx body]
  (let [[id & args] (-> body slurp cheshire/decode)
        callback (-> ctx :state deref :callbacks (get id))]
    (if callback
      (do (dynamic/with-live-context ctx
            (apply callback args))
          {:status 200})
      {:status 404
       :body "Unknown callback"})))

(defn connection-handler
  "Handler that initiates WebSocket (or SSE) connection with ripley server
  and browser.

  Options:

  :ping-interval  Ping interval seconds. If specified, send ping message to client periodically.
                  This can facilitate keeping alive connections when load balancers have some
                  idle timeout. "
  [uri & {:keys [ping-interval]}]
  (params/wrap-params
   (fn [req]
     (when (= uri (:uri req))
       (let [id (-> req :query-params (get "id") java.util.UUID/fromString)
             ctx (get @current-live-contexts id)]
         (if-not ctx
           {:status 404
            :body "No such live context"}
           (if (= :post (:request-method req))
             (post-callback ctx (:body req))
             ;; Serve events as WebSocket or SSE
             (server/as-channel
              req
              {:on-close
               (fn [_ch _status]
                 (cleanup-ctx ctx))
               :on-receive
               (fn [_ch ^String data]
                 (if (= data "!")
                   (swap! (:state ctx) assoc :last-ping-received (System/currentTimeMillis))
                   (let [idx (.indexOf data ":")
                         id (Long/parseLong (if (pos? idx)
                                              (subs data 0 idx)
                                              data))
                         args (if (pos? idx)
                                (seq (cheshire/decode (subs data (inc idx)) keyword))
                                nil)
                         callback (-> ctx :state deref :callbacks (get id))]
                     (if-not callback
                       (println "Got callback with unrecognized id: " id)
                       (dynamic/with-live-context ctx
                         (apply callback args))))))
               :on-open
               (fn [ch]
                 (log/debug "Connected, ws? " (server/websocket? ch))
                 ;; If connection is not WebSocket, initialize SSE headers
                 (when-not (server/websocket? ch)
                   (server/send! ch {:status 200
                                     :headers {"Content-Type" "text/event-stream"}}
                                 false))

                 ;; Send any items in the send queue
                 (doseq [queued-data (:send-queue @(:state ctx))]
                   (default-send-fn! ch queued-data))

                 ;; Close the wait-ch so the registered live component
                 ;; go-blocks will start running
                 ;; TODO: We shouldn't need this anymore
                 (async/close! (:wait-ch @(:state ctx)))
                 (swap! (:state ctx)
                        #(-> %
                             (dissoc :send-queue :wait-ch)
                             (assoc
                              :channel ch
                              :ping-task (when ping-interval
                                           (schedule-ping ch ping-interval))
                              :status :connected))))}))))))))
