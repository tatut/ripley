(ns ripley.live.collection
  "A live component that handles a collection of entities.
  Optimizes rerenders to changed entities only."
  (:require [ripley.html :as h]
            [ripley.impl.output :refer [render-to-string]]
            [ripley.live.protocols :as p]
            [ripley.live.async :refer [ch->source]]
            [clojure.core.async :as async :refer [go-loop <! >!]]
            [clojure.set :as set]
            [clojure.string :as str]
            [ripley.live.source :as source]
            [ripley.impl.dynamic :as dynamic]
            [ripley.live.patch :as patch]))

(defn- diff-ordered
  "Diff two ordered collections of keys, returns removals
  additions and the new order of keys.

  Supports removals from any place and additions to between elements.
  Doesn't support reordering of existing elements."
  [a-list b-list]

  (let [a-set (into #{} a-list)
        b-set (into #{} b-list)
        removed-keys (set/difference a-set b-set)
        added-keys (set/difference b-set a-set)]
    {:removed-keys removed-keys
     :added-keys added-keys
     :key-order
     ;; Go through rest of items
     (loop [a-list (remove removed-keys a-list)
            b-list b-list
            keys []]
       (if (or (seq a-list) (seq b-list))
         ;; Either list still has elements
         (let [a (first a-list)
               b (first b-list)]
           (cond

             (= a b)
             (recur (rest a-list)
                    (rest b-list)
                    (conj keys a))

             ;; new added item here
             (not (a-set b))
             (recur a-list (rest b-list)
                    (conj keys b))))
         ;; No more elements in either, return the keys
         keys))}))

(defn- create-component [ctx render _value]
  (let [ch (async/chan 1)
        source (ch->source ch)]
    {:source source
     :component-id (p/register! ctx source render {})}))

(defn- collection-update-process [collection-id collection-ch key render initial-collection components-by-key]
  (let [ctx dynamic/*live-context*]
    (binding [dynamic/*component-id* collection-id]
      ;; Read the collection source
      (go-loop [old-collection-by-key (into {} (map (juxt key identity)) initial-collection)
                old-collection-keys (map key initial-collection)
                new-collection (<! collection-ch)]
        (let [new-collection-by-key (into {} (map (juxt key identity)) new-collection)
              new-collection-keys (map key new-collection)

              ;; Determine keys that are removed or added and the new key order
              {:keys [removed-keys added-keys key-order]}
              (diff-ordered old-collection-keys new-collection-keys)]

          (doseq [removed-key removed-keys
                  :let [source (:source (get @components-by-key removed-key))]]
            ;; Send tombstone to sources that were removed, so context will send
            ;; deletion patch
            (>! (p/to-channel source) :ripley.live/tombstone))

          (loop [last-component-id nil
                 [key & key-order] key-order
                 patches []]
            (if-not key
              (when (seq patches)
                (p/send! ctx patches))
              (let [add? (added-keys key)
                    old-value (get old-collection-by-key key)
                    new-value (get new-collection-by-key key)]
                (if add?
                  ;; This is an added key, add after last-component-id
                  ;; or prepend item if last-component-id is nil
                  (let [{new-id :component-id :as component}
                        (create-component ctx render new-value)
                        rendered (dynamic/with-component-id new-id
                                   (render-to-string render new-value))]
                    (swap! components-by-key assoc key component)
                    (recur new-id key-order
                           (conj patches
                                 (if last-component-id
                                   (patch/insert-after last-component-id rendered)
                                   (patch/prepend collection-id rendered)))))

                  ;; Send update if needed, to existing item
                  (let [{:keys [component-id source]}
                        (@components-by-key key)]
                    (when (not= old-value new-value)
                      (async/put! (p/to-channel source) new-value))
                    (recur component-id key-order patches))))))

          (recur new-collection-by-key
                 new-collection-keys
                 (<! collection-ch)))))))

(defn live-collection [{:keys [render ; function to render one entity
                               patch ; :append or :prepend for where to render new entities
                               key ; function to extract entity identity (like an :id column)
                               source ; source that provides the collection
                               container-element ; HTML element type of container, defaults to :span
                               ]
                        :or {patch :append
                             container-element :span}}]
  (let [ctx dynamic/*live-context*
        source (source/source source)
        collection-ch (p/to-channel source)
        initial-collection (async/<!! collection-ch)

        ;; Register dummy component as parent, that has no render and will never receive updates
        collection-id (p/register! ctx (ch->source (async/chan 1)) :_ignore {})


        ;; Store individual :source and :component-id for entities in an atom
        components-by-key
        (atom (into {}
                    (map (juxt key (partial create-component ctx render)))
                    initial-collection))

        container-element-name (h/element-name container-element)
        container-element-classes (h/element-class-names container-element)]

    (collection-update-process collection-id collection-ch
                               key render
                               initial-collection
                               components-by-key)

    (h/out! "<" container-element-name
            (when (seq container-element-classes)
              (str " class=\"" (str/join " " container-element-classes) "\""))
            " id=\"__rl" collection-id "\">")

    ;; Render live components for each initial value
    (doseq [[_k {:keys [component-id source]}] @components-by-key]
      (dynamic/with-component-id component-id
        (render (async/<!! (p/to-channel source)))))
    (h/out! "</" container-element-name ">")))

(defn- scroll-sensor [callback]
  (let [g (name (gensym "__checkscroll"))
        id (name (gensym "__scrollsensor"))]
    (h/out!
     "<script>"
     "function " g "() {"
     " var yMax = window.innerHeight; "
     " var y = document.getElementById('" id "').getBoundingClientRect().top;"
     ;;" console.log('hep yMax: ', yMax, ', y: ', y); "
     " if(0 <= y && y <= yMax) { " (h/register-callback callback) "}"
     "}\n"
     "window.addEventListener('scroll', " g ");"
     "</script>"
     "<span id=\"" id "\"></span>")))

(defn infinite-scroll [{:keys [render
                               container-element
                               child-element
                               next-batch ;; Function to return the next batch
                               ]
                        :or {container-element :span
                             child-element :span}}]
  (let [next-batch-ch (async/chan)
        batches (async/chan)
        render-batch (fn [items]
                       (doseq [item items]
                         (h/out! "<" (name child-element) ">")
                         (render item)
                         (h/out! "</" (name child-element) ">")))]

    (go-loop [_ (<! next-batch-ch)]
      (>! batches (next-batch))
      (recur (<! next-batch-ch)))

    (h/out! "<" (name container-element) ">")
    (println "calling render-batch")
    (render-batch (next-batch))

    (println "done rendering first batch")
    (h/html
     [:<>
      [::h/live {:source (ch->source batches false)
                 :component render-batch
                 :patch :append}]
      (scroll-sensor #(async/>!! next-batch-ch 1))])

    (println "after html")
    (h/out! "</" (name container-element) ">")))
