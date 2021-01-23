(ns ripley.live.source-test
  (:require [ripley.live.source :as source]
            [clojure.test :as t :refer [deftest testing is]]
            [ripley.live.protocols :as p]
            [clojure.string :as str]))

(deftest simple-computed-test
  (let [a (atom 30)
        b (atom 11)
        c (source/computed + (source/to-source a) (source/to-source b))
        unlisten! (p/listen! c #(println (str "a+b=" %)))]

    (testing "Changing input source calls computed listeners"
      (is (= "a+b=42\n"
             (with-out-str
               (swap! b inc))))

      (is (= "a+b=1042\n"
             (with-out-str
               (reset! a 1030))))

      (testing "listener is not called if value isn't actually changed"
        (is (str/blank?
             (with-out-str
               (swap! b identity))))))

    (testing "Listeners not called after removing it"
      (unlisten!)
      (is (str/blank?
           (with-out-str
             (swap! a inc)))))))
