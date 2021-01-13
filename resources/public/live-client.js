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
        };
    },
    onmessage: function(msg) {
        if(this.debug) console.log("Received:", msg);
        var idx = msg.data.indexOf(":");
        var id = msg.data.substring(0,idx);
        var method = msg.data.substring(idx+1,idx+2);
        var content = msg.data.substring(idx+3);
        var elt = document.getElementById("__rl"+id);
        if(elt == null) {
                console.error("Received content for non-existant element: ", id);
        } else {
            switch(method) {
            case "R": elt.outerHTML = content; break; // Replace
            case "A": elt.innerHTML += content; break; // Append
            case "P": elt.innerHTML = content + elt.innerHTML; break; // Prepend
            case "D": elt.parentElement.removeChild(elt); break;// Delete
            case "@": {
                // set attributes
                var attrs = JSON.parse(content);
                for(var attr in attrs) {
                    elt.setAttribute(attr, attrs[attr]);
                }
                break;
            }
            case "E": {
                // eval code with this bound to live component element
                var f = new Function(content);
                f.call(elt);
                break;
            }
            default: console.error("Received unrecognized patch method: ", method);}

        }
    }
}
