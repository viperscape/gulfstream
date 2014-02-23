# gulfstream

My effort to combine both websockets and regular tcp sockets under the same roof, and to homogenize the processes and handling for both client and server with similar semantics.

Is both a regular tcp socket server as well as a websocket server, is able to serve both simultaneously.
Can either be a websocket client or regular tcp socket client. Optional raw tcp socket header for more control.


> raw tcp header (in its current state) looks like: 

>> first byte: [final? text? op/tag? 8bit-size 16bit-size 32bit-size reserved1 reserved2]

>> second byte(either op/tag or starts on size): [op/tag here]

>> size-block(either starts at second byte or 3rd byte, depends if op exists), spans either 1, 2 or 4 bytes to contain numeric length of data

>> data-block: anything after size


I'm not sure how/if I'll implement r1 and r2 (if I do they might exist after the data payload). Op will likely be similar to websocket opcodes, though will likely include a route option (something like [header,op-route tag,8bit size of route,[route in bytes]])



## Installation

For now clone this repo, run lein repl.

## Usage

```clojure
(ns my.ns
    (:use [gulfstream.core]))

(def serverhandler
  (with-conn client
    (prn "client connected!")
    (send! client "welcome!")
    (with-data client data ;;logical listen loop is here, anything before is just when initially connected, after is when disconnected
      (if (=(:route client)"echo")(send! client data)) ;;echo back whatever client sent, only if connected at echo route (currently websockets only handle routes)
      (prn "received:" data))
    (prn client "disconnected")))

(def clienthandler
  (with-conn server
    (send! server "i'm here!") ;;on connect send this
    (with-data server data (prn "received from server" data))
    (prn "client is now disconnected")))

(def conn-details {:port 8080
                   :host "localhost"
                   :handler serverhandler})


(def myserver (start-server conn-details))
;(stop-server myserver) ;;shuts server down and unbinds server socket


(def client-conn (start-client 
	(conj conn-details {:handler clienthandler}))) ;;conj client-handler to replace server-handler functions
(send! client-conn "woot")
;(stop-client client-conn) ;;shuts client down and disconnects socket


;;websocket client connect variation
;; not specifying port assumes port 80, specifying uri with ws:// protocol and optional route will start a websocket client, alternatively specify regular ip/domain with {:ws? true}
(def ws-conn-details {:host "ws://echo.websocket.org/chat",:handler clienthandler}) ;;translates to a websocket client with route: chat
(def client-conn (start-client ws-conn-details))
(send! client-conn "why hello there")
```

### Non-Compliant

This is not a fully RFC compliant spec websocket server nor client, but it's close and perfectly functional. Frame continuations, ping-pong and error op codes do not exist, but op-close works fine. The handshaking could be improved with verification but I don't see much of a point. Beyond this, it does use full frame-sizes (no chunking) and proper masking. When I finish raw socket continuation handling I'll implement this for websockets too.

### Future implementations

I am considering about adding an async option for both client and server by using go blocks instead of threads-- though performance may suffer; we'll see.

I am planning to include both ping-pong support as well as frame continuations, but need to consider raw tcp sockets as well for this to be homogenous. Properly supporting strings as well as bytes is important and thus requires a special raw socket header for non-websocket connections.

I am requesting help for a clojurescript variant of the client, ideally I'd like to make the client-handler the exact same but specify in connection details something like (conj conn-details {:cljs true}) and implement a backend set of functions that converts and builds a basic javascript websocket client. Keeping the client-handler peice is key. It's on my radar either way.

## License

Copyright © 2014 Chris Gill

Distributed under the Eclipse Public License.
Have fun with this code, please fork and push any updates you see fit. 
