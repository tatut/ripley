window.ripley = {
    connection: null,
    debug: false,
    preOpenQueue: [],
    get: function(id) {
        return document.querySelector("[data-rl='"+id+"']");
    },
    send: function(id, args) {
        if(this.debug) console.log("Sending id:", id, ", args: ", args);
        let msg = id+":"+JSON.stringify(args);
        if(this.connection.readyState == 0) {
            this.preOpenQueue.push(msg);
        } else {
            this.connection.send(msg);
        }
    },
    connect: function(path, id) {
        var l = window.location;
        var url = (l.host=="https:"?"wss://":"ws://")+l.host+path+"?id="+id;
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
