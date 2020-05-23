# ripley

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