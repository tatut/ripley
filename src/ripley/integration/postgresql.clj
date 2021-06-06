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
            [ripley.integration.postgresql.decode :refer [parse-test-decoding-message]]))


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

(defn- send-event-notifications [listeners {table :table :as event}]
  (doseq [{:keys [filter callback-fn]} (get listeners table)
          :when (filter event)]
    (callback-fn event)))

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
               status interval in seconds (default: 10)
  :replication-start-position
               optional start position of replication
               (org.postgresql.replication.LogSequenceNumber)
  "
  [{:keys [connection
           replication-slot-name
           replication-slot-options
           replication-status-interval
           replication-start-position]
    :or {replication-status-interval 10}}]
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
      (log/info "Start reading logical replication stream for slot: "
                replication-slot-name)
      (loop [msg (read-pending)]
        (if (nil? msg)
          (.sleep TimeUnit/MILLISECONDS 10)
          (let [msg (parse-test-decoding-message msg)]
            (when msg
              (send-event-notifications @listeners msg))
            (.setAppliedLSN stream (.getLastReceiveLSN stream))
            (.setFlushedLSN stream (.getLastReceiveLSN stream))))
          (when-not (.isClosed stream)
            (recur (read-pending))))
      (log/info "Logical replication stream was closed for slot: "
                replication-slot-name))

    (->LogicalReplicationStream stream listeners)))



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
