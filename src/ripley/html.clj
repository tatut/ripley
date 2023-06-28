(ns ripley.html
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [ripley.live.context :as context]
            [ripley.live.protocols :as p]
            [ripley.live.source :as source]
            [clojure.core.async :as async]
            [ripley.impl.output :refer [*html-out*]]
            [ripley.live.patch :as patch]
            [ripley.impl.dynamic :as dynamic])
  (:import (org.apache.commons.lang3 StringEscapeUtils)))

(set! *warn-on-reflection* true)

(def ^:dynamic *raw-text-content* false)

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
  (doall
   (map compile-html children)))

#_(def callback-attributes #{"onchange" "onclick" "onblur" "onfocus"
                           "onkeypress" "onkeyup" "onkeydown"
                             "ondblclick"})
(defn- callback-attribute? [attr-name]
  (and (string? attr-name)
       (str/starts-with? attr-name "on")))


(def no-mangle-attributes
  "Attribute names that should not be mangled (like SVG attrs having dashes)"
  #{"accent-height" "alignment-baseline"
    "arabic-form" "baseline-shift" "cap-height"
    "clip-path" "clip-rule" "color-interpolation"
    "color-interpolation-filters" "color-profile"
    "color-rendering" "dominant-baseline"
    "enable-background" "fill-opacity" "fill-rule"
    "flood-color" "flood-opacity" "font-family"
    "font-size" "font-size-adjust" "font-stretch"
    "font-style" "font-variant" "font-weight"
    "glyph-name" "glyph-orientation-horizontal"
    "glyph-orientation-vertical"
    "horiz-adv-x" "horiz-origin-x" "image-rendering"
    "letter-spacing" "lighting-color"
    "marker-end" "marker-mid" "marker-start"
    "overline-position" "overline-thickness"
    "panose-1" "paint-order" "pointer-events"
    "rendering-intent" "shape-rendering"
    "stop-color" "stop-opacity" "strikethrough-position"
    "strikethrough-thickness" "stroke-dasharray"
    "stroke-dashoffset" "stroke-linecap" "stroke-linejoin"
    "stroke-miterlimit" "stroke-opacity" "stroke-width"
    "text-anchor" "text-decoration" "text-rendering"
    "transform-origin" "underline-position"
    "underline-thickness" "unicode-bidi" "unicode-range"
    "units-per-em" "v-alphabetic" "v-hanging"
    "v-ideographic" "v-mathematical" "vector-effect"
    "vert-adv-y" "vert-origin-x" "vert-origin-y"
    "word-spacing" "writing-mode" "x-height"})

(defn no-mangle-attribute? [attr]
  (or (contains? no-mangle-attributes attr)
      (str/starts-with? attr "data-")
      (str/starts-with? attr "aria-")
      (str/starts-with? attr "x-")))

(defn- html-attr-name [attr-name]
  (let [name (name attr-name)]
    (if (no-mangle-attribute? name)
      ;; Keep attributes marked no mangle as is
      name

      ;; otherwise lowercase and remove dashes
      (str/lower-case (str/replace name #"-" "")))))



(defn register-callback [callback]
  (cond
    ;; Multiple values, join by semicolon
    (vector? callback)
    (str/join ";" (map register-callback callback))

    ;; A callback record
    (satisfies? p/Callback callback)
    (let [invoke-callback-js (str "_rs("
                                  (p/register-callback! dynamic/*live-context*
                                                        (p/callback-fn callback))
                                  ",[" (str/join "," (p/callback-js-params callback)) "]"
                                  "," (or (p/callback-debounce-ms callback) "undefined")
                                  "," (or (p/callback-on-success callback) "undefined")
                                  "," (or (p/callback-on-failure callback) "undefined")
                                  ")")
          condition (p/callback-condition callback)]
      (if condition
        (str "if(" condition ") " invoke-callback-js)
        invoke-callback-js))

    ;; A raw function, register it as callback
    (fn? callback)
    (str "_rs(" (p/register-callback! dynamic/*live-context* callback) ", [])")

    ;; Some js expression, return as is
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

(defn to-style-str [style]
  (cond
    (string? style)
    style

    (map? style)
    (@garden-compile-style [style])))

(defn- compile-style [style]
  (cond
    ;; String style attr, pass it through as is
    (string? style)
    `(out! " style=\"" ~style "\"")

    (symbol? style)
    `(out! " style=\"" (to-style-str ~style) "\"")

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

(def boolean-attribute?
  "Attributes that are rendered without value"
  #{:checked :selected :disabled :readonly :multiple :defer})

(defn- register-live-attr [component-live-id attr live static-value]
  (let [{:keys [source component did-update]} (live-source-and-component live)
        val (gensym "val")
        new-val (if component
                  (list component val)
                  val)
        new-val (if (= attr :style)
                  `(style->str ~new-val)
                  new-val)]
    `(let [source# (source/source ~source)
           ~val (p/current-value source#)
           ~@(when component
               [val (list component val)])
           ~@(when (= :style attr)
               [val `(style->str ~val)])]
       ~(if (boolean-attribute? attr)
          ;; Boolean attribute, output only attr if value is truthy
          `(when ~val
             (out! ~(str " " (name attr))))
          ;; Regular attribute, output with value
          `(when ~(if static-value true `(some? ~val))
             (out! ~(str " " (name attr) "=\"")
                   ~@(when static-value [(str static-value " ")]))
             (dyn! ~val)
             (out! "\"")))
       (binding [dynamic/*component-id* ~component-live-id]
         (p/register! dynamic/*live-context* source#
                      (fn [~val]
                        ~(if (boolean-attribute? attr)
                           `{~attr (if ~new-val 1 nil)}
                           `{~attr ~new-val}))
                      {:patch :attributes
                       :parent ~component-live-id
                       :did-update ~did-update})))))

(def no-close-tag #{"input"})

(def raw-text-content #{"script"})

(defn compile-html-element
  "Compile HTML markup element, like [:div.someclass \"content\"]."
  [body]
  (let [element-kw (first body)
        element (element-name element-kw)
        class-names (element-class-names element-kw)
        id (element-id element-kw)
        [orig-props children] (props-and-children body)
        live-attrs (live-attributes orig-props)
        live-id (gensym "live-id")
        props (merge orig-props
                     (when id
                       {:id id})
                     (when (seq class-names)
                       {:class (if-let [class (and (not (live-attrs :class))
                                                   (:class orig-props))]
                                 ;; Has a class prop and hiccup classes in keyword
                                 ;; combine them
                                 `(str ~(str/join " " class-names)
                                       " "
                                       ~class)

                                 ;; No class prop
                                 (str/join " " class-names))})
                     (when (and (seq class-names)
                                (live-attrs :class))
                       ;; Both static classes and a live class attribute
                       ;; we need to record the static ones for js side
                       {:data-rl-class (str/join " " class-names)}))]
    `(let [~live-id
           ;; FIXME: do the consume-component-id! only on the FIRST html element
           ;; during this ripley.html/html expansion call to optimize further
           ~(if (seq live-attrs)
              `(or (dynamic/consume-component-id!)
                   (p/register! dynamic/*live-context* nil nil {}))
              `(dynamic/consume-component-id!))]
       (out!
        ~(str "<" element))
       (when ~live-id
         (out! " data-rl=\"" ~live-id "\""))
       ~@(for [[attr val] props
               :let [html-attr (html-attr-name attr)]]
           (if (live-attrs attr)
             ;; Live attribute, register source
             (register-live-attr live-id attr (orig-props attr)
                                 ;; Class is special as it may be in hiccup
                                 ;; keyword as static value AND have a live
                                 ;; attribute that defines more classes
                                 (and (= attr :class)
                                      (:data-rl-class props)))

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
                 `(out! ~(str " " html-attr "=\""
                              (StringEscapeUtils/escapeHtml4 static-value) "\""))
                 ;; Expand dynamic attribute (where nil removes the value)
                 (let [valsym (gensym "val")]
                   `(when-let [~valsym ~val]
                      (out! " " ~html-attr "=\""
                            ~(if (callback-attribute? html-attr)
                               `(register-callback ~valsym)
                               `(StringEscapeUtils/escapeHtml4 (str ~valsym)))
                            "\"")))))))
       ~@(if (no-close-tag element)
           [`(out! ">")]
           (concat [`(out! ">")]
                   (binding [*raw-text-content* (raw-text-content element)]
                     (compile-children children))
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
  `(let [test# ~test
         render# (fn [] ~(compile-html then))]
     (if (satisfies? p/Source test#)
       ;; Live when, compile into live component
       (let [show?# (p/current-value test#)
             render-component#
             (fn [show?#]
               (if-not show?#
                 (do
                   ;; Script is a good placeholder element
                   (out! "<script type=\"ripley/placeholder\" data-rl=\"")
                   (dyn! (dynamic/consume-component-id!))
                   (out! "\"></script>"))
                 (render#)))

             id# (p/register! dynamic/*live-context* test# render-component# {})]
         (dynamic/with-component-id id#
           (render-component# show?#)))
       ;; This is non-live
       (when test#
         (render#)))))

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

(defn wrap-placeholder [component-fn]
  (fn [value]
    (if (some? value)
      (component-fn value)
      (let [id (dynamic/consume-component-id!)]
        (out! "<script type=\"ripley/placeholder\" data-rl=\""
              id
              "\"></script>")))))

(defn compile-live
  "Compile special :ripley.html/live element."
  [live-element]
  (let [{:keys [source component patch container should-update?]}
        (live-source-and-component live-element)
        id-sym (gensym "id")
        comp-sym (gensym "component")
        source-sym (gensym "source")]
    (assert (or (nil? container)
                (and (vector? container)
                     (or (= patch :append)
                         (= patch :prepend))))
            "Only :append or :prepend patch methods can have container, which must be a hiccup element vector.")
    `(let [~source-sym (source/source ~source)
           ~comp-sym ~(or component
                          `(fn [thing#]
                             (out! (str thing#))))
           ~id-sym (p/register! dynamic/*live-context* ~source-sym (wrap-placeholder ~comp-sym)
                                ~(merge
                                  {}
                                  (when patch
                                    {:patch patch})
                                  (when should-update?
                                    {:should-update? should-update?})))]
       ~(if container
          ;; If there's a container element for append/prepend content
          ;; render that, with any initial content that is available
          `(dynamic/with-component-id ~id-sym
             ~(compile-html-element
               (conj container
                     `(let [val# (p/current-value ~source-sym)]
                        (when (some? val#)
                          (~comp-sym val#))))))
          `(let [val# (p/current-value ~source-sym)]
             (if (some? val#)
               (dynamic/with-component-id ~id-sym
                 (~comp-sym val#))

               ;; Render placeholder now that will be replaced with contents
               (out! ~(str "<script type=\"ripley/placeholder\" data-rl=\"")
                     ~id-sym
                     "\"></script>")))))))

(defmulti compile-special
  "Compile a special element, that is not regular HTML vector. Dispatches on the first keyword."
  (fn [[special-kw & _rest]] special-kw))

(defmethod compile-special :<> [body] (compile-fragment body))
(defmethod compile-special ::let [body] (compile-let body))
(defmethod compile-special ::for [body] (compile-for body))
(defmethod compile-special ::if [body] (compile-if body))
(defmethod compile-special ::when [body] (compile-when body))
(defmethod compile-special ::cond [body] (compile-cond body))
(defmethod compile-special ::live [body] (compile-live body))

(defn compile-html [body]
  (cond
    (vector? body)
    (cond
      ;; first element is special element
      (contains? (methods compile-special) (first body))
      (compile-special body)

      ;; first element is a keyword this is static HTML markup
      (keyword? (first body))
      (compile-html-element body)

      ;; unrecognized
      :else
      (throw (ex-info "Vector must start with element or special keyword. Hint: call functions with regular parenthesis."
                      {:invalid-first-element (first body)})))

    (string? body)
    ;; Static content
    (if *raw-text-content*
      `(out! ~body)
      `(out! ~(StringEscapeUtils/escapeHtml4 body)))

    ;; Some dynamic content: symbol reference
    (symbol? body)
    `(dyn! ~body)

    ;; Function call (or some other clojure form), pass it as is
    (seq? body)
    body

    :else
    (throw (ex-info (str "Can't compile to HTML: " (pr-str body))
                    {:element body}))))

(defn- do-form? [x]
  (and (seq? x) (= 'do (first x))))

(defn- optimize-nested-do
  "Remove nested do wrappings so next optimizations work better.
  Turns (do a (do b) (do c) d) into (do a b c d)."
  [form]
  (if-not (do-form? form)
    form
    `(do ~@(mapcat
            (fn [f]
              (if (do-form? f)
                (rest f)
                [f]))
            (rest form)))))


(defn- out-form? [x]
  (and (seq? x) (= 'ripley.html/out! (first x))))

(defn- combine-adjacent-string
  "Combine adjacent string values in list.
  (\"a\" \"b\" c \"d\") => (\"ab\" c \"c\")"
  [x]
  (let [[before after] (split-with (complement string?) x)]
    (if (empty? after)
      before
      (let [[strings after] (split-with string? after)]
        (concat before
                (list (str/join strings))
                (when (seq after)
                  (combine-adjacent-string after)))))))

(def ^:private multi-branch-symbols? #{'if 'cond 'case 'if-let})

(defn- multi-branch-form? [x]
  (and (seq? x)
       (multi-branch-symbols? (first x))))


(defn- combine-adjacent-out
  "Combine adjacent out! calls.
  (do
   (out! a b)
   (out! \"c\"))
  =>
  (do (out! a b \"c\"))

  Calls combine-adjacent-string on resulting combined out! calls
  to further optimize strings.
  "
  [x]
  (if (multi-branch-form? x)
    ;; Don't optimize adjacent positions if there may be multiple
    ;; branches in play
    x
    (let [[before after] (split-with (complement out-form?) x)]
      (if (empty? after)
        before
        (let [[outs after] (split-with out-form? after)]
          (concat
           before
           (list (concat (list `out!)
                         (combine-adjacent-string (mapcat rest outs))))
           (when (seq after)
             (combine-adjacent-out after))))))))

(defn- optimize
  "Optimize compiled HTML forms."
  [form]
  (walk/postwalk
   (fn [form]
     (if-not (seq? form)
       form
       (-> form
           optimize-nested-do
           combine-adjacent-out)))
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
       optimize
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
  ([render-fn] (render-response {} render-fn))
  ([response-map render-fn]
   (assert (not (contains? response-map :body))
           "Response map can't contain body. Render gives body.")
   (merge-with
    (fn [a b]
      (if (map? a) (merge a b) b))
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (context/render-with-context render-fn)}
    response-map)))

(defn live-client-script
  ([path]
   (live-client-script path :ws))
  ([path connection-type]
   (assert (or (= connection-type :ws)
               (= connection-type :sse))
           "Supported connection types are WebSocket (:ws) and Server-Sent Events (:sse)")
   (html
    [:script
     (out! (str/replace @patch/live-client-script
                        "__TYPE__" (str "\"" (name connection-type) "\""))
           "\ndocument.onload = ripley.connect('" path "', '" (str (context/current-context-id)) "');")])))
