(ns ripley.browser-test
  "Test with playwright. Runs a test server on port 4455."
  (:require [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [ripley.html :as h]
            [wally.main :as w]
            [wally.selectors :as ws]
            [ripley.live.source :as source]
            [clojure.test :refer [deftest is testing] :as t]))

(defonce page (w/make-page {:headless true}))

(defonce test-component
  (atom #(h/html [:div "nothing to test"])))

(t/use-fixtures :once #(w/with-page page (%)))

(let [ws-handler (context/connection-handler "/ws")]
  (defn page-handler [{uri :uri :as req}]
    (if (= uri "/ws")
      (ws-handler req)
      (h/render-response
       #(do (h/out! "<!DOCTYPE html>\n")
            (h/html
             [:html
              [:head
               [:meta {:charset "UTF-8"}]
               (h/live-client-script "/ws")]
              [:body (@test-component)]]))))))

(defonce test-server
  (server/run-server page-handler {:port 4455}))

(defmacro with-page
  [page & test-body]

  `(do
     (reset! test-component (fn [] ~page))
     (w/navigate "http://localhost:4455/")
     ~@test-body))


(deftest counter-page
  (with-page
    (let [[?count _set-count! swap-count!] (source/use-state 0)]
      (h/html
       [:div
        [::h/live ?count
         #(h/html
           [:div.counter "Clicked " % " times."])]
        [:button.inc {:on-click #(swap-count! inc)}
         "click me"]]))

    (is (w/wait-for (ws/text "Clicked 0 times.")))
    (w/click :inc)
    (is (w/wait-for (ws/text "Clicked 1 times.")))
    (w/click :inc)
    (is (w/wait-for (ws/text "Clicked 2 times.")))))
