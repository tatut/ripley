(ns ripley.live.collection
  "A live component that handles a collection of entities.
  Optimizes rerenders to changed entities only."
  (:require [ripley.html :as h]
            [ripley.impl.output :refer [render-to-string]]
            [ripley.live.protocols :as p]
            [ripley.live.atom :as atom]
            [clojure.set :as set]
            [clojure.string :as str]
            [ripley.live.source :as source]
            [ripley.impl.dynamic :as dynamic]
            [ripley.live.patch :as patch]
            [taoensso.timbre :as log]))

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
     :key-ops
     ;; Generate operations to do for keys after deletions
     ;; have been removed
     (let [old-key-idx (into {}
                             (map-indexed (fn [i k] [k i]))
                             (remove removed-keys a-list))]
       ;; Go through rest of items
       (loop [idx 0
              last-key nil
              [b & b-list] b-list
              keys []]
         (if-not b
           keys
           (let [old-idx (old-key-idx b)]
             (cond
               ;; Added here
               (nil? old-idx)
               (recur idx ; don't increment
                      b
                      b-list
                      (conj keys
                            (if last-key
                              [b :insert-after last-key]
                              [b :prepend])))

               ;; Moved here
               (not= old-idx idx)
               (recur (inc idx)
                      b
                      b-list
                      (conj keys
                            (if last-key
                              [b :move-after last-key]
                              [b :move-first])))

               ;; No change in position
               :else
               (recur (inc idx)
                      b
                      b-list
                      (conj keys [b :compare])))))))}))

(defn- create-component [ctx render value]
  (let [source (atom/atom-source (atom value))]
    {:source source
     :component-id (p/register! ctx source render {})}))

(defn- by-key [key coll]
  (into {} (map (juxt key identity)) coll))

(defn- listen-collection! [source initial-collection collection-id key render components-by-key]
  (log/debug "Start collection listener" collection-id)
  (let [by-key (partial by-key key)
        old-state (atom {:by-key (by-key initial-collection)
                         :keys (map key initial-collection)})
        ctx dynamic/*live-context*]
    (binding [dynamic/*component-id* collection-id]
      ;; Read the collection source
      (p/listen!
       source
       (fn collection-source-listener [new-collection]
         (log/debug "new collection" new-collection)
         (let [{old-collection-by-key :by-key
                old-collection-keys :keys} @old-state

               new-collection-by-key (by-key new-collection)
               new-collection-keys (map key new-collection)

               ;; Determine keys that are removed or added and the new key order
               {:keys [removed-keys key-ops]}
               (diff-ordered old-collection-keys new-collection-keys)]
           (reset! old-state {:by-key new-collection-by-key
                              :keys new-collection-keys})
           ;; Send tombstone to sources that were removed, so context will send
           ;; deletion patch
           (doseq [removed-key removed-keys
                   :let [source (:source (get @components-by-key removed-key))]
                   :when source]
             (p/write! source :ripley.live/tombstone))

           ;; Go through key-ops for still active children
           (loop [[key-op & key-ops] key-ops
                  patches []]
             (if-not key-op
               ;; Send the gathered patches, if any
               (when (seq patches)
                 (p/send! ctx patches))
               (let [[key op & args] key-op
                     old-value (get old-collection-by-key key)
                     new-value (get new-collection-by-key key)
                     {:keys [component-id source]}
                     (when old-value
                       (@components-by-key key))]

                 ;; Send update if needed to existing item
                 (when (and (some? old-value)
                            (not= old-value new-value))
                   (p/write! source new-value))

                 ;; Handle key operations to reposition/insert children
                 (case op
                   ;; Element stayed in same position, do nothing
                   :compare
                   (recur key-ops patches)

                   ;; Existing element moved position to first
                   :move-first
                   (recur key-ops
                          (conj patches (patch/move-first component-id)))

                   ;; Existing element moved
                   :move-after
                   (let [after-key (first args)
                         after-component-id (:component-id (@components-by-key after-key))]
                     (recur key-ops
                            (conj patches (patch/move-after after-component-id component-id))))
                   ;; New element
                   (:insert-after :prepend)
                   (let [after-component-id
                         (when (= op :insert-after)
                           (:component-id (@components-by-key (first args))))
                         {new-id :component-id :as component}
                         (create-component ctx render new-value)
                         rendered (dynamic/with-live-context ctx
                                    (dynamic/with-component-id new-id
                                      (render-to-string render new-value)))]
                     (swap! components-by-key assoc key component)
                     (recur key-ops
                            (conj patches
                                  (if after-component-id
                                    (patch/insert-after after-component-id rendered)
                                    (patch/prepend collection-id rendered)))))))))))))))

(defn live-collection
  [{:keys [render ; function to render one entity
           key ; function to extract entity identity (like an :id column)
           source ; source that provides the collection
           container-element ; HTML element type of container, defaults to :span
           ]
    :or {container-element :span}}]
  (let [ctx dynamic/*live-context*
        source (source/source source)

        initial-collection (p/current-value source)

        ;; Register dummy component as parent, that has no render and will never receive updates
        collection-id (p/register! ctx nil :_ignore {})


        ;; Store individual :source and :component-id for entities in an atom
        components-by-key
        (atom (into {}
                    (map (juxt key (partial create-component ctx render)))
                    initial-collection))

        container-element-name (h/element-name container-element)
        container-element-classes (h/element-class-names container-element)]

    (log/debug "initial collection: " initial-collection)
    (listen-collection!
     source initial-collection collection-id
     key render components-by-key)


    (h/out! "<" container-element-name
            (when (seq container-element-classes)
              (str " class=\"" (str/join " " container-element-classes) "\""))
            " data-rl=\"" collection-id "\">")

    ;; Render live components for each initial value
    (doseq [[_k {:keys [component-id source]}] @components-by-key]
      (dynamic/with-component-id component-id
        (render (p/current-value source))))
    (h/out! "</" container-element-name ">")))


(defn- scroll-sensor [callback]
  (let [g (name (gensym "__checkscroll"))
        id (name (gensym "__scrollsensor"))]
    (h/html
     [:<>
      [:span {:id id}]
      (h/out! "<script>")
      (h/out!
       "function " g "() {"
       " var yMax = window.innerHeight; "
       " var y = document.getElementById('" id "').getBoundingClientRect().top;"
       ;;" console.log('hep yMax: ', yMax, ', y: ', y); "
       " if(0 <= y && y <= yMax) { "
       ;;"console.log('fetching'); "
       (h/register-callback callback) "}"
       "}\n"
       "window.addEventListener('scroll', " g ");")
      (h/out! "</script>")])))

(defn default-loading-indicator []
  (h/html
   [:div "Loading..."]))

(defn infinite-scroll [{:keys [render
                               container-element
                               child-element
                               next-batch ;; Function to return the next batch
                               immediate?
                               render-loading-indicator]
                        :or {container-element :span
                             child-element :span
                             immediate? true
                             render-loading-indicator default-loading-indicator}}]
  (let [initial-batch (when immediate? (next-batch))
        [loading-source set-loading!] (source/use-state (not immediate?))
        [batch-source set-batch!] (source/use-state initial-batch)
        render-batch (fn [items]
                       (doseq [item items]
                         (h/out! "<" (name child-element) ">")
                         (render item)
                         (h/out! "</" (name child-element) ">")))]

    (h/out! "<" (name container-element) ">")
    (h/html
     [:<>
      [::h/live {:source batch-source
                 :component render-batch
                 :patch :append}]])
    (h/out! "</" (name container-element) ">")

    (scroll-sensor
     #(when (false? (p/current-value loading-source))
        (set-loading! true)
        (set-batch! (next-batch))
        (set-loading! false)))

    (h/html
     [::h/when loading-source
      (render-loading-indicator)])

    ;; If not immediate, start fetching 1st batch after render
    (when-not immediate?
      (future
        (set-batch! (next-batch))
        (set-loading! false)))))
