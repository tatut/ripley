(ns ripley.live.push-state
  "Helpers for working with history push state."
  (:require [ripley.html :as h]
            [ripley.live.protocols :as p]
            [ripley.impl.dynamic :as dyn]
            [clojure.string :as str]))

(defn- encode [x]
  (java.net.URLEncoder/encode (str x)))

(defn- decode [x]
  (java.net.URLDecoder/decode x))

(defn- ->js
  "Encode value suitably for js string."
  [val]
  (encode (pr-str val)))

(defn- ->clj
  "Read value to Clojure data"
  [str]
  (binding [*read-eval* false]
    (read-string (decode str))))

(defn- push-state-query-js [push-state-fn val]
  (let [query (str/join "&"
                        (for [[k v] val]
                          (str (if (keyword? k)
                                 (name k)
                                 (str k))
                               "="
                               (java.net.URLEncoder/encode (str v)))))]
    (str push-state-fn "(\"" (->js val) "\", \"" query "\")")))

(defn push-state-query
  "Outputs a script that sets query parameters based on source value (a map).
  When browser pops state, the callback is invoked to handle new values.
  The callback will be invoked with the popped state.
  "
  [query-params-source on-pop-state]
  (let [push-state-fn (str (gensym "_ps"))
        ctx dyn/*live-context*
        last-popped (atom nil)
        component-id (p/register! ctx query-params-source
                                  (partial push-state-query-js push-state-fn)
                                  {:patch :eval-js
                                   :should-update? (fn [val]
                                                     (not= val @last-popped))})
        callback-id (p/register-callback!
                     ctx
                     (fn [arg]
                       (let [val (->clj arg)]
                         (reset! last-popped val)
                         (on-pop-state val))))]
    (h/out! "<script data-rl=\"" component-id "\">\n"
            "history.replaceState({s:\"" (->js (p/current-value query-params-source)) "\"},document.title)\n"
            "function " push-state-fn "(state,query) {\n"
            "var l = window.location; "
            "window.history.pushState({s:state},\"\","
            "l.protocol+\"//\"+l.host+l.pathname+\"?\"+query)\n"
            "}\n"
            "window.addEventListener(\"popstate\", (s) =>  ripley.send(" callback-id ",[s.state.s]))\n"
            "</script>\n")))
