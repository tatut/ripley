(ns ripley.integration.redis-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [ripley.integration.redis :as redis]
            [ripley.live.protocols :as p]
            [taoensso.carmine :as car]))


(deftest pubsub
  (let [s (redis/pubsub-listener-source
           {:parse-message (fn [chan msg]
                             {:channel chan
                              :msg msg})}
           "testing")
        v (atom nil)]
    (is (nil? (p/current-value s)))
    (p/listen! s #(reset! v %))

    (testing "Publishing redis message sends to source"
      ;; Send redis msg
      (car/wcar
       {}
       (car/publish "testing" "Hello there"))

      (Thread/sleep 100)

      (is (= {:channel "testing"
              :msg "Hello there"}
             @v)))

    (testing "Closing source no longer listens"
      (reset! v nil)
      (p/close! s)
      (car/wcar
       {}
       (car/publish "testing" "Are you still there?"))
      (Thread/sleep 100)
      (is (nil? @v)))))
