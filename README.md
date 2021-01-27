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

## Sources

The main abstraction for working with live components in ripley is the Source.
It provides a way for rendering to get the current value (if available)
and allows the live context to listen for changes.

The source value can be an atomic value (like string, number or boolean) or
a map or collection. The interpretation of the value of the source if entirely
up to the component that is being rendered.


### Built-in sources

Ripley provides built-in sources that integrate regular Clojure
mechanisms into sources. Built-in sources don't require any external
extra dependencies.

You can create sources by calling the specific constructor functions
in `ripley.live.source` namespace or the `to-source` multimethod.


| Type | Description |
| ---- | --- |
| atom | Regular Clojure atoms. Listens to changes with `add-watch`. See: `use-state` |
| use-state | Convenient light weight source for per render local state |
| core.async channel | A core async channel |
| future | Any future value, if realized by render time, used directly. Otherwise patched in after the result is available. |
| promise | A promise, if delivered by render time, used directly. Otherwise patched in after the promise is delivered. |
| computed | Takes one or more input sources and a function. Listens to input sources and calls function with their values to update. See also `c=` convenience macros. |
| split | Takes a map valued input source and keysets. Distributes changes to sub sources only when their keysets change. |

### Integration sources

Ripley also contains integration sources that integrate external state into usable sources.
Integration sources may need external dependencies (not provided by ripley)
see namespace docstring for an integration source in `ripley.integration.<type>`.

| Type | Description |
| ---- | --- |
| redis | Integrate Redis pubsub channels as sources (uses carmine library) |
| manifold | Integrate manifold library `deferred` and `stream` as source |
