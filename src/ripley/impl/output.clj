(ns ripley.impl.output)

(def ^:dynamic *html-out* nil)

(defn render-to-string [component value]
  (str (with-open [out (java.io.StringWriter.)]
         (binding [*html-out* out] (component value))
         out)))
