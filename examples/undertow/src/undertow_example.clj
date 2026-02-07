(ns undertow-example
  (:require [ring.adapter.undertow :refer [run-undertow]]
            [reitit.ring.middleware.parameters :as params]
            [reitit.ring :as ring]
            [ripley.live.context :as context]
            [ripley.html :as h]))

(defn page []
  (h/out! "<!DOCTYPE html>")
  (let [counter (atom 0)]
    (h/html
     [:html
      [:head
       [:title "Ripley Undertow WS example"]
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

(def handler
  (ring/ring-handler
    (ring/router
      [["/" {:get page-handler}]
       ["/ws" {:handler (context/connection-handler "/ws" :implementation :undertow)}]])))

(defn start [handler]
  (run-undertow handler {:port 3000}))

(defn -main []
  (start handler))
