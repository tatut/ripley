(ns ripley.live.hash-route
  "A live component for reacting to changes in the window location hash."
  (:require [ripley.html :as h]
            [ripley.live.context :as context]
            [ripley.live.protocols :as p]))

(defn on-hash-change
  "Output script that calls a callback function when the window
  location hash changes. The callback will receive the new hash
  as parameter."
  [callback]

  (let [callback-id (p/register-callback! context/*live-context*
                                          callback)]
    (h/out! "<script>"
            " window.onhashchange = function() {"
            " ripley.send(" callback-id ", [window.location.hash]);"
            "}"
            ;; Immediately send an update, if hash isn't empty
            "\nripley.send(" callback-id ",[window.location.hash]);"
            "</script>")))
