window.ripley = {
    connection: null,
    debug: false,
    preOpenQueue: [],
    type: __TYPE__,
    connected: false,
    debounceTimeout: {},
    callbackHandlers: {},
    nextCallbackId: 0,
    get: function(id) {
        return document.querySelector("[data-rl='"+id+"']");
    },
    send: function(id, args, debouncems, onsuccess, onfailure) {
        if(this.debug) console.log("Sending id:", id, ", args: ", args);
        if(!this.connected) {
            this.preOpenQueue.push({id: id, args: args, onsuccess: onsuccess, onfailure: onfailure});
        } else {
            if(debouncems === undefined) {
                this._send(id,args,onsuccess,onfailure);
            } else {
                var tid = this.debounceTimeout[id];
                if(tid !== undefined) {
                    window.clearTimeout(tid);
                }
                this.debounceTimeout[id] = window.setTimeout(
                    function() { ripley._send(id,args,onsuccess,onfailure); },
                    debouncems
                );
            }
        }
    },
    _send: function(id,args,onsuccess,onfailure) {
        if(this.type === "sse") {
            var l = window.location;
            fetch(l.protocol+"//"+l.host+this.cpath+"?id="+this.cid,
                  {method: "POST",
                   headers: {"Content-Type":"application/json"},
                   body: JSON.stringify([id].concat(args))})
        } else if(this.type === "ws") {
            let cid;
            let msg;
            if(onsuccess !== undefined || onfailure !== undefined) {
                cid = this.nextCallbackId;
                this.nextCallbackId++;
                msg = [id, args, cid];
                this.callbackHandlers[cid] = {onsuccess: onsuccess, onfailure: onfailure};
            } else {
                msg = [id, args];
            }
            this.connection.send(JSON.stringify(msg));
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
            this.send(cb.id, cb.args, undefined, cb.onsuccess, cb.onfailure);
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
                var elt = id === null ? document : ripley.get(id);
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

        // If application has added an global function with name "ripley_disconnected"
        // then call that.
        let disconnected = window.ripley_disconnected;
        if(typeof(disconnected) === "function") {
            disconnected();
        }
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
    },
    F: function(elt, withContent) {
        let newElt = document.createElement("script");
        elt.insertAdjacentElement("afterend", newElt);
        newElt.outerHTML = withContent;
        newElt.querySelectorAll("script").forEach( (script) => {
            eval(script.text+"")
        })
    },
    T: function(templateElt, data) {
        let target = document.querySelector(data[0]);
        target.textContent="";
        let svg = target.namespaceURI === "http://www.w3.org/2000/svg";
        for(let i=1;i<data.length;i++) {
            if(svg) {
                let g = document.createElementNS(target.namespaceURI, "g");
                g.innerHTML = templateElt.content.firstElementChild.outerHTML.replace(/{{_rl(\d+)}}/g, (_m,k) => data[i][parseInt(k)]);
                target.appendChild(g.firstElementChild);
            } else {
                let n = document.importNode(templateElt.content,true).firstChild;
                n.classList.add("templateItem"+(i%2==0?"Even":"Odd"));
                n.innerHTML = n.innerHTML.replace(/{{_rl(\d+)}}/g, (_m,k) => data[i][parseInt(k)]);
                target.appendChild(n);
            }
        };
    },
    handleResult: function(type, replyId, reply) {
        let handlers = this.callbackHandlers[replyId];
        if(handlers !== undefined) {
            let handle = handlers[type];
            if(handle !== undefined) {
                handle(reply);
            }
            delete this.callbackHandlers[replyId];
        }
    }
}

_rs = ripley.send.bind(ripley);
_rg = ripley.get.bind(ripley);
