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
            [clojure.tools.logging :as log]))

(defn- create-component [ctx render value]
  (let [source (atom/atom-source (atom value))]
    {:source source
     :component-id (p/register! ctx source render {})}))

(defn- by-key [key coll]
  (into {} (map (juxt key identity)) coll))

(defn- listen-collection! [source initial-collection collection-id key render initial-components-by-key]
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
                                          new-keys-without-added)))]
           (reset! old-state {:by-key new-collection-by-key
                              :keys new-collection-keys})
           ;; Send tombstone to sources that were removed, so context will send
           ;; deletion patch
           (doseq [removed-key removed-keys
                   :let [source (:source (get @components-by-key removed-key))]
                   :when source]
             (p/write! source :ripley.live/tombstone))

           ;; Set child order for existing children (if changed)
           ;; and add any new ones
           (loop [prev-key nil
                  [new-key & new-keys] new-collection-keys
                  patches (if order-change [order-change] [])]
             (if-not new-key
               (when (seq patches)
                 (p/send! ctx patches))

               (let [old-value (get old-collection-by-key new-key)
                     new-value (get new-collection-by-key new-key)
                     source
                     (when old-value
                       (:source (@components-by-key new-key)))]
                 (if-not (added-keys new-key)
                   (do
                     ;; Send update if needed to existing item
                     (when (and (some? old-value)
                                (not= old-value new-value))
                       (p/write! source new-value))

                     (recur new-key new-keys patches))

                   ;; Added, render this after given prev child id
                   (let [after-component-id
                         (when prev-key
                           (:component-id (@components-by-key prev-key)))
                         {new-id :component-id :as component}
                         (create-component ctx render new-value)
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


        ;; Store individual :source and :component-id for entities
        components-by-key
        (into {}
              (map (juxt key (partial create-component ctx render)))
              initial-collection)

        container-element-name (h/element-name container-element)
        container-element-classes (h/element-class-names container-element)]

    (log/debug "Initial collection: " (count initial-collection) "items")
    (listen-collection!
     source initial-collection collection-id
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
