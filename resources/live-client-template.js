window.ripley = {
    connection: null,
    debug: false,
    preOpenQueue: [],
    type: __TYPE__,
    connected: false,
    debounceTimeout: {},
    get: function(id) {
        return document.querySelector("[data-rl='"+id+"']");
    },
    send: function(id, args, debouncems) {
        if(this.debug) console.log("Sending id:", id, ", args: ", args);
        if(!this.connected) {
            this.preOpenQueue.push({id: id, args: args});
        } else {
            if(debouncems === undefined) {
                this._send(id,args);
            } else {
                var tid = this.debounceTimeout[id];
                if(tid !== undefined) {
                    window.clearTimeout(tid);
                }
                this.debounceTimeout[id] = window.setTimeout(
                    function() { ripley._send(id,args); },
                    debouncems
                );
            }
        }
    },
    _send: function(id,args) {
        if(this.type === "sse") {
            var l = window.location;
            fetch(l.protocol+"//"+l.host+this.cpath+"?id="+this.cid,
                  {method: "POST",
                   headers: {"Content-Type":"application/json"},
                   body: JSON.stringify([id].concat(args))})
        } else if(this.type === "ws") {
            let msg = id+":"+JSON.stringify(args);
            this.connection.send(msg);
        } else {
            console.err("Unknown connection type: ", this.type);
        }
    },
    onopen: function(e) {
        this.connected = true;
        let q = this.preOpenQueue;
        let c = this.connection;
        for(var i = 0; i<q.length; i++) {
            let cb = q[i];
            this.send(cb.id, cb.args);
        }
        // clear the array
        q.length = 0;
    },
    connect: function(path, id) {
        var l = window.location;
        if(this.type === "sse") {
            var url = l.protocol+"//"+l.host+path+"?id="+id;
            this.connection = new EventSource(url, {withCredentials:true});
            this.connection.onmessage = this.onmessage.bind(this);
            this.cid = id;
            this.cpath = path;
            this.connection.onopen = this.onopen.bind(this);
        } else if(this.type === "ws") {
            var url = (l.protocol=="https:"?"wss://":"ws://")+l.host+path+"?id="+id;
            this.connection = new WebSocket(url);
            this.connection.onmessage = this.onmessage.bind(this);
            this.connection.onopen = this.onopen.bind(this);
            this.connection.onclose = this.onclose.bind(this);
        } else {
            console.error("Unknown connection type: ", this.type);
        }
    },
    setAttr: function(elt, attr, value) {
        // set attributes, some are set as properties instead
        if(attr === "checked") {
            elt.checked = value!==null;
        } else if(elt.tagName === "INPUT" && attr === "value") {
            elt.value = value;
        } else if(attr === "class" && elt.hasAttribute("data-rl-class")) {
            // has static class + dynamic part
            elt.className = elt.getAttribute("data-rl-class") + " " + value;
        } else {
            if(value === null) {
                elt.removeAttribute(attr);
            } else {
                elt.setAttribute(attr,value);
            }
        }
    },
    onmessage: function(msg) {
        if(this.debug) console.log("Received:", msg);
        if(msg.data === "!") {
            // Appliation level PING
            this.connection.send("!");
        } else {
            var patches = JSON.parse(msg.data);
            var patchlen = patches.length;
            for(var p = 0; p < patchlen; p++) {
                var patch = patches[p];
                var id = patch[0];
                var elt = ripley.get(id);
                if(elt == null) {
                    console.error("Received content for non-existant element: ", id,
                                  "msg:", msg);
                } else {
                    var method = patch[1];
                    var payload = patch[2];
                    if(this.debug) console.log("elt: ", elt, "method: ", method, ", payload: ", payload);
                    __PATCH__
                }
            }
        }
    },
    onclose: function(evt) {
        // PENDING: reconnect needs server to not cleanup the connection
        // other solution is to reload page (should do only if reconnect
        // fails because server has discarded the live context)
        console.log("WebSocket connection closed", evt);
    },

    // helper for R patch method to work around SVG issues
    // PENDING: need similar fix for appends? try to generalize
    R: function(elt, withContent) {
        if(elt.namespaceURI === "http://www.w3.org/2000/svg") {
            // Some browsers (Safari at least) can't use outerHTML
            // replace as method to patch SVG.
            var parent = elt.parentElement;
            var g = document.createElementNS(parent.namespaceURI, parent.tagName);
            g.innerHTML = withContent;
            elt.replaceWith(g.firstElementChild);
        } else {
            // Simple outerHTML change for HTML elements
            elt.outerHTML = withContent;
        }
        // if there were any scripts in the replaced content, evaluate them
        // we need to refetch the element from DOM after its outerHTML changd
        if(withContent.match(/<script/ig)) {
            let id = elt.getAttribute("data-rl");
            ripley.get(id).querySelectorAll("script").forEach( (script) => {
                eval(script.text+"")
            })
        }
    }
}

_rs = ripley.send.bind(ripley)
