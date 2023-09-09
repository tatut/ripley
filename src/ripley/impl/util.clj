(ns ripley.impl.util
  "Some implementation utilities.")

(defn arity ^long [f]
  (-> f class .getDeclaredMethods first .getParameterTypes alength))
