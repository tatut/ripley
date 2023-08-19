(ns tetris.state
  "Tetris game logic state")

(def pieces [[[1 1]
              [1 1]]

             [[1]
              [1]
              [1]
              [1]]

             [[0 1 1]
              [1 1 0]]

             [[1 1 0]
              [0 1 1]]

             [[1 0]
              [1 0]
              [1 1]]

             [[0 1]
              [0 1]
              [1 1]]

             [[1 1 1]
              [0 1 0]]])

(def colors "rgbyomc")
(def board-size {:width 10 :height 20})

(defn rotate [rows]
  (let [row-count (count (first rows))
        col-count (count rows)]
    (vec (for [r (range row-count)]
           (vec
            (for [c (range col-count)]
              (get-in rows [(- col-count c 1) r])))))))

(defn random-color! []
  (rand-nth colors))

(defn random-piece! []
  (let [c (random-color!)]
    (mapv (fn [row]
            (mapv #(if (= 1 %) c %)
                  row))
          (rand-nth pieces))))



(comment
  ;; Test every piece rotates correctly
  (every? #(= % (nth (iterate rotate %) 4)) pieces)
  ,,,)

(defn initial-state!
  "Generate initial game state, with random pieces."
  []
  (let [{:keys [width height]} board-size]
    {:current-piece (random-piece!)
     :piece-position [(- (/ width 2) 2) 0] ;; [x y] coordinate of piece
     :next-piece (random-piece!)
     :board (vec
             (repeat height
                     (vec (repeat width 0))))
     :game-over? false
     :score 0}))

(defn can-occupy? [{b :board} piece [x y]]
  (let [w (count (first b))
        h (count b)]
    (every? (fn [row-idx]
              (let [board-y (+ y row-idx)
                    row (and (< board-y h) (nth piece row-idx))]
                (when row
                  (every? (fn [col-idx]
                            (let [board-x (+ x col-idx)
                                  col (nth row col-idx)]
                              (or (= 0 col)
                                  (and (< board-x w)
                                       (= 0 (get-in b [board-y board-x]))))))
                          (range (count row))))))
            (range (count piece)))))

(defn occupy
  "Occupy the given piece at given position on the board. Returns new game state."
  [state piece [x y]]
  (update state :board
          (fn [b]
            (reduce (fn [b [xp yp]]
                      (let [at (get-in piece [yp xp])]
                        (if (= 0 at)
                          b
                          (assoc-in b [(+ y yp) (+ x xp)] at))))
                    b
                    (for [x (range (count (first piece)))
                          y (range (count piece))]
                      [x y])))))

(defn board-with-piece
  "Get board with the current piece filled in."
  [{:keys [board current-piece piece-position] :as gs}]
  (:board (occupy gs current-piece piece-position)))

(defn width [{b :board}]
  (count (first b)))

(defn height [{b :board}]
  (count b))

(defn- if-can-occupy [state update-fn]
  (let [{:keys [current-piece piece-position] :as new-state} (update-fn state)]
    (if (can-occupy? state current-piece piece-position)
      new-state
      state)))

;; Players actions for game state
(defn up
  "Rotate current piece clockwise."
  [state]
  (if-can-occupy state #(update % :current-piece rotate)))

(defn down
  "Rotate current piece counterclockwise."
  [state]
  (if-can-occupy state #(update % :current-piece (fn [p]
                                                 (nth (iterate rotate p) 3)))))


(defn left
  "Move current piece one place left."
  [state]
  (if-can-occupy state #(update % :piece-position (fn [[x y]] [(dec x) y]))))

(defn right
  "Move current piece one place right."
  [state]
  (if-can-occupy state #(update % :piece-position (fn [[x y]] [(inc x) y]))))

(defn spawn
  "Spawn the next piece, if the piece can't spawn, mark the game as over."
  [{np :next-piece :as state}]
  (let [w (width state)
        pos [(dec (/ w 2)) 0]]
    (if (can-occupy? state np pos)
      ;; New piece can be spawned
      (assoc state
             :current-piece np
             :piece-position pos
             :next-piece (random-piece!))
      ;; Can't spawn, game over :(
      (assoc state :game-over? true))))

(defn clear-filled
  "Clear full lines"
  [{b :board :as state}]
  (let [h (height state)
        w (width state)
        lines (remove (fn [row]
                        (every? #(not= % 0) row))
                      b)
        cleared-lines (- h (count lines))
        score (if (pos? cleared-lines)
                (nth (iterate #(* % 2) 10) cleared-lines)
                0)]
    (-> state
        (update :score + score)
        (assoc :board
               (into (vec (repeat cleared-lines (vec (repeat w 0))))
                     lines)))))

(defn tick
  "Drop current piece 1 row down. If current piece can't move down, it will occupy
  the current position and the next piece will be spawned."
  [{p :current-piece [x y] :piece-position :as state}]
  (let [new-pos [x (inc y)]]
    (if (can-occupy? state p new-pos)
      ;; Can move down
      (assoc state :piece-position new-pos)

      ;; Can't move down, occupy the positions and spawn new piece.
      (-> state
          (occupy p [x y])
          clear-filled
          spawn))))

(defn fall
  "Let the current piece fall as low as it can."
  [{p :current-piece :as state}]
  (loop [[x y] (:piece-position state)]
    (if (can-occupy? state p [x (inc y)])
      (recur [x (inc y)])
      (-> state
          (occupy p [x y])
          clear-filled
          spawn))))
