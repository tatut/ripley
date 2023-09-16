(ns ripley.live.impl.http-kit
  "http-kit WebSocket connection implementation."
  (:require [ripley.live.protocols :as p]
            [org.httpkit.server :as server]
            [clojure.tools.logging :as log]
            [ring.middleware.params :as params]))


(defn- send-fn! [chd data]
  (let [ch @chd]
    (server/send!
     ch
     (if (server/websocket? ch)
       data
       (str "data: " data "\n\n"))
     false)))

(defn handler [uri options initialize-connection]
  (params/wrap-params
   (fn [req]
     (when (= uri (:uri req))
       (try
         (let [id (-> req :query-params (get "id") java.util.UUID/fromString)
               ch* (object-array 1)
               send! (partial send-fn! (delay (aget ch* 0)))
               callbacks (initialize-connection id send!)]
           (server/as-channel
            req
            {:on-open
             (fn [ch]
               (aset ch* 0 ch)
               (log/debug "Connected, ws? " (server/websocket? ch))
               ;; If connection is not WebSocket, initialize SSE headers
               (when-not (server/websocket? ch)
                 (server/send! ch {:status 200
                                   :headers {"Content-Type" "text/event-stream"}}
                               false))
               (p/on-open callbacks))

             :on-close
             (fn [_ch status]
               (p/on-close callbacks status))

             :on-receive
             (fn [_ch ^String data]
               (p/on-receive callbacks data))}))
         (catch Exception e
           (log/error e "Unable to initialize live context")))))))
