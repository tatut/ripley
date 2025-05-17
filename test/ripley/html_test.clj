(ns ripley.html-test
  (:require [clojure.test :refer [is deftest testing]]
            [ripley.html :as h]
            [ripley.impl.output :refer [*html-out*]]
            [ripley.impl.dynamic :refer [*live-context*]]
            [ripley.live.context :as live.context]))

(defmacro r [& body]
  `(with-open [out# (java.io.StringWriter.)]
     (binding [*html-out* out#]
       (h/html ~@body))
     (str out#)))

(defmacro r-live [patches-sym html-sym hiccup & body]
  `(let [ctx# (make-array Object 1)
         ~html-sym (slurp
                    (live.context/render-with-context
                     #(do
                        (aset ctx# 0 *live-context*)
                        (h/html ~hiccup))))
         ~patches-sym (atom [])]
     (swap! (:state (aget ctx# 0)) assoc
            :send! (fn [patches#]
                     (reset! ~patches-sym patches#)))
     ~@body))

(deftest close-tag
  (is (= "<div></div>" (r [:div])) "div has close tag")
  (is (= "<input>" (r [:input])) "no close tag for input"))

(def my-truthy 42)
(def my-falsy false)

(deftest boolean-attributes
  (testing "Regular boolean attribute (not live source)"
    (is (= "<input type=\"checkbox\" checked>"
           (r [:input {:type :checkbox :checked true}]))
        "statically known to be true")
    (is (= "<input type=\"checkbox\">"
           (r [:input {:type :checkbox :checked false}]))
        "statically known to be false")
    (is (= "<input type=\"checkbox\" checked>"
           (r [:input {:type :checkbox :checked my-truthy}]))
        "truthy at runtime")
    (is (= "<input type=\"checkbox\">"
           (r [:input {:type :checkbox :checked my-falsy}]))
        "falsy at runtime"))
  (testing "Boolean attribute from live source"
    (let [bool (atom true)]
      (r-live
       patch html [:input {:type :checkbox :checked [::h/live bool]}]
       (is (= "<input data-rl=\"0\" type=\"checkbox\" checked>"
              html))
       (testing "Attribute is removed when changed to false"
         (swap! bool not)
         (Thread/sleep 10)
         (is (= [[0 "@" {:checked nil}]] @patch)
             "patch removes the attribute"))
       (testing "Attribute is added when changed to true"
         (swap! bool not)
         (Thread/sleep 10)
         (is (= [[0 "@" {:checked 1}]] @patch)
             "patch adds the attribute"))))))
