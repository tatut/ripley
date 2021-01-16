(ns ripley.live.collection-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [ripley.live.protocols :as p]
            [ripley.live.context :as context]
            [ripley.live.collection :as collection]
            [clojure.core.async :as async :refer [>!! <!! alts!!]]
            [ripley.html :as h]
            [ripley.impl.output :refer [*html-out*]]
            [clojure.string :as str]))

(defn test-context [sent-ch]
  (let [wait-ch (async/chan)]
    (context/->DefaultLiveContext (fn [_ch msg]
                                    (async/>!! sent-ch msg))
                                  (atom (context/initial-context-state wait-ch)))))

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
    (binding [context/*live-context* ctx]
      (let [output
             (with-html-out-str
              (collection/live-collection
               {:render (fn [item]
                          (h/out! (str (context/consume-component-id!)
                                       "="
                                       (:k item) "/" (:v item))))
                :source items
                :key :k
                :container-element :li}))]
        (is (str/starts-with? output "<li id=\"__rl0\""))
        (is (str/ends-with? output "</li>")))

      ;; Start the live context
      (async/close! (:wait-ch @(:state ctx)))

      ;; Return handles needed to test the live collection handling
      {:sent-ch sent-ch
       :items items
       :set-items!
       (fn [[new-items expected-patches]]
         (reset! items new-items)
         (doseq [patch (if (string? expected-patches)
                         [expected-patches]
                         expected-patches)]
           (is (= (take! sent-ch) patch))))})))

(deftest collection-update-process
  (let [{:keys [set-items!]} (setup-live-collection)]
    (doseq [items-patches
            
            [;; Add item, sends patch to prepend item
             [[{:k 1 :v 1}]
              "0:P:1=1/1"]

             ;; Adding second item send patch to add if after first
             [[{:k 1 :v 1} {:k 2 :v 2}]
              "1:F:2=2/2"]

             ;; Add third, append after second
             [[{:k 1 :v 1} {:k 2 :v 2} {:k 3 :v 3}]
              "2:F:3=3/3"]
             
             ;; Remove second item, sends deletion patch
             [[{:k 1 :v 1} {:k 3 :v 3}]
              "2:D"]

             ;; Change value of third item
             [[{:k 1 :v 1} {:k 3 :v "FIXED"}]
              "3:R:3=3/FIXED"]
             
             ;; Add fourth item add end, append after third
             [[{:k 1 :v 1} {:k 3 :v "FIXED"} {:k 4 :v 4}]
              "3:F:4=4/4"]

             ;; Prepend fifth item
             [[{:k 5 :v 5} {:k 1 :v 1} {:k 3 :v "FIXED"} {:k 4 :v 4}]
              "0:P:5=5/5"]
             
             ;; Add sixth between 3 and 4
             [[{:k 5 :v 5} {:k 1 :v 1} {:k 3 :v "FIXED"} {:k 6 :v 6} {:k 4 :v 4}]
              "3:F:6=6/6"]

             ;; prepend and append on same change
             [[{:k "b+" :v 666}
               {:k 5 :v 5} {:k 1 :v 1} {:k 3 :v "FIXED"} {:k 6 :v 6} {:k 4 :v 4} 
               {:k "b-" :v -666}]
              ["0:P:7=b+/666"
               "4:F:8=b-/-666"]]]]
      (set-items! items-patches))))
