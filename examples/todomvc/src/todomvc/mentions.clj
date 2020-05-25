(ns todomvc.mentions
  "A mentions input that allows selecting github users."
  (:require [org.httpkit.client :as client]
            [cheshire.core :as cheshire]
            [clojure.string :as str]
            [ripley.html :as h]
            [clojure.core.async :as async :refer [go-loop <! >! timeout alts!]]
            [ripley.live.async :refer [ch->source]]
            [ripley.js :as js]
            [ripley.live.collection :refer [live-collection]]
            [ripley.live.atom :refer [atom-source]]))


(defn- search-gh-users [txt]
  (println "Searching github users:" txt)
  (let [ch (async/chan)]
    (client/get "https://api.github.com/search/users" {:query-params {"q" txt}}
                (fn [{:keys [body]}]
                  (let [results (-> body
                                    (cheshire/decode keyword)
                                    :items)]
                    (async/put! ch (into []
                                         (comp (filter #(str/includes? % txt))
                                               (map #(select-keys % [:avatar_url :login :id]))
                                               (take 10))
                                         results))
                    (async/close! ch))))
    ch))

(defn- new-mentions-state []
  (let [in-ch (async/chan)
        results-ch (async/chan 1)
        state {:showing? (atom false)
               :searching? (atom false)
               :term (atom "")
               :search-term-ch in-ch
               :results-source (ch->source results-ch false)}]

    ;; Initial empty results
    (async/put! results-ch [])

    ;; Listen to typing (first time without timeout)
    (go-loop [[val ch] (alts! [in-ch])]
      (if (= ch in-ch)
        ;; More typing, update term
        (do
          (reset! (:showing? state) true)
          (reset! (:term state) val)
          (recur (alts! [in-ch (timeout 300)])))

        ;; Timeout reached, trigger search
        (do
          (println "init search")
          (reset! (:searching? state) true)
          (let [results (<! (search-gh-users @(:term state)))]
            (println "sending results")
            (>! results-ch results))
          (reset! (:searching? state) false)
          (println "after search")
          (recur (alts! [in-ch])))))
    (def *state state)
    state))

(defn- mentions-popup [on-select {:keys [showing? searching? term results-source] :as state}]
  (h/html
   [:<>
    [:div {:style [::h/live
                   showing?
                   ;; show a string for now
                   #(str "z-index:999;position:static;display:" (if % "block" "none") ";")]
           #_{:display (if showing? :block :none)
              :z-index 999
              :position :static}}
     [:div {:style [::h/live searching?
                    #(str "background-color: wheat; padding: 1rem; border: solid 1px black; display: "
                          (if % "block" "none") ";")
                    #_{:background-color :wheat
                     :padding "1rem"
                     :border "solid 1px black"}]}
      "Searching... "
      [::h/live term h/out!]]]

    (live-collection {:source results-source
                      :key :id
                      :patch :append
                      :render (fn [{:keys [id login avatar_url] :as user}]
                                (h/html
                                 [:div {:on-click #(on-select user)}
                                  [:img {:src avatar_url :width 32 :height 32}]
                                  login]))})]))



(defn mentions-input [input-id]
  (let [state (new-mentions-state)
        search-term-ch (:search-term-ch state)]
    (h/html
     [:<>
      [:input {:type :text
               :id (name input-id)
               :on-key-up (js/js (fn [{:keys [caret term] :as payload}]
                                   (when term
                                     (println "TERMI: " term)
                                     (async/put! search-term-ch term)))
                                 (str "(function() { var e = document.getElementById('" (name input-id) "');"
                                      "var p = e.selectionStart;"
                                      "var t = e.value.substring(0,p);"
                                      "var i = t.lastIndexOf('@');"
                                      "if(i != -1) {"
                                      "return {caret: p, term: t.substring(i+1)};"
                                      "} else {"
                                      "return null;"
                                      "}})()"))}]

      (mentions-popup #(println "VALITTU: " %)
                      state)])))
