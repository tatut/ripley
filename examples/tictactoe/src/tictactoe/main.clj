(ns tictactoe.main
  (:require [org.httpkit.server :as server]
            [compojure.core :refer [routes GET]]
            [compojure.route :as route]
            [ripley.html :as h]
            [ripley.live.context :as context]
            [ripley.js :as js]
            [tictactoe.game :as game]
            [ripley.live.source :as source]
            [clojure.string :as str]
            [ripley.live.protocols :as p]
            [ripley.impl.dynamic :as dynamic]))

(defonce server (atom nil))

(def games (atom {}))

(defn input [id label & [placeholder]]
  (h/html
   [:div.mb-4
    [:label.block.text-gray-700.text-sm.font-bold.mb-2 {:for id} label]
    [:input.shadow.appearance-none.border.rounded.w-full.py-2.px-3.text-gray-700.leading-tight.focus:outline-none.focus:shadow-outline
     {:id id :type "text"
      :placeholder (or placeholder "")}]]))

(defn lobby [on-join]
  (let [[waiting set-waiting!] (source/use-state nil)]
    (h/html
     [:span
      [:div.max-w-xs.container.mx-auto
       [:form.bg-white.shadow-md.rounded.px-8.pt-6.pb-8.mb-4
        (input "player" "Player name")
        (input "code" "Game code" "leave blank to start new game")
        [:div.flex.items-center
         [:button.bg-blue-500.hover:bg-blue-700.text-white.font-bold.my-2.py-2.px-4.rounded.focus:outline-none.focus:shadow-outline
          {:type :button
           :on-click (js/js (partial on-join set-waiting!)
                            (js/input-value "player")
                            (js/input-value "code"))}
          "Play"]]]]
      [::h/live waiting
       (fn [{:keys [code]}]
         (h/html
          [:div.fixed.top-0.left-0.right-0.bottom-0.w-full.h-screen.z-50.overflow-hidden.bg-gray-900.opacity-75.flex.flex-col.items-center.justify-center
           [:h2.text-white.text-xl "Waiting for other player"]
           [:h1.text-white.text-3xl "join with code: " code]]))]])))

(defn turn!
  "Play one turn"
  [name game-atom position]
  (swap!
   game-atom
   (fn [{:keys [players] :as state}]
     ;; Find out my player (:x or :o) by name
     (let [p (some (fn [[p p-name]]
                     (when (= name p-name)
                       p)) players)]
       ;; Update game state
       (update state :game game/turn p position)))))

(defn- shapes []
  (h/html
   ;; Define x and o elements
   [:defs
    [:path {:id "x"
            :d (str "M 0.25,0.25"
                    "L 0.75, 0.75"
                    "M 0.75, 0.25"
                    "L 0.25, 0.75")
            :stroke "black" :stroke-width 0.1}]
    [:circle {:id "o"
              :cx 0.5 :cy 0.5 :r 0.25
              :stroke "black" :stroke-width 0.1}]]))

(defn- grid []
  ;; show lines to separate grid
  (h/html
   [:g
    [::h/for [[x1 x2 y1 y2] [[0.1 2.9 1 1] [0.1 2.9 2 2]
                             [1 1 0.1 2.9] [2 2 0.1 2.9]]]
     [:line {:x1 x1 :x2 x2 :y1 y1 :y2 y2
             :stroke "black" :stroke-width 0.1}]]]))

(defn game
  "UI to play the game"
  [name game-atom]
  (let [[players-s game-s]
        (source/split game-atom #{:players} #{:game})
        players-and-turn (source/c=
                          {:players (:players %players-s)
                           :turn (get-in %game-s [:game :turn])})

        ;; Computed source for the winning player's name
        winner-name (source/c=
                     (let [{winning-player :player} (get-in %game-s [:game :winner])
                           players (:players %players-s)]
                       (if (= :tie winning-player)
                         :tie
                         (some-> winning-player players))))
        board-position (fn [x y]
                         (source/c=
                          (let [p (+ x (* y 3))
                                winner (get-in %game-s [:game :winner])]
                            {:winning-pos? (boolean
                                            (and (:move winner)
                                                 ((:move winner) p)))
                             :x x :y y :p p
                             :held (get-in %game-s [:game :board p])})))]
    (def g game-atom) ; debug to inspect current game in repl
    (h/html
     [:div
      [::h/live players-and-turn
       (fn [{:keys [players turn] :as comp}]
         (h/html
          [:div.flex.justify-center
           [::h/for [p [:x :o]
                     :let [turn? (= turn p)
                           name (players p)]]
            [:div {:class (str "inline m-4 text-xl "
                               (if turn? "font-bold" "font-normal"))}
             name]]]))]
      [:div.flex.justify-center
       [:svg {:class "w-1/3 h-1/3" :viewBox "0 0 3 3" :fill "white"
              :xmlns:xlink "http://www.w3.org/1999/xlink"}
        (shapes)
        (grid)
        [::h/for [x (range 3)
                  y (range 3)
                  :let [pos (board-position x y)]]
         [::h/live pos
          (fn [{:keys [x y p held winning-pos?] :as bp}]
            (let [shape (case held
                          :x "#x"
                          :o "#o"
                          nil)]
              (h/html
               [:g
                [::h/when shape
                 [:use {:xlink:href shape :x x :y y}]]
                [::h/cond
                 winning-pos?
                 [:rect {:x x :y y :width 1 :height 1 :fill-opacity 0.7
                         :fill "green"}]

                 (not held)
                 [:rect {:x x :y y :width 1 :height 1 :fill-opacity 0
                         :on-click #(turn! name game-atom p)}]]])))]]]]
      [::h/live winner-name
       #(h/html
         [:span
          [::h/when %
           [:div.flex.justify-center.m-4
            [::h/if (= :tie %)
             [:div.text-2xl "It's a tie!"]
             [:div.text-2xl "Winner is " % "!"]]]]])]])))

(defn- generate-code
  "Generate a random 4 character game code"
  []
  (let [first-char (int \A)
        last-char (int \Z)]
    (str/join (take 4 (repeatedly
                       #(char (+ first-char (rand-int (- (inc last-char)
                                                         first-char)))))))))

(defn- game-atom
  "Return atom that holds the given game."
  [code]
  (get (swap! games update code #(or % (atom nil))) code))

(defn- update-game! [code update-fn & args]
  (apply swap! (game-atom code) update-fn args))

(defn- remove-game! [code]
  ;; We could check here that both players have disconnected
  ;; before removing, but this is good enough.
  (swap! games dissoc code))

(defn join-game
  "Create or join a game. Returns game state."
  [name code on-start]
  (let [code (if (str/blank? code)
               (generate-code)
               code)
        game (update-game!
              code
              (fn [game]
                (if (nil? game)
                  ;; create empty game when first person joins
                  {:players {:x name :o nil}
                   :code code
                   :game nil
                   :on-start [on-start]}
                  ;; join game
                  (-> game
                      (assoc-in [:players :o] name)
                      (assoc :game game/new-game
                             :ready? true)
                      (update :on-start conj on-start)))))]
    (p/register-cleanup! dynamic/*live-context* #(remove-game! code))
    (when (:ready? game)
      ;; game is ready to start (both joined), notify players
      (let [g (game-atom code)]
        (doseq [on-start (:on-start game)]
          (on-start g))))
    game))

(defn tictactoe []
  (let [[state set-state!] (source/use-state {})]
    (h/html
     [:html
      [:head
       [:link {:rel :stylesheet :href "tictactoe.css"}]]
      [:body
       (h/live-client-script "/__ripley-live")
       [:div.decoration-clone.bg-gradient-to-b.from-blue-100.to-blue-500.text-2xl.text-center.p-2.m-2
        "Tictactoe"]
       [::h/live state
        (fn [{:keys [name game-atom]}]
          (if game-atom
            (game name game-atom)
            (lobby (fn [set-waiting! name code]
                     (let [g (join-game name code #(set-state! {:name name :game-atom %}))]
                       (when-not (:ready? g)
                         ;; We need to stay in lobby to wait for other player
                         (set-waiting! {:code (:code g)})))))))]]])))

(def tictactoe-routes
  (routes
   (GET "/" _req
        (h/render-response #(tictactoe)))
   (route/resources "/")
   (context/connection-handler "/__ripley-live")))

(defn- restart []
  (swap! server
         (fn [old-server]
           (when old-server (old-server))
           (println "Starting tictactoe server")
           (server/run-server tictactoe-routes {:port 3000}))))

(defn -main [& args]
  (restart))
