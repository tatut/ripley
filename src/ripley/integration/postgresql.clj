(ns ripley.integration.postgresql
  "Integrate to PostgreSQL listen/notify and logical replication."
  (:import (org.postgresql PGConnection PGProperty)
           (org.postgresql.replication PGReplicationStream)
           (org.postgresql.replication.fluent.logical ChainedLogicalStreamBuilder)
           (java.sql Connection DriverManager)
           (java.util.concurrent TimeUnit)
           (java.nio ByteBuffer))
  (:require [clojure.core.async :as async :refer [thread]]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defprotocol LogicalReplication
  (listen-table! [this table-name callback-fn]
    "Register 1 arg `callback-fn` to receive notifications of
changes to table `table-name`. Returns 0 arg function
that will stop listening when called.

The event is a map describing the table, the operation and values.")
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

(declare parse-test-decoding-message)
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
            (.unwrap PGConnection)
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
                          (String. arr offset len)))]
    (thread
      (loop [msg (read-pending)]
        (if (nil? msg)
          (.sleep TimeUnit/MILLISECONDS 10)
          (do
            (parse-test-decoding-message msg)
            (.setAppliedLSN stream (.getLastReceiveLSN stream))
            (.setFlushedLSN stream (.getLastReceiveLSN stream))))
          (when-not (.isClosed stream)
            (recur (read-pending)))))

    (reify LogicalReplication
      (listen-table! [this table-name callback-fn]
        :fixme)
      (close! [this]
        (.close stream)))))

(declare parse-test-decoding-values)
(defn- parse-test-decoding-message
  "Parse a message created by test_decoding output plugin.

  https://wiki.postgresql.org/wiki/Logical_Decoding_Plugins#test_decoding
  \"People have written code to parse the output from this plugin, but that doesn't make it a good idea\" *shrug*

  "
  [^String msg]
  (println "MSG: " msg)
  (let [pos (.indexOf msg (int \space))
        cmd (subs msg 0 pos)]
    (when (= cmd "table") ; don't care about BEGIN/COMMIT lines
      (let [msg (subs msg (inc pos))
            pos (.indexOf msg (int \:))
            table (subs msg 0 pos)
            msg (subs msg (+ 2 pos))
            pos (.indexOf msg (int \:))
            change-type (subs msg 0 pos)]
        {:table table
         :type (keyword change-type)
         :values (parse-test-decoding-values (subs msg (+ 2 pos)))}))))

(def ^:private column-name-and-type-pattern
  "Regex pattern to extract column name and type
  examples:
  \"id[integer]:\"
  \"description[character varying]:\""
  #"(.*?)\[([^\]]+)\]:(.*)$")

(defn- extract-value
  "extract possibly quoted value, returns the value and the remaining text"
  [^String value]
  (if (= (.charAt value 0) \')
    ;; Quoted string (a quote in the value is escaped as double '')
    (let [len (count value)
          res (StringBuilder.)]
      (loop [i 1]
        (let [ch (.charAt value i)]
          (if (= ch \')
            ;; might be quoted ' if the next is also '
            (if (and (< i (dec len)) (= \' (.charAt value (inc i))))
              (do
                (.append res \')
                (recur (+ i 2)))
              [(str res) (if (> (+ 2 i) len)
                           ""
                           (subs value (+ 2 i)))])
            (do
              (.append res ch)
              (recur (inc i)))))))
    ;; Not quoted string, get until space
    (let [pos (.indexOf value (int \space))
          v (if (neg? pos)
              value
              (subs value 0 pos))
          more-values (if (neg? pos)
                        ""
                        (subs value (inc pos)))]
      [v more-values])))

(defmulti pg->clj (fn [type _string] type))

(defmethod pg->clj :default [_ string] string)

(defmethod pg->clj "integer" [_ v]
  (Long/parseLong v))

(defmethod pg->clj "boolean" [_ v]
  (Boolean/valueOf v))

(defmethod pg->clj "date" [_ v]
  (java.time.LocalDate/parse v java.time.format.DateTimeFormatter/ISO_LOCAL_DATE))

(defmethod pg->clj "time without time zone" [_ v]
  (java.time.LocalTime/parse v))

(defmethod pg->clj "timestamp without time zone" [_ v]
  (let [[date time] (str/split v #" ")]
    (java.time.LocalDateTime/of
     (java.time.LocalDate/parse date java.time.format.DateTimeFormatter/ISO_LOCAL_DATE)
     (java.time.LocalTime/parse time))))

(defmethod pg->clj "numeric" [_ v]
  (bigdec v))

(defn- parse-test-decoding-value
  "Naive and possibly incomplete parser for values, can read numbers
  and quoted strings."
  [type ^String value]
  (println "type: " (pr-str type) ", value: " (pr-str value))
  (let [[v more-values] (extract-value value)]
    [(if (= v "null")
       nil
       (try
         (pg->clj type v)
         (catch Exception e
           (log/warn e "Failed to parse logical replication " type " value: " v)
           v)))
     more-values]))

(defn- parse-test-decoding-values [^String values]
  (loop [out {}
         [_ name type rest :as match] (re-find column-name-and-type-pattern values)]
    (if-not match
      out
      (let [[val more-values :as res] (parse-test-decoding-value type rest)]
        (if-not res
          (do (log/warn "Failed to parse test_decoding value " type " from: " rest)
              out)
          (recur (assoc out name val)
                 (re-find column-name-and-type-pattern more-values)))))))

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
  (def lr (initialize-logical-replication opts)))
