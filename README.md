# ripley

*NOTE:* Consider this *alpha* quality and subject to change while I'm figuring things out!


Ripley is a fast server-side rendered web UI toolkit with live components.

Create rich webapps without the need for a SPA frontend.

## Comparison with SPA

Single Page Appplications are complicated things, ripley is a traditional server side
rendered model with websocket enhancement for live parts.

Pros:
- No need for an API just for the frontend
- No need for client-side state management
- No need to wait for large JS download before rendering (or setup complicated SSR for client side apps)
- Leverages browser's native routing
- No separate backend and frontend build complexity, just use Clojure
- Use functions and hiccup to build the UI, like Reagent or other ClojureScript React wrappers

Cons:
- Client browser needs constant connection to server
- Interaction latency is limited by network conditions
- Unsuitable for serverless cloud platforms

## Usage

Using ripley is from a regular ring app is easy. You call `ripley.html/render-response` from a ring
handler to create a response that sets up a live context.

The render response takes a root component that will render the page.
Any rendered live components are registered with the context and updates are sent to the client if
their sources change. Use `ripley.html/html` macro to output HTML with hiccup style markup.

Live components are rendered with the special `:ripley.html/live` element. The live component takes
a source (which implements `ripley.live.protocols/Source`) and a component function. The component
function is called with the value received from the source.

```clojure
(def counter (atom 0))

(defn counter-app [counter]
  (h/html
    [:div
      "Counter value: " [::h/live {:source (atom-source counter)
                                   :component #(h/out! (str %))}]
      [:button {:on-click #(swap! counter inc)} "increment"]
      [:button {:on-click #(swap! counter dec)} "decrement"]]))
```

All event handling attributes (like `:on-click` or `:on-change`) are registered as callbacks
that are sent via the websocket to the server.

See more details and fully working example in the examples folder.
