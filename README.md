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

### Dev mode

In development mode, Ripley can be made to replace any `html` macro with an error description panel
that shows exception information and the body source of the form.
This has some performance penalty as all components will first be output into an in-memory `StringWriter`
instead of directly to the response.

Dev mode can be enabled with the system property argument `-Dripley.dev-mode=true` or by setting the
`ripley.html/dev-mode?` atom to true before any `ripley.html/html` macroexpansions take place.

### Client side state

Ripley supports a custom attribute `::h/after-replace` in the component's root
element. When the component is replaced when the source value changes, this
JS fragment is evaluated after the DOM update. This can be used to reinitialize
any client side scripts that are attached to this component. The DOM element that
was replaced is bound to `this` during evaluation.

### Morph support

Ripley by default patches components by setting the `outerHTML`, but in some
cases you want to do morphing using a JS library like [Idiomorph](https://github.com/bigskysoftware/idiomorph).

To make Ripley use a different replace method, pass it as a parameter to the live client script:

```clojure
(live-client-script "/_ws" :replace-method "Idiomorph.morph")
```

Any library can be used, but the function must take in 2 parameters: the node to be morphed and the new HTML content.

Note that Ripley doesn't bundle any morphing library, include it in your page `<head>`.

## Changes

### 2025-05-08
- Support alternative replacement method (like Idiomorph)

### 2025-03-31
- Fix: when the last listener of a computed source unlistens, close the source

### 2024-04-03
- Use new Function instead of eval for after replace JS code

### 2024-04-02
- Add `::h/after-replace` attribute (see Client side state above)

### 2024-03-18
- Add `inert` boolean attribute

### 2024-03-06
- Dev mode: replace component with an error display when an exception is thrown

### 2023-12-27
- Bugfix: also cleanup source that is only used via other computed sources

### 2023-12-05
- Bugfix: handle callback arities correctly when using bindings and no success handler

### 2023-09-21
- Add support for undertow server (thanks @zekzekus)

### 2023-09-20
- Bugfix: proper live collection cleanup on long-lived source (like atom)

### 2023-09-19
- Bugfix: support 0-arity callbacks when wrapping failure/success handlers

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
