(ns ripley.live.context
  "Live context"
  (:require [ripley.live.protocols :as p]
            [clojure.core.async :as async :refer [go <! timeout]]
            [cheshire.core :as cheshire]
            [ring.util.io :as ring-io]
            [clojure.java.io :as io]
            [ripley.impl.output :refer [*html-out*]]
            [ripley.live.patch :as patch]
            [ripley.impl.dynamic :as dynamic]
            [clojure.tools.logging :as log]
            [ripley.impl.util :refer [arity]])
  (:import (java.util.concurrent Executors TimeUnit ScheduledExecutorService Future)))

(set! *warn-on-reflection* true)

(defn- cleanup-component
  "Cleanup component from context.
  This will remove stale callbacks and children. Recursively cleans up child components."
  [unlisten? state id]
  (let [{child-component-ids :children
         callback-ids :callbacks
         unlisten :unlisten} (get-in state [:components id])
        state (reduce (partial cleanup-component true) state child-component-ids)]
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
  [{:keys [state] :as ctx}
   id
   {:keys [source component patch parent did-update should-update?]
    :or {patch :replace
         should-update? (constantly true)}}
   val]
  (log/trace "component " id " has " val)
  (let [update? (should-update? val)
        send! (:send! @state)]
    (when (and update? (= :replace patch))
      (swap! state (partial cleanup-component false) id))
    (cond
      ;; source says this component should be removed, send deletion
      ;; patch to client (unless it targets parent, like attributes)
      ;; and close the source
      (= val :ripley.live/tombstone)
      (do
        (when-not (patch/target-parent? patch)
          (send! [(patch/delete id)]))
        (p/close! source))

      ;; Marker that this component was already removed from client
      ;; by a parent, don't send deletion patch just close source.
      (= val :ripley.live/tombstone-no-funeral)
      (p/close! source)

      :else
      (when update?
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
                        (catch Throwable e
                          (log/error e "Uncaught exception in component render"
                                     ", id: " id
                                     ", component fn: " component)))
              patches [(patch/make-patch patch target-id payload)]]
          (send!
           (if-let [[patch payload]
                    (when did-update
                      (did-update val))]
             ;; If there is a did-update handler, send that as well
             (conj patches (patch/make-patch patch target-id payload))
             patches)))))))

(defn- bind [bindings function]
  (if-not (seq bindings)
    function
    (let [current-bindings (select-keys (get-thread-bindings) bindings)]
      (case (arity function)
        0 (fn [] (with-bindings current-bindings (function)))
        1 (fn [a] (with-bindings current-bindings (function a)))
        2 (fn [a b] (with-bindings current-bindings (function a b)))
        3 (fn [a b c] (with-bindings current-bindings (function a b c)))
        4 (fn [a b c d] (with-bindings current-bindings (function a b c d)))
        5 (fn [a b c d e] (with-bindings current-bindings (function a b c d e)))
        6 (fn [a b c d e f] (with-bindings current-bindings (function a b c d e f)))
        7 (fn [a b c d e f g] (with-bindings current-bindings (function a b c d e f g)))
        (fn [& args] (with-bindings (apply function args)))))))

(defrecord DefaultLiveContext [state bindings]
  p/LiveContext
  (register! [this source component opts]
    ;; context is per rendered page, so will be registrations will be called from a single thread
    (let [component (bind bindings component)
          parent-component-id dynamic/*component-id*
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
    (let [callback (bind bindings callback)
          parent-component-id dynamic/*component-id*
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
    (let [current-state @state
          {source :source} (get-in current-state [:components id])]
      (cleanup-component true current-state id)
      (when source (p/close! source))
      (swap! state update :components dissoc id)
      nil))

  (send! [_this payload]
    (if-let [send! (:send! @state)]
      ;; Connection is open, send it
      (send! payload)
      ;; No connection yet, queue this send
      (swap! state update :send-queue (fnil conj []) payload))))

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
  []
  {:next-id 0
   :status :not-connected
   :components {}
   :callbacks {}
   :context-id (java.util.UUID/randomUUID)})



(defonce ping-executor
  (delay (Executors/newScheduledThreadPool 0)))

(defn- schedule-ping [send! ping-interval]
  (.scheduleWithFixedDelay
   ^ScheduledExecutorService @ping-executor
   #(send! "!")
   ping-interval ping-interval
   TimeUnit/SECONDS))

(defn- empty-context? [{state :state}]
  (let [{:keys [components callbacks]} @state]
    (and (empty? components)
         (empty? callbacks))))

(defn render-with-context
  "Return input stream that calls render-fn with a new live context bound."
  ([render-fn]
   (render-with-context render-fn {}))
  ([render-fn {:keys [bindings]
               :or {bindings #{}} :as _context-options}]
   (let [{id :context-id :as initial-state} (initial-context-state)
         state (atom initial-state)
         ctx (->DefaultLiveContext state bindings)]
     (swap! current-live-contexts assoc id ctx)
     (ring-io/piped-input-stream
      (fn [out]
        (with-open [w (io/writer out)]
          (binding [dynamic/*live-context* ctx
                    *html-out* w]
            (try
              (render-fn)
              (catch Throwable t
                (log/error t "Exception while rendering!"))
              (finally
                (if (empty-context? ctx)
                 ;; No live components  rendered or callbacks registered, remove context immediately
                  (do
                    (log/debug "No live components, remove context with id: " id)
                    (swap! current-live-contexts dissoc id))

                 ;; Some live components were rendered by the page,
                 ;; add this context as awaiting websocket.
                  (go
                    (<! (timeout 30000))
                    (when (= :not-connected (:status @state))
                      (log/info "Removing context that wasn't connected within 30 seconds")
                      (cleanup-ctx ctx)))))))))))))

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

(defn initialize-connection
  "Initialize a connection for the given live context id and send function.
  The [[send!]] function must have arity of 1 that takes a (String) data to
  send to the client.

  If no context for the given id exists, throws an exception.

  Returns an instance of Callbacks which the caller must configure on the
  underlying connection."
  [{:keys [ping-interval]} context-id send!]
  (let [ctx (get @current-live-contexts context-id)]
    (if-not ctx
      (throw (ex-info "Unknown live context" {:context-id context-id}))
      (reify p/ConnectionCallbacks
        (on-close [_ _status]
          (cleanup-ctx ctx))
        (on-receive [_ data]
          (if (= data "!")
            (swap! (:state ctx) assoc :last-ping-received (System/currentTimeMillis))
            (let [[id args reply-id] (cheshire/decode data keyword)
                  callback (some-> ctx :state deref :callbacks (get id))]
              (if-not callback
                (log/debug "Got callback with unrecognized id: " id)
                (try
                  (dynamic/with-live-context ctx
                    (let [res (apply callback args)]
                      (when-let [reply (and reply-id (map? res) (:ripley/success res))]
                        (send! (cheshire/encode [(patch/callback-success [reply-id reply])])))))
                  (catch Throwable t
                    (if-let [reply (and reply-id (:ripley/failure (ex-data t))
                                        (merge {:message (.getMessage t)}
                                               (dissoc (ex-data t) :ripley/failure)))]
                      (send! (cheshire/encode [(patch/callback-error [reply-id reply])]))
                      ;; No on-failure reply was requested, log this as an uncaught
                      (log/error t "Uncaught exception while processing callback"))))))))
        (on-open [_]
          (let [{send-queue :send-queue}
                (swap! (:state ctx) assoc
                       :send! (fn send-patches-as-json [patches]
                                (send! (cheshire/encode patches))))]
            ;; Send any items in the send queue
            (doseq [queued-data send-queue
                    :let [data (cheshire/encode queued-data)]]
              (send! data)))

          (swap! (:state ctx)
                 #(-> %
                      (dissoc :send-queue)
                      (assoc
                       :ping-task (when ping-interval
                                    (schedule-ping send! ping-interval))
                       :status :connected))))))))

(defn connection-handler
  "Create a handler that initiates WebSocket (or SSE) connection with ripley server
  and browser.

  Options:

  :implementation the name of the server implementation to use (defaults to :http-kit)
                  will call `ripley.live.impl.<implementation>/handler` to create the handler
  :ping-interval  Ping interval seconds. If specified, send ping message to client periodically.
                  This can facilitate keeping alive connections when load balancers have some
                  idle timeout.

  See other server implementation ns for possible other options.
  "
  [uri & {:keys [implementation]
          :or {implementation :http-kit} :as options}]
  (let [implementation-handler-fn
        (requiring-resolve (symbol (str "ripley.live.impl." (name implementation))
                                   "handler"))]
    (implementation-handler-fn uri options
                               (partial initialize-connection options))))
