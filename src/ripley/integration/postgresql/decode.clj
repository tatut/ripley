(ns ripley.integration.postgresql.decode
  "Code to decode values from test_decoding output plugin."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

(declare parse-test-decoding-values)
(defn parse-test-decoding-message
  "Parse a message created by test_decoding output plugin.

  https://wiki.postgresql.org/wiki/Logical_Decoding_Plugins#test_decoding
  \"People have written code to parse the output from this plugin, but that doesn't make it a good idea\" *shrug*

  "
  [^String msg]
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

(defmethod pg->clj "bigint" [_ v]
  (Long/parseLong v))
(defmethod pg->clj "integer" [_ v]
  ;; parse integers as longs, like clojure does
  (Long/parseLong v))

(defmethod pg->clj "boolean" [_ ^String v]
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
  ;;(println "PARSE VALUES: " values)
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
