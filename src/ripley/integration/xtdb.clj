(ns ripley.integration.xtdb
  "XTDB query source integration.

  Query source will listen to new transactions and rerun
  queries on any changes."
  (:require [xtdb.api :as xt]
            [ripley.live.source :as source]
            [ripley.live.protocols :as lp]))


(defn q
  "XTDB query source. Will rerun query and update results
  when new transactions are indexed.

  Options map can have the following keys:

  :node            the XTDB node (required)
  :should-update?  Optional function that takes tx ops and
                   returns truthy value if the query should
                   be rerun. By default query is always rerun.
  :immediate?      If true (default) the query is immediately
                   run when the source is created. If false,
                   the query is run asynchronously and the
                   source is returned without waiting for the
                   results.


  Query and args can either be a function to call or an XTDB
  query and its input arguments.

  If the query is a function it is called with the latest
  db value and the rest of the arguments.
  "
  [{:keys [node should-update? immediate?]
    :or {immediate? true} :as _options} & query-and-args]
  {:pre [(some? node)
         (seq query-and-args)]}
  (let [listener (atom nil)
        [q & args] query-and-args
        run-q (if (fn? q)
                #(apply q (xt/db node) args)
                #(apply xt/q (xt/db node) q args))
        last-value (atom (when immediate?
                           (run-q)))
        [source _listeners]
        (source/source-with-listeners #(deref last-value)
                                      #(some-> listener deref .close))

        update! #(let [new-value (run-q)]
                   (reset! last-value new-value)
                   (lp/write! source new-value))]
    (when-not immediate?
      (future
        (update!)))
    (reset! listener
            (xt/listen node {::xt/event-type ::xt/indexed-tx
                             :with-tx-ops? (boolean should-update?)}
                       (fn [event]
                         (when (or (nil? should-update?)
                                   (should-update? (::xt/tx-ops event)))
                           (update!)))))
    source))
