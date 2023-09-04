(ns tetris.main
  (:require [ripley.html :as h]
            [ripley.js :as js]
            [ripley.live.source :as source]
            [compojure.core :refer [routes GET]]
            [compojure.route :refer [resources]]
            [org.httpkit.server :as server]
            [ripley.live.context :as context]
            [tetris.state :as state]))

(defonce server (atom nil))

(defn key->action
  "Map keycode to game action the player can take."
  [c]
  (case c
    (87 38) state/up    ; W or up arrow
    (65 37) state/left  ; A or left arrow
    (83 40) state/down  ; S or down arrow
    (68 39) state/right ; D or right arrow
    32      state/fall  ; space
    nil))

(defn tick-thread [update-gs! tick?]
  (.start (Thread. #(loop []
                      (Thread/sleep 500)
                      (when @tick?
                        (update-gs! state/tick)
                        (recur))))))

(defn game
  "Main component of our tetris game."
  []
  (let [initial-state (state/initial-state!)
        w (state/width initial-state)
        h (state/height initial-state)
        [gs _set-gs! update-gs!] (source/use-state initial-state)
        tick? (atom true)
        gs (assoc gs :cleanup-fn #(reset! tick? false))]
    ;; Start a thread to tick our game, this could be a go block instead or something fancier
    (tick-thread update-gs! tick?)
    (h/out! "<!DOCTYPE html>")
    (h/html
     [:html
      [:head
       [:title "Tetris with Ripley"]
       [:link {:rel :stylesheet :href "tetris.css"}]
       (h/live-client-script "/_ws")]
      [:body {:onkeydown (js/js #(some-> % key->action update-gs!) js/keycode)}
       [:div.tetris-container
        [:div.tetris
         [:div.score
          "SCORE: "
          [::h/live-let [score (source/computed :score gs)]
           [:span score]]]
         [:div.board-and-next
          [:svg.board {:width (* 20 w)
                       :height (* 20 h)}
           [::h/for [y (range h)]
            [::h/live-let [row (source/computed #(nth (state/board-with-piece %) y) gs)]
             [:g.row
              [::h/for [x (range w)
                        :let [empty? (= 0 (nth row x))]]
               [:rect {:x (* 20 x) :y (* 20 y) :width 20 :height 20
                       :class (if empty? "e" (nth row x))}]]]]]]

          ;; Next piece display
          [:div.next
           [:div "Next:"]
           [:svg {:width (* 20 4) :height (* 20 4)}
            [::h/live (source/computed :next-piece gs)
             (fn [rows]
               (h/html
                [:g
                 [::h/for [r (range (count rows))
                           :let [row (nth rows r)]]
                  [::h/for [c (range (count row))
                            :let [p (nth row c)]]
                   [:rect {:x (* c 20) :y (* r 20) :width 20 :height 20
                           :class (if (= 0 p) "e" p)}]]]]))]]]]

         ;; Game over dialog
         [::h/live-let [open? (source/computed :game-over? gs)]
          [:dialog.game-over {:open open?} "Game over"]]]]]])))

(def tetris-routes
  (routes
   (GET "/" _req
        (h/render-response game))
   (resources "/")
   (context/connection-handler "/_ws")))

(defn restart
  ([] (restart 3000))
  ([port]
   (swap! server
          (fn [old-server]
            (when old-server
              (old-server))
            (println "Starting Tetris server on port " port)
            (server/run-server tetris-routes {:port port})))))

(defn -main []
  (restart))
