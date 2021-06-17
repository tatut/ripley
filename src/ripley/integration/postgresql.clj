(ns ripley.integration.postgresql
  "Integrate to PostgreSQL listen/notify and logical replication."
  (:import (org.postgresql PGConnection PGProperty)
           (org.postgresql.replication PGReplicationStream)
           (org.postgresql.replication.fluent.logical ChainedLogicalStreamBuilder)
           (java.sql Connection DriverManager)
           (java.util.concurrent TimeUnit)
           (java.nio ByteBuffer))
  (:require [clojure.core.async :as async :refer [thread]]
            [taoensso.timbre :as log]
            [ripley.integration.postgresql.decode :refer [parse-test-decoding-message]]
            [ripley.live.source :as source]
            [ripley.live.protocols :as p]))

(def ^:private logical-replication-instance
  "A global logical replication instance for convenience." nil)

(defprotocol LogicalReplication
  (listen-table! [this table-name callback-fn]
    "Register 1 arg `callback-fn` to receive notifications of
changes to table `table-name`. Returns 0 arg function
that will stop listening when called.

The event is a map describing the table, the operation and values.

table-name is a string that contains the schema name and the table
(eg. \"public.user\")")
  (listen-table-with-values! [this table-name values-map callback-fn]
    "Same as `listen-table!` but only returns events where the
operation matches the values.
`values-map` is a map of {column-name column-value}.

Example:
(listen-table-with-values! ... \"public.user\" {\"email\" \"foo@example.com\"} callback-fn)

Will call the callback-fn when an insert, update or delete is done
where the row has the specified email address.

")
  (close! [this] "Closes the logical replication stream."))

(defn- with-slot-options [^ChainedLogicalStreamBuilder b opts]
  (reduce-kv (fn [b [opt value]]
               (.withSlotOption b
                                (if (keyword? opt)
                                  (name opt)
                                  (str opt))
                                value))
             b opts))

(defn replication-connection
  "Create a PostgreSQL JDBC connection for replication"
  [{:keys [host port database user password]
    :or {host "localhost"
         user (System/getenv "USER")
         port 5432}}]
  (let [p (java.util.Properties.)]
    (.setProperty p "user" user)
    (when password
      (.setProperty p "password" password))
    (.set PGProperty/ASSUME_MIN_SERVER_VERSION p "9.4")
    (.set PGProperty/REPLICATION p "database")
    (.set PGProperty/PREFER_QUERY_MODE p "simple")
    (DriverManager/getConnection
     (str "jdbc:postgresql://" host ":" port "/" database)
     p)))

(defn create-logical-replication-slot
  "Create a replication slot."
  [{:keys [connection replication-slot-name]}]
  (-> ^Connection connection
      (.unwrap PGConnection)
      .getReplicationAPI
      .createReplicationSlot
      .logical
      (.withSlotName replication-slot-name)
      (.withOutputPlugin "test_decoding")
      .make))

(defn- add-listener! [listeners-atom table-name listener]
  (swap! listeners-atom update table-name
         #(conj (or % []) listener))
  (fn []
    (swap! listeners-atom update table-name
           (fn [table-listeners]
             (filterv #(not= listener %) table-listeners)))))

(defrecord LogicalReplicationStream [stream listeners]
  LogicalReplication
  (listen-table! [_this table-name callback-fn]
    (add-listener! listeners table-name
                   {:filter (constantly true)
                    :callback-fn callback-fn}))

  (listen-table-with-values! [_this table-name values-map callback-fn]
    (add-listener! listeners table-name
                   {:filter (fn [{v :values}]
                              (every? (fn [[required-column required-value]]
                                        (= required-value
                                           (get v required-column)))
                                      values-map))
                    :callback-fn callback-fn}))
  (close! [_this]
    (.close stream)))

(defn- send-event-notifications [listeners-atom {table :table :as event}]
  (doseq [{:keys [filter callback-fn] :as listener} (get @listeners-atom table)
          :when (filter event)]
    (try
      (callback-fn event)
      (catch Throwable t
        (log/error t "Error in replication change listener for table " table ". Disabling listener!")
        (swap! listeners-atom update table
               (fn [table-listeners]
                 (filterv #(not= listener %) table-listeners)))))))

(defn initialize-logical-replication
  "Initialize a logical replication connection.
  Returns a LogicalReplication instance. You should
  only create one logical replication per server process.

  Options:
  :connection  a JDBC Connection to PostgreSQL
  :replication-slot-name
               the name of the replication slot
  :replication-slot-options
               map of options for logical replication slot
  :replication-status-interval
               status interval in seconds (default: 5)
  :replication-start-position
               optional start position of replication
               (org.postgresql.replication.LogSequenceNumber)
  "
  [{:keys [connection
           replication-slot-name
           replication-slot-options
           replication-status-interval
           replication-start-position]
    :or {replication-status-interval 5}}]
  (let [^PGReplicationStream stream
        (-> ^Connection connection
            ^PGConnection (.unwrap PGConnection)
            .getReplicationAPI
            .replicationStream
            .logical
            (.withSlotName replication-slot-name)
            (with-slot-options replication-slot-options)
            (.withStatusInterval replication-status-interval java.util.concurrent.TimeUnit/SECONDS)
            (as-> b
                (if replication-start-position
                  (.withStartPosition b replication-start-position)
                  b))
            .start)
        read-pending #(when-let [^ByteBuffer msg (.readPending stream)]
                        (let [offset (.arrayOffset msg)
                              arr (.array msg)
                              len (- (alength arr) offset)]
                          (String. arr offset len)))
        listeners (atom {})]
    (thread
      (try
        (log/info "Start reading logical replication stream for slot: "
                  replication-slot-name)
        (loop [msg (read-pending)
               last-lsn nil]
          (if (nil? msg)
            (do
              (.sleep TimeUnit/MILLISECONDS 10)
              (when-not (.isClosed stream)
                (recur (read-pending) last-lsn)))
            (do
              (log/debug "REPLICATION MSG: " msg)
              (let [msg (parse-test-decoding-message msg)
                    last-rcv-lsn (.getLastReceiveLSN stream)]
                (when msg
                  (send-event-notifications listeners msg))
                (when (not= last-lsn last-rcv-lsn)
                  (.setAppliedLSN stream last-rcv-lsn)
                  (.setFlushedLSN stream last-rcv-lsn))
                (when-not (.isClosed stream)
                  (recur (read-pending) last-rcv-lsn))))))
        (catch Throwable t
          (log/error t "Error in logical replication read thread.")))
      (log/info "Logical replication stream was closed for slot: "
                replication-slot-name))

    (->LogicalReplicationStream stream listeners)))

(defn start!
  "Starts logical replication and sets the global instance.
  See [[initialize-logical-replication]] for options."
  [opts]
  (alter-var-root #'logical-replication-instance
                  (constantly (initialize-logical-replication opts))))

(defn stop!
  "Stops the global logical replication instance."
  []
  (alter-var-root #'logical-replication-instance
                  (fn [instance]
                    (close! instance)
                    nil)))


(comment
  (def test-msgs ["table public.todos: UPDATE: id[integer]:2 label[text]:'muuta' complete[boolean]:true"
                  "COMMIT 505"
                  "BEGIN 506"
                  "table public.todos: INSERT: id[integer]:5 label[text]:'lookinen replikantti' complete[boolean]:false"
                  "COMMIT 506"
                  "BEGIN 507"
                  "table public.todos: INSERT: id[integer]:6 label[text]:'lookinen replikantti 2' complete[boolean]:false"
                  "COMMIT 507"
                  "table public.testi: INSERT: id[bigint]:2 foo[text]:'hep' pvm[date]:'2021-06-05' aika[time without time zone]:'23:15:00' luotu[timestamp without time zone]:'2021-06-05 12:23:49.273684'"])
  (def c (replication-connection {:database "tatutarvainen"}))
  (def opts {:connection c :replication-slot-name "test1"})
  (create-logical-replication-slot opts)
  (def lr (initialize-logical-replication opts))
  (def unlisten
    (listen-table! lr "public.testi" (fn [event]
                                       (def *event event)
                                       (println "GOT EVENT: " (pr-str event))))))

(defn collection-source
  "Collection that is backed by a SQL query and automatically
  updates based on changes.

  Options:
  :logical-replication  a LogicalReplication instance. If omitted the global
                        instance is used.
  :initial-state        a collection of initial rows (or future if
                        source is not immediate)
  :changes              collection of change listeners that react to database
                        transaction data

  Each change listener is a map with the following options:
  :table         name of the table to listen to, must include
                 schema (eg. \"public.user\")
  :values        map of {column value} for filtering which changes
                 are relevant
  :type          fn determining if the tx type should be included
                 defaults to #{:UPDATE :INSERT :DELETE} (all types)
  :update        function to process the change, receives the current
                 values and tx event as parameter and must return
                 the new collection values

  See [[default-change-handler]] for a simple change handler that
  is suitable if there are no joins in the query.

  "
  [{:keys [logical-replication initial-state changes]
    :or {logical-replication logical-replication-instance}}]
  (assert logical-replication
          "Logical replication instance not specified and no global instance started.")
  (let [state (atom (when-not (future? initial-state)
                      initial-state))
        unlistens (atom nil)

        [source listeners]
        (source/source-with-listeners
         #(deref state)
         #(let [unlistens @unlistens]
            (log/debug "Cleaning up PostgreSQL collection-source with "
                       (count unlistens) " listeners.")
            (doseq [ul unlistens]
              (ul))))

        set-state! (fn [new-value]
                     (reset! state new-value)
                     (doseq [listener @listeners]
                       (listener new-value)))]

    ;; Register all logical replication table listeners
    (reset!
     unlistens
     (mapv
      (fn [{:keys [table values update type]
            :or {type #{:UPDATE :INSERT :DELETE}}}]
        (let [cb #(do
                    (println "got event " (pr-str %))
                    (when (type (:type %))
                       (println ":type " (:type %) "matches, doing update")
                       (set-state! (update @state %))))]
          (if (seq values)
            (listen-table-with-values! logical-replication table values cb)
            (listen-table! logical-replication table cb))))
      changes))
    (when (future? initial-state)
      (future (set-state! @initial-state)))

    source))

(defn default-change-handler
  "Default change handler that updates collection values
  based on values received. Inserted rows are inserted
  at the position determined by `:compare` function
  or at the end if not specified.

  :id-keys   specifies set of keys (eg. #{:id}) used to determine
             if two items represent the same row
  "
  [{:keys [table values id-keys keyword-keys? compare]}]
  {:table table
   :values values
   :type #{:UPDATE :INSERT :DELETE}
   :update (fn [current-values tx-event]
             (let [values (into {}
                                (map
                                 (if keyword-keys?
                                   (fn [[key val]] [(keyword key) val])
                                   identity))
                                (:values tx-event))
                   changed-row-id (select-keys values id-keys)]
               (case (:type tx-event)
                 :UPDATE (mapv (fn [row]
                                 (if (= changed-row-id (select-keys row id-keys))
                                   (merge row values)
                                   row))
                               current-values)
                 :INSERT (let [new-values (conj current-values values)]
                           (if compare
                             (sort compare new-values)
                             new-values))
                 :DELETE (filterv #(not= changed-row-id (select-keys % id-keys))
                                  current-values))))})
