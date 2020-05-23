window.ripley = {
    connection: null,
    send: function(id, event) {
        console.log("Sending id:", id, ", event: ", event);
        var payload = id;
        if(event.value||false) {
            payload += ":"+event.value;
        }
        this.connection.send(payload);
    },
    connect: function(path, id) {
        var l = window.location;
        var url = (l.host=="https:"?"wss://":"ws://")+l.host+path+"?id="+id;
        this.connection = new WebSocket(url);
        this.connection.onmessage = this.onmessage;
    },
    onmessage: function(msg) {
        var idx = msg.data.indexOf(":");
        var id = msg.data.substring(0,idx);
        var method = msg.data.substring(idx+1,idx+2);
        var content = msg.data.substring(idx+3);
        var elt = document.getElementById("__ripley-live-"+id);
        if(elt == null) {
                console.error("Received content for non-existant element: ", id);
        } else {
            switch(method) {
            case "R": elt.innerHTML = content; break; // Replace
            case "A": elt.innerHTML += content; break; // Append
            default: console.error("Received unrecognized patch method: ", method);
            }
        }
    }
}
