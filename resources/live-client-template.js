window.ripley = {
    connection: null,
    debug: false,
    preOpenQueue: [],
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
    onmessage: function(msg) {
        if(this.debug) console.log("Received:", msg);
        var patches = JSON.parse(msg.data);
        var patchlen = patches.length;
        for(var p = 0; p < patchlen; p++) {
            var patch = patches[p];
            var id = patch[0];
            var elt = document.getElementById("__rl"+id);
            if(elt == null) {
                console.error("Received content for non-existant element: ", id,
                              "msg:", msg);
            } else {
                var method = patch[1];
                var payload = patch[2];
                __PATCH__
            }
        }
    }
}