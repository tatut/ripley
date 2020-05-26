(ns ripley.live.collection
  "A live component that handles a collection of entities.
  Optimizes rerenders to changed entities only."
  (:require [ripley.html :as h]
            [ripley.impl.output :refer [render-to-string]]
            [ripley.live.protocols :as p]
            [ripley.live.async :refer [ch->source]]
            [clojure.core.async :as async :refer [go-loop <! >!]]
            [ripley.live.context :as context]
            [clojure.set :as set]))


(defn live-collection [{:keys [render ; function to render one entity
                               patch ; :append or :prepend for where to render new entities
                               key ; function to extract entity identity (like an :id column)
                               source ; source that provides the collection
                               container-element ; HTML element type of container, defaults to :span
                               child-element ; HTML element type to render new children in, defaults to :span
                               ]
                        :or {patch :append
                             container-element :span
                             child-element :span}}]
  (let [ctx context/*live-context*
        collection-ch (p/to-channel source)
        initial-collection (async/<!! collection-ch)

        ;; Store individual sources for entities in an atom
        source-by-key (atom (into {}
                                  (map (juxt key (fn [entity]
                                                   (let [ch (async/chan 1)]
                                                     (async/put! ch entity)
                                                     (ch->source ch)))))
                                  initial-collection))

        ;; Register dummy component as parent, that has no render and will never receive updates
        collection-id (p/register! ctx (ch->source (async/chan 1)) :_ignore {})]

    (h/out! "<" (name container-element) " id=\"__rl" collection-id "\">")
    (binding [context/*component-id* collection-id]
      ;; Read the collection source
      (go-loop [old-collection-by-key (into {} (map (juxt key identity)) initial-collection)
                new-collection (<! collection-ch)]
        (let [new-collection-by-key (into {} (map (juxt key identity)) new-collection)]
          (doseq [removed-key (set/difference (set (keys old-collection-by-key))
                                              (set (keys new-collection-by-key)))
                  :let [source (get @source-by-key removed-key)]]
            ;; Send tombstone to sources that were removed, so context will send
            ;; deletion patch
            (>! (p/to-channel source) :ripley.live/tombstone))

          (doseq [[new-key entity] new-collection-by-key
                  :let [old-value (get old-collection-by-key new-key ::not-found)]]
            (cond
              (= ::not-found old-value)
              (let [ch (async/chan 1)
                    source (ch->source ch)
                    new-id (p/register! ctx source render {})]
                (p/send! ctx (str collection-id (case patch
                                                  :append ":A:"
                                                  :prepend ":P:")
                                  "<" (name child-element) " id=\"__rl" new-id "\">"
                                  (render-to-string render entity)
                                  "</" (name child-element) ">"))
                (swap! source-by-key assoc new-key source))

              (not= old-value entity)
              (let [source (get @source-by-key new-key)]
                ;(println "entity " new-key " changed, send to its source")
                (async/put! (p/to-channel source) entity))))
          (recur (into {} (map (juxt key identity)) new-collection)
                 (<! collection-ch))))

      ;; Render live components for each value
      (let [sources @source-by-key]
        (doseq [entity initial-collection
                :let [k (key entity)
                      source (sources k)
                      id (p/register! context/*live-context* source render {})]]
          (h/out! "<" (name child-element) " id=\"__rl" id "\">")
          (context/with-component-id id
            (render (async/<!! (p/to-channel source))))
          (h/out! "</" (name child-element) ">"))))
    (h/out! "</" (name container-element) ">")))
