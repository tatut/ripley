(ns pedestal-example
  (:require [io.pedestal.http :as http]
            [ripley.live.context :as context]
            [ripley.html :as h]))

(defn page []
  (h/out! "<!DOCTYPE html>")
  (let [counter (atom 0)]
    (h/html
     [:html
      [:head
       [:title "Ripley Pedestal WS example"]
       (h/live-client-script "/ws")]
      [:body
       [:div
        "You have clicked the button "
        [::h/live-let [c counter]
         [:span c]]
        " times."]
       [:div
        [:button {:on-click #(swap! counter inc)} "click me"]]]])))

(defn page-handler [_req]
  (h/render-response page))

(def routes
  `[[["/" {:get page-handler}]]])


(def service
  {:env :dev
   ::http/join? false
   ::http/routes routes
   ::http/type :jetty
   ::http/secure-headers {:content-security-policy-settings {:script-src "'unsafe-inline'"}}
   ::http/container-options
   {:context-configurator (context/connection-handler
                           "/ws"
                           :implementation :pedestal-jetty)}
   ::http/port 3000})

(defonce runnable-service
  (-> service
      http/default-interceptors
      http/dev-interceptors
      http/create-server))

(defn -main []
  (http/start runnable-service))
