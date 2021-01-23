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


(deftest split
  (let [full-source (atom {:first-name "Foo" :last-name "Barsky"
                           :age 42})
        [name-source age-source] (source/split full-source
                                               #{:first-name :last-name}
                                               #{:age})]
    (p/listen! name-source (fn [{:keys [first-name last-name]}]
                             (println "NAME:" first-name last-name)))
    (p/listen! age-source (fn [{:keys [age]}]
                            (println "AGE:" age)))

    (testing "Changing name will only print name"
      (is (= "NAME: Bar Barsky\n"
             (with-out-str
               (swap! full-source assoc :first-name "Bar")))))

    (testing "Changing age will only print age"
      (is (= "AGE: 43\n"
             (with-out-str
               (swap! full-source update :age inc)))))

    (testing "Changing both will print both"
      (is (= #{"NAME: Testy Testington"
               "AGE: 100"}
             (into #{}
                   (str/split-lines
                    (with-out-str
                      (reset! full-source {:first-name "Testy"
                                           :last-name "Testington"
                                           :age 100})))))))))