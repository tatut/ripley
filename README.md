# ripley

![test workflow](https://github.com/tatut/ripley/actions/workflows/test.yml/badge.svg)

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
                                   :component #(h/html [:span %])}]
      [:button {:on-click #(swap! counter inc)} "increment"]
      [:button {:on-click #(swap! counter dec)} "decrement"]]))
```

All event handling attributes (like `:on-click` or `:on-change`) are registered as callbacks
that are sent via the websocket to the server. See `ripley.js` namespace for helpers in creating
callbacks with more options. You can add debouncing and client side condition and success/failure
handlers.

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
| xtdb | Integrate XTDB query as an automatically updating source |

## Working with components

### Component functions

In Ripley, components are functions that take in parameters and **output** HTML fragment
as a side-effect. They do not return a value. This is different from normal hiccup, where
functions would return a hiccup vector describing the HTML.

Ripley uses the `ripley.html/html` macro to convert a hiccup style body into plain Clojure
that writes HTML. The macro also adds Ripley's internal tracking attributes so components
can be updated on the fly.

Any Clojure code can be called inside the body, but take note that return values are discarded.
This is a common mistake, forgetting to use the HTML macro in a function and returning a vector.
The caller will simply discard it and nothing is output.

### Child components

Components form a tree so a component can have child components with their own sources. The children
are registered under the parent and if the parent fully rerenders, the children are recursively
cleaned up. A component does not need to care if it is at the top level or a child of some other
component.

The main consideration comes from the sources used. If the parent component creates per render
sources for the children, the children will lose the state when the parent is rerendered.

### Dynamic scope

Ripley supports capturing dynamic scope that was in place when a component or callback was created.
This can be used to avoid passing in every piece of context to all components (like user information
or db connection pools). The set of vars to capture must be configured when calling
`ripley.html/render-response`.

## Changes

### 2023-09-16
- Alternate server implementation support (with pedestal+jetty implementation)

### 2023-09-09
- Support dynamic binding capture

### 2023-09-02
- Support a `::h/live-let` directive that is more concise

### 2023-08-30
- Log errors in component render and callback processing

### 2023-08-26
- Fix bug in live collection cleanup not being called

### 2023-08-04
- Fix computed source when calculation fn is not pure (eg. uses current time)

### 2023-07-01
- Added `ripley.js/export-callbacks` to conveniently expose server functions as JS functions
- Added `static` utility to use a static value as a source

### 2023-06-28
- Source value can be `nil` now and component is replaced with placeholder

### 2023-06-10
- Support client side success and failure callbacks

### 2023-06-07
- `ripley.html/compile-special` is now a multimethod and can be extended

### 2023-03-18
- Support specifying `:should-update?` in `::h/live`
- `use-state` now returns a third value (update-state! callback)

### See commit log for older changes
