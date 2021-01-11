(ns ripley.html
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [ripley.live.context :as context]
            [ripley.live.protocols :as p]
            [ripley.live.source :as source]
            [clojure.core.async :as async]
            ripley.js
            [ripley.impl.output :refer [*html-out*]]
            [cheshire.core :as cheshire])
  (:import (org.apache.commons.lang3 StringEscapeUtils)))

(set! *warn-on-reflection* true)

(defn out! [& things]
  (doseq [thing things]
    (.write ^java.io.Writer *html-out* (str thing))))

(defn dyn! [& things]
  ;; Output some dynamic part
  (.write ^java.io.Writer *html-out* (StringEscapeUtils/escapeHtml4 (str/join things))))

(defn to-camel-case [kw]
  (str/replace (name kw)
               #"-\w"
               (fn [m]
                 (str/upper-case (subs m 1)))))



(defn element-class-names [elt]
  (map second (re-seq #"\.([^.#]+)" (name elt))))

(defn element-name [elt]
  (second (re-find #"^([^.#]+)" (name elt))))

(defn element-id [elt]
  (second (re-find #"#([^.]+)" (name elt))))

(declare compile-html)

(defn props-and-children [body]
  (let [has-props? (map? (second body))
        props (if has-props?
                (second body)
                nil)
        children (drop (if has-props? 2 1) body)]
    [props children]))

(defn compile-children [children]
  (map compile-html children))

(def callback-attributes #{"onchange" "onclick" "onblur" "onfocus"
                           "onkeypress" "onkeyup" "onkeydown"})

(defn- html-attr-name [attr-name]
  (str/lower-case (str/replace (name attr-name) #"-" "")))



(defn register-callback [callback]
  (cond
    (instance? ripley.js.JSCallback callback)
    (let [invoke-callback-js (str "ripley.send("
                                  (p/register-callback! context/*live-context*
                                                        (:callback-fn callback))
                                  ",[" (str/join "," (:js-params callback)) "])")
          condition (:condition callback)]
      (if condition
        (str "if(" condition ") " invoke-callback-js)
        invoke-callback-js))

    (fn? callback)
    (str "ripley.send(" (p/register-callback! context/*live-context* callback) ", [])")

    (string? callback)
    callback

    :else
    (throw (ex-info "Unsupported callback value, expected callback function or string"
                    {:unsupported-callback callback}))))

(defn- static-form? [form symbol-whitelist]
  (let [static (volatile! true)]
    (walk/prewalk (fn [x]
                    (when (and (symbol? x)
                               (not (symbol-whitelist x)))
                      (vreset! static false))
                    x) form)
    @static))

(defonce garden-compile-style
  (delay (try
           (require 'garden.compiler)
           (resolve 'garden.compiler/compile-style)
           (catch Throwable t
             (println "No garden dependency, can't compile styles")))))

(defn- compile-style [style]
  (cond
    ;; String style attr, pass it through as is
    (string? style)
    `(out! " style=\"" ~style "\"")

    ;; Render maps with garden, if present
    (map? style)
    (if-let [compile-style @garden-compile-style]
      (if (static-form? style #(str/starts-with? (name %) "garden."))
        ;; Transform static style at compile time
        `(out! " style=\"" ~(compile-style [style]) "\"")
        ;; Otherwise output code that transforms at runtime
        `(out! " style=\"" (garden.compiler/compile-style [~style]) "\""))
      (throw (ex-info "No garden compiler found, add garden to deps to support CSS compilation"
                      {:missing-var 'garden.compiler/compile-style})))))

(defn- live-attributes [attrs]
  (into #{}
        (keep (fn [[attr val]]
                (when (and (vector? val)
                           (= ::live (first val)))
                  attr)))
        attrs))

(defn- live-source-and-component [[_live & opts]]
  (if (map? (first opts))
    (if (and (contains? (first opts) :source)
             (contains? (first opts) :component))
      (first opts)
      (throw (ex-info "Live map options must contains :source and :component"
                      {:invalid-options opts})))
    (let [[source component] opts]
      (merge {:source source}
             (when component
               {:component component})))))

(defn style->str [style]
  (if (map? style)
    (str/replace (@garden-compile-style [style]) "\n" "")
    style))

(defn- register-live-attr [component-live-id attr live]
  (let [{:keys [source component]} (live-source-and-component live)
        val (gensym "val")
        new-val (if component
                  (list component val)
                  val)
        new-val (if (= attr :style)
                  `(style->str ~new-val)
                  new-val)]
    `(let [source# (source/source ~source)]
       (when (p/immediate? source#)
         (out! " " ~(name attr) "=\"")
         (let [~val (async/<!! (p/to-channel source#))
               ~@(when component
                   [val (list component val)])
               ~@(when (= :style attr)
                   [val `(style->str ~val)])]
           (dyn! ~val))
         (out! "\""))
       (binding [context/*component-id* ~component-live-id]
         (p/register! context/*live-context* source#
                      (fn [~val]
                        ;; FIXME: handle style compilation if attr is :style
                        (out! (cheshire/encode {~attr (str ~new-val)})))
                      {:patch :attributes
                       :parent ~component-live-id})))))

(def no-close-tag #{"input"})

(defn compile-html-element
  "Compile HTML markup element, like [:div.someclass \"content\"]."
  [body]
  (let [element-kw (first body)
        element (element-name element-kw)
        class-names (element-class-names element-kw)
        id (element-id element-kw)
        [props children] (props-and-children body)
        live-attrs (live-attributes props)
        live-id (gensym "live-id")
        props (merge props
                     (when (seq class-names)
                       {:class (str/join " " class-names)}))]
    `(let [~live-id
           ;; FIXME: do the consume-component-id! only on the FIRST html element
           ;; during this ripley.html/html expansion call to optimize further
           ~(if (seq live-attrs)
              `(or (context/consume-component-id!)
                   (p/register! context/*live-context* nil nil {}))
              `(context/consume-component-id!))]
       (out!
        ~(str "<" element))
       (when ~live-id
         (out! " id=\"__rl" ~live-id "\""))
       ~@(for [[attr val] props
               :let [html-attr (html-attr-name attr)]]
           (if (live-attrs attr)
             ;; Live attribute, register source
             (register-live-attr live-id attr val)

             ;; Style or other regular attribute
             (if (= :style attr)
               (compile-style val)
               (if-let [static-value
                        (cond
                          (keyword? val) (name val)
                          (string? val) val
                          (number? val) (str val)
                          :else nil)]
                 ;; Expand a static attribute
                 `(out! ~(str " " html-attr "=\"" static-value "\""))
                 ;; Expand dynamic attribute (where nil removes the value)
                 (let [valsym (gensym "val")]
                   `(when-let [~valsym ~val]
                      (out! " " ~html-attr "=\""
                            ~(if (callback-attributes html-attr)
                               `(register-callback ~valsym)
                               `(str ~valsym))
                            "\"")))))))
       ~@(if (no-close-tag element)
           [`(out! ">")]
           (concat [`(out! ">")]
                   (compile-children children)
                   [`(out! ~(str "</" element ">"))])))))

(defn compile-fragment [body]
  (let [[props children] (props-and-children body)]
    `(do
       ~@(compile-children children))))

(defn compile-for
  "Compile special :ripley.html/for element."
  [[_ bindings body :as form]]
  (assert (vector? bindings) ":ripley.html/for bindings must be a vector")
  (assert (= 3 (count form)) ":ripley.html/for must have bindings and a single child form")
  `(doseq ~bindings
     ~(compile-html body)))

(defn compile-if
  "Compile special :ripley.html/if element."
  [[_ test then else :as form]]
  (assert (= 4 (count form)) ":ripley.html/if must have exactly 3 forms: test, then and else")
  `(if ~test
     ~(compile-html then)
     ~(compile-html else)))

(defn compile-when
  "Compile special :ripley.html/when element."
  [[_ test then :as form]]
  (assert (= 3 (count form)) ":ripley.html/when must have exactly 2 forms: test and then")
  `(when ~test
     ~(compile-html then)))

(defn compile-cond
  "Compile special :ripley.html/cond element."
  [[_ & clauses]]
  (assert (even? (count clauses)) ":ripley.html/cond must have even number of forms")
  `(cond
     ~@(mapcat (fn [[test expr]]
                 [test (compile-html expr)])
               (partition 2 clauses))))

(defn compile-let
  "Compile special :ripley.html/let element."
  [[_ bindings body]]
  (assert (vector? bindings) "Let bindings must be a vector")
  `(let ~bindings
     ~(compile-html body)))

(defn compile-live
  "Compile special :ripley.html/live element."
  [live-element]
  (let [{:keys [source component element patch]} (live-source-and-component live-element)
        ;; FIXME: not used now, the actual render gives the element
        element (if element
                  (name element)
                  "span")]
    `(let [source# (source/source ~source)
           component# ~(or component
                           `(fn [thing#]
                              (out! (str thing#))))
           id# (p/register! context/*live-context* source# component#
                            ~(if patch
                               {:patch patch}
                               {}))]
       (if (p/immediate? source#)
         (context/with-component-id id#
           (component# (async/<!! (p/to-channel source#))))

         ;; Render placeholder now that will be replaced with contents
         (out! ~(str "<span id=\"__rl") id# "\" />")))))

(def compile-special {:<> #'compile-fragment
                      ::let #'compile-let
                      ::for #'compile-for
                      ::if #'compile-if
                      ::when #'compile-when
                      ::cond #'compile-cond
                      ::live #'compile-live})

(defn compile-html [body]
  (cond
    (vector? body)
    (cond
      ;; first element is special element
      (contains? compile-special (first body))
      ((compile-special (first body)) body)

      ;; first element is a keyword this is static HTML markup
      (keyword? (first body))
      (compile-html-element body)

      ;; unrecognized
      :else
      (throw (ex-info "Vector must start with element or special keyword. Hint: call functions with regular parenthesis."
                      {:invalid-first-element (first body)})))

    (string? body)
    ;; Static content
    `(out! ~(StringEscapeUtils/escapeHtml4 body))

    ;; Some dynamic content: symbol reference
    (symbol? body)
    `(dyn! ~body)

    ;; Function call (or some other clojure form), pass it as is
    (list? body)
    body

    :else
    (throw (ex-info (str "Can't compile to HTML: " (pr-str body))
                    {:element body}))))

(defn- optimize-nested-do
  "Remove nested do wrappings so next optimizations work better.
  Turns (do a (do b) (do c) d) into (do a b c d)."
  [[_ & forms]]
  `(do ~@(mapcat
          (fn [f]
            (if (and (seq? f)
                     (= 'do (first f)))
              (rest f)
              [f]))
          forms)))

(defn- optimize-adjacent-out! [forms]
  (loop [acc '()
         out-strings nil
         forms forms]
    (if (empty? forms)
      (concat acc
              (when (seq out-strings)
                [`(out! ~(str/join out-strings))]))
      (let [[f & forms] forms]
        (if (and (seq? f) (= 'ripley.html/out! (first f)))
          ;; This is a call to out!
          (let [[strings rest-forms] (split-with string? (rest f))]
            (if (seq strings)
              ;; There's static strings here, move them to out-strings and recur
              (recur acc
                     (concat out-strings strings)
                     (concat (when (seq rest-forms)
                               [`(out! ~@rest-forms)])
                             forms))

              ;; No static strings in the beginning here
              (let [[dynamic-parts rest-forms] (split-with (complement string?) rest-forms)]
                (recur (concat acc
                               (when (seq out-strings)
                                 [`(out! ~(str/join out-strings))])
                               [`(out! ~@dynamic-parts)])
                       nil ;; out strings consumed, if any
                       (if (seq rest-forms)
                         ;; some more strings here
                         (concat [`(out! ~@rest-forms)] forms)
                         forms)))))

          ;; This is something else, consume out strings (if any)
          (recur (concat acc
                         (when (seq out-strings)
                           [`(out! ~(str/join out-strings))])
                         [f])
                 nil
                 forms))))))

(defn- optimize
  "Optimize compiled HTML forms."
  [optimizations form]
  (walk/postwalk
   (fn [form]
     (if-not (seq? form)
       form

       (if-let [optimization-fn (optimizations (first form))]
         (optimization-fn form)
         form)))
   form))

(defn- wrap-try [form]
  `(try
     ~form
     (catch Throwable t#
       (println "Exception in HTML rendering: " t#))))

(defmacro html
  "Compile hiccup to HTML output."
  [body]
  (->> body
       compile-html
       (optimize {'do optimize-nested-do})
       (optimize {'do optimize-adjacent-out!})
       wrap-try))

(comment
  (defn list-item [x]
    (html
     [:li {:data-idx x
           :foo "x"
           :disabled (when (even? x) "")} "<script>" x]))

  (with-out-str
    (binding [*html-out* clojure.core/*out*]
      (html [:div.main
             [:h3 "section"]
             [:div.second-level
              [:ul
               [::for [x (range 10)]
                (list-item x)]]]]))))

(defn render-response
  "Return a ring reponse that renders HTML.
  The function is called with HTML output bound."
  [render-fn]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (context/render-with-context render-fn)})

(defn live-client-script [path]
  (html
   [:script
    (out! (slurp (io/resource "public/live-client.js"))
          "\ndocument.onload = ripley.connect('" path "', '" (str (context/current-context-id)) "');")]))
