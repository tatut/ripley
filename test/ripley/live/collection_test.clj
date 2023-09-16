(ns ripley.live.collection-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [ripley.live.context :as context]
            [ripley.live.collection :as collection]
            [clojure.core.async :as async :refer [alts!!]]
            [ripley.html :as h]
            [ripley.impl.output :refer [*html-out*]]
            [ripley.impl.dynamic :as dynamic]
            [clojure.string :as str]))

(defn test-context [sent-ch]
  (context/->DefaultLiveContext
   (atom (merge (context/initial-context-state)
                {:send! #(async/>!! sent-ch %)}))
   {}))

(defmacro with-html-out-str [& body]
  `(let [out# (java.io.StringWriter.)]
     (binding [*html-out* out#]
       ~@body
       (.toString out#))))

(defn- take! [sent-ch]
  (first (alts!! [sent-ch (async/timeout 500)])))

(defn setup-live-collection []
  (let [sent-ch (async/chan 10)
        ctx (test-context sent-ch)
        items (atom [])]
    (binding [dynamic/*live-context* ctx]
      (let [output
             (with-html-out-str
              (collection/live-collection
               {:render (fn [item]
                          (h/out! (str (dynamic/consume-component-id!)
                                       "="
                                       (:k item) "/" (:v item))))
                :source items
                :key :k
                :container-element :li}))]
        (is (str/starts-with? output "<li data-rl=\"0\""))
        (is (str/ends-with? output "</li>")))

      ;; Return handles needed to test the live collection handling
      {:sent-ch sent-ch
       :items items
       :set-items!
       (fn [[new-items expected-patches]]
         (reset! items new-items)
         (let [received-patches (take! sent-ch)]
           (if (fn? expected-patches)
             (expected-patches received-patches)
             (is (= expected-patches received-patches)))))})))

(deftest collection-update-process
  (let [{:keys [set-items!]} (setup-live-collection)]
    (doseq [items-patches

            [;; Add item, sends patch to prepend item
             [[{:k 1 :v 1}]
              [[0 "P" "1=1/1"]]]

             ;; Adding second item send patch to add if after first
             [[{:k 1 :v 1} {:k 2 :v 2}]
              [[1 "F" "2=2/2"]]]

             ;; Add third, append after second
             [[{:k 1 :v 1} {:k 2 :v 2} {:k 3 :v 3}]
              [[2 "F" "3=3/3"]]]

             ;; Remove second item, sends deletion patch
             [[{:k 1 :v 1} {:k 3 :v 3}]
              [[nil "D+" [2]]]]

             ;; Change value of third item
             [[{:k 1 :v 1} {:k 3 :v "FIXED"}]
              [[3 "R" "3=3/FIXED"]]]

             ;; Add fourth item add end, append after third
             [[{:k 1 :v 1} {:k 3 :v "FIXED"} {:k 4 :v 4}]
              [[3 "F" "4=4/4"]]]

             ;; Prepend fifth item
             [[{:k 5 :v 5} {:k 1 :v 1} {:k 3 :v "FIXED"} {:k 4 :v 4}]
              [[0 "P" "5=5/5"]]]

             ;; Add sixth between 3 and 4
             [[{:k 5 :v 5} {:k 1 :v 1} {:k 3 :v "FIXED"} {:k 6 :v 6} {:k 4 :v 4}]
              [[3 "F" "6=6/6"]]]

             ;; prepend and append on same change
             [[{:k "b+" :v 666}
               {:k 5 :v 5} {:k 1 :v 1} {:k 3 :v "FIXED"} {:k 6 :v 6} {:k 4 :v 4}
               {:k "b-" :v -666}]
              [[0 "P" "7=b+/666"]
               [4 "F" "8=b-/-666"]]]

             ;; remove 3, change order
             [[{:k "b-" :v -666}
               {:k "b+" :v 666}
               {:k 1 :v 1}
               {:k 5 :v 5}
               {:k 6 :v 6}
               {:k 4 :v 4}]
              [[nil "D+" [3]]
               [0 "O" [8 7 1 5 6 4]]]]

             ;; remove all
             [[]
              (fn [[[no-id d+ ids] :as patches]]
                (is (= 1 (count patches)))
                (is (nil? no-id))
                (is (= d+ "D+"))
                (is (every? (set ids) [8 7 1 5 6 4])))]]]
      (set-items! items-patches))))
