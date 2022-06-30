(ns ripley.live.collection
  "A live component that handles a collection of entities.
  Optimizes rerenders to changed entities only."
  (:require [ripley.html :as h]
            [ripley.impl.output :refer [render-to-string]]
            [ripley.live.protocols :as p]
            [clojure.set :as set]
            [clojure.string :as str]
            [ripley.live.source :as source]
            [ripley.impl.dynamic :as dynamic]
            [ripley.live.patch :as patch]
            [clojure.tools.logging :as log]))

(defn- create-component [ctx item-source-fn render value]
  (let [[source set-value!] (source/use-state value)
        source (item-source-fn source)]
    {:source source
     :set-value! set-value!
     :component-id (p/register! ctx source render {})}))

(defn- by-key [key coll]
  (into {} (map (juxt key identity)) coll))

(defn- listen-collection!
  [source item-source-fn initial-collection collection-id key render initial-components-by-key]
  (log/debug "Start collection listener" collection-id)
  (let [components-by-key (atom initial-components-by-key)
        by-key (partial by-key key)
        old-state (atom {:by-key (by-key initial-collection)
                         :keys (map key initial-collection)})
        ctx dynamic/*live-context*]
    (binding [dynamic/*component-id* collection-id]
      ;; Read the collection source
      (p/listen!
       source
       (fn collection-source-listener [new-collection]
         (log/debug "New collection:" (count new-collection) "items")
         (let [{old-collection-by-key :by-key
                old-collection-keys :keys} @old-state

               new-collection-by-key (by-key new-collection)
               new-collection-keys (map key new-collection)

               old-key-set (set old-collection-keys)
               new-key-set (set new-collection-keys)
               removed-keys (set/difference old-key-set new-key-set)
               added-keys (set/difference new-key-set old-key-set)

               ;; If list of old keys (minus removed) differs
               ;; from list of new keys (minus additions), we need
               ;; to send a child order patch.
               new-keys-without-added
               (remove added-keys new-collection-keys)

               order-change
               (when (not= (remove removed-keys old-collection-keys)
                           new-keys-without-added)
                 (patch/child-order collection-id
                                    (mapv (comp :component-id @components-by-key)
                                          new-keys-without-added)))

               removed-components (select-keys @components-by-key removed-keys)
               patches
               (cond-> []
                 (seq removed-keys)
                 (conj (patch/delete-many
                        (mapv :component-id (vals removed-components))))

                 order-change
                 (conj order-change))]

           (reset! old-state {:by-key new-collection-by-key
                              :keys new-collection-keys})

           ;; Cleanup components that were removed
           (swap! components-by-key #(reduce dissoc % removed-keys))
           (doseq [{id :component-id} removed-components]
             (p/deregister! ctx id))

           ;; Set child order for existing children (if changed)
           ;; and add any new ones
           (loop [prev-key nil
                  [new-key & new-keys] new-collection-keys
                  patches patches]
             (if-not new-key
               (when (seq patches)
                 (p/send! ctx patches))

               (let [old-value (get old-collection-by-key new-key)
                     new-value (get new-collection-by-key new-key)
                     set-value!
                     (when old-value
                       (:set-value! (@components-by-key new-key)))]
                 (if-not (added-keys new-key)
                   (do
                     ;; Send update if needed to existing item
                     (when (and (some? old-value)
                                (not= old-value new-value))
                       (set-value! new-value))

                     (recur new-key new-keys patches))

                   ;; Added, render this after given prev child id
                   (let [after-component-id
                         (when prev-key
                           (:component-id (@components-by-key prev-key)))
                         {new-id :component-id :as component}
                         (create-component ctx item-source-fn render new-value)
                         rendered (dynamic/with-live-context ctx
                                    (dynamic/with-component-id new-id
                                      (render-to-string render new-value)))]
                     (swap! components-by-key assoc new-key component)
                     (recur new-key new-keys
                            (conj patches
                                  (if after-component-id
                                    (patch/insert-after after-component-id rendered)
                                    (patch/prepend collection-id rendered)))))))))))))))

(defn live-collection
  "Render a live-collection that automatically inserts and removes
  new items as they are added to the source.

  This can be used to render eg. tables that have dynamic items.

  Options:

  :render  Function to render one entity, takes the entity as parameter

  :key     Function to extract entity identity (like an :id column).
           Key is used to determine if item is already in the collection

  :source  The source that provides the collection.

  :container-element
           Keyword for the container HTML element (defaults to :span).
           Use :tbody when rendering tables.

  :item-source-fn
           Optional function to pass each source generated for individual
           items through.
           This is useful if you want to make computed sources for items
           that consider some additional data as well.


  "
  [{:keys [render key source container-element item-source-fn]
    :or {container-element :span
         item-source-fn identity}}]
  (let [ctx dynamic/*live-context*
        source (source/source source)

        initial-collection (p/current-value source)

        ;; Register dummy component as parent, that has no render and will never receive updates
        collection-id (p/register! ctx nil :_ignore {})


        ;; Store individual :source and :component-id for entities
        components-by-key
        (into {}
              (map (juxt key (partial create-component ctx item-source-fn render)))
              initial-collection)

        container-element-name (h/element-name container-element)
        container-element-classes (h/element-class-names container-element)]

    (log/debug "Initial collection: " (count initial-collection) "items")
    (listen-collection!
     source item-source-fn initial-collection collection-id
     key render components-by-key)

    (h/out! "<" container-element-name
            (when (seq container-element-classes)
              (str " class=\"" (str/join " " container-element-classes) "\""))
            " data-rl=\"" collection-id "\">")

    ;; Render live components for each initial value
    (doseq [{:keys [component-id source]} (map (comp components-by-key key)
                                               initial-collection)]
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
