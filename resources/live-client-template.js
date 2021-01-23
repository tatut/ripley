window.ripley = {
    connection: null,
    debug: false,
    preOpenQueue: [],
    type: __TYPE__,
    get: function(id) {
        return document.querySelector("[data-rl='"+id+"']");
    },
    send: function(id, args) {
        if(this.debug) console.log("Sending id:", id, ", args: ", args);
        if(this.type === "sse") {
            var l = window.location;
            fetch(l.protocol+"//"+l.host+this.cpath+"?id="+this.cid,
                  {method: "POST",
                   headers: {"Content-Type":"application/json"},
                   body: JSON.stringify([id].concat(args))})
        } else if(this.type === "ws") {

            let msg = id+":"+JSON.stringify(args);
            if(this.connection.readyState == 0) {
                this.preOpenQueue.push(msg);
            } else {
                this.connection.send(msg);
            }
        } else {
            console.err("Unknown connection type: ", this.type);
        }
    },
    connect: function(path, id) {
        var l = window.location;
        if(this.type === "sse") {
            var url = l.protocol+"//"+l.host+path+"?id="+id;
            this.connection = new EventSource(url, {withCredentials:true});
            this.connection.onmessage = this.onmessage.bind(this);
            this.cid = id;
            this.cpath = path;
            // FIXME: send pre open queue
        } else if(this.type === "ws") {

            var url = (l.protocol=="https:"?"wss://":"ws://")+l.host+path+"?id="+id;
            this.connection = new WebSocket(url);
            this.connection.onmessage = this.onmessage.bind(this);
            let q = this.preOpenQueue;
            let c = this.connection;
            this.connection.onopen = function(e) {
                for(var i = 0; i<q.length; i++) {
                    c.send(q[i]);
                }
                // clear the array
                q.length = 0;
            };
        } else {
            console.error("Unknown connection type: ", this.type);
        }
    },
    setAttr: function(elt, attr, value) {
        // set attributes, some are set as properties instead
        switch(attr) {
        case "checked": elt.checked = value!==null; break;
        default:
            if(value === null) {
                elt.removeAttribute(attr);
            } else {
                elt.setAttribute(attr,value);
            }
        }
    },
    onmessage: function(msg) {
        if(this.debug) console.log("Received:", msg);
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
}
