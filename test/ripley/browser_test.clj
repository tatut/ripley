(ns ripley.browser-test
  "Test with playwright. Runs a test server on port 4455."
  (:require [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [ripley.html :as h]
            [wally.main :as w]
            [wally.selectors :as ws]
            [ripley.live.source :as source]
            [ripley.js :as js]
            [clojure.test :refer [deftest is] :as t]
            [clojure.string :as str]))

(reset! h/dev-mode? true)

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

(deftest after-replace
  (with-page
    (let [[?count _ swap-count!] (source/use-state 0)]
      (h/html
       [:div
        [:script "window._COUNT = 0"]
        [::h/live ?count
         #(h/html [:div {::h/after-replace "window._COUNT++"} "Clicked " % " times."])]
        [:button.inc {:on-click #(swap-count! inc)} "click me"]]))

    (is (w/wait-for (ws/text "Clicked 0 times.")))
    (w/click :inc)

    (is (w/wait-for (ws/text "Clicked 1 times.")))
    (is (= 1 (.evaluate @w/*page* "window._COUNT"))
        "after replace js was called")))

(deftest indicator
  (with-page
      (h/html
        [:div "Click to load"
         [:button.load {:on-click ["document.querySelector('.indicator').style.display=''"
                                   (-> #(do (Thread/sleep 1000) :ok)
                                       (js/on-success "_=>document.querySelector('.indicator').style.display='none'"))]}
          "load"]
         [:div.indicator {:style "display: none;"} "Loading..."]])

      (is (not (w/visible? :indicator)))
      (w/click :load)
      (w/wait-for :indicator {:state :visible})
      (w/wait-for :indicator {:state :hidden})))

(deftest error-callback
  (with-page
    (h/html
      [:div "Click to do stuff"
       [:button.do {:on-click (-> #(throw (ex-info "nope" {:not-going-to :happen}))
                                  (js/on-failure "err=>document.querySelector('.error').innerText=err.message"))}
        "do it"]
       [:div.error]])

    (is (str/blank? (w/text-content :error)))
    (w/click :do)
    (is (w/wait-for (ws/text "nope")))))

(deftest dev-mode-exception
  (with-page
    (let [[?div set-div!] (source/use-state 2)]
      (h/html
       [:div "error component"
        [::h/live ?div
         #(h/html [:div "42 / " % " = " (h/dyn! (/ 42 %))])]
        [:button.crash {:on-click #(set-div! 0)} "divide by zero"]]))

    (is (w/wait-for (ws/text "42 / 2 = 21"))
        "component initially rendered ok")
    (w/click :crash)
    (is (w/wait-for (ws/text "Render exception: Divide by zero")))))
