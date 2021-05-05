(ns tictactoe.game
  "Tictactoe game logic")

(def new-game
  "Empty game state for new game."
  {:board (vec (repeat 9 nil))
   :turn :x
   :winner nil})

(def ^:private winning-moves
  "Set of winning moves. Each winning move is a set
  of board positions that need to be held."
  #{#{0 1 2} #{3 4 5} #{6 7 8} ; rows
    #{0 3 6} #{1 4 7} #{2 5 8} ; cols
    #{0 4 8} #{2 4 6}})        ; diagonals

(defn winner
  "Return the player (:x or :o) who has won the game and the winning move
  (set of positions) as a map with keys :player and :move.

  If the game ended in tie returns {:player :tie} without winning move.
  If the game hasn't ended yet, returns nil."
  [{b :board :as _game}]
  (let [move-held-by (fn [move]
                       (let [held (into #{}
                                        (map #(nth b %))
                                        move)]
                         (when (and (= 1 (count held))
                                    (not (held nil)))
                           (first held))))]
    (or
     ;; Return winner and move, if any
     (some #(when-let [p (move-held-by %)]
              {:player p
               :move %})
           winning-moves)

     ;; If there are no empty slots, return tie
     (and (not (some nil? b))
          {:player :tie})

     nil)))

(defn turn
  "Play 1 turn, returns updated game state."
  [game player position]
  {:pre [(<= 0 position 8)]}
  (cond
    ;; tried to play when winner is already determined, do nothing
    (some? (:winner game))
    game

    ;; tried to play on other players turn, do nothing
    (not= (:turn game) player)
    game

    ;; if played position is not empty, do nothing
    (some? (nth (:board game) position))
    game

    ;; play this position
    :else
    (let [new-game (-> game
                       (update :board assoc position player)
                       (update :turn #(if (= % :x) :o :x)))]
      (assoc new-game
             :winner (winner new-game)))))

(do;comment
  (-> new-game
      (turn :x 4)
      (turn :o 3)
      (turn :x 1)
      (turn :o 0)
      (turn :x 7)
      :winner)) ; {:player :x :move #{7 1 4}}
