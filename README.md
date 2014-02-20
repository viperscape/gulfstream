# gulfstream

My effort to combine both websockets and regular tcp sockets under the same roof, and to homogenize the processes and handling for both client and server with similar semantics.

Is both a regular tcp socket server as well as a websocket server, is able to serve both simultaneously.
Can either be a websocket client or regular tcp socket client.

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
      (if (=(:route client)"echo")(send! client data)) ;;echo back whatever client sent, only if connected at echo route
      (prn "received:" data))
    (prn client "disconnected")))

(def clienthandler
  (with-conn server
    (send! server "i'm here!") ;;on connect send this
    (with-data server data (prn "received from server" data))
    (prn "client is now disconnected")))

(def conn-details {:port 8080
                   :host "192.168.2.5"
                   :handler serverhandler})


(def myserver (start-server conn-details))
;(stop-server myserver) ;;shuts server down and unbinds server socket


(def client-conn (start-client 
	(conj conn-details {:handler clienthandler}))) ;;conj client-handler to replace server-handler functions
(send! client-conn "woot")
;(stop-client client-conn) ;;shuts client down and disconnects socket


;;websocket client connect variation
;; not specifying port assumes port 80, specifying uri with ws:// protocol and optional route will start a websocket client, alternatively specify regular ip/domain with {:ws? true}
(def ws-conn-details {:host "ws://echo.websocket.org/chat",:handler clienthandler})
(def client-conn (start-client ws-conn-details))
(send! client-conn "why hello there")
```

### Non-Compliant

This is not a fully RFC compliant spec websocket server nor client, but it's close and functional. Frame continuations, ping-pong and other op codes such as close are needed still. The handshaking could be improved with verification. However, this does use full frame sizing and proper masking.

### Future implementations

I am considering about adding an async option for both client and server by using go blocks instead of threads-- though performance may suffer; we'll see.

I am planning to include both ping-pong support as well as frame continuations for websockets, but need to consider raw tcp sockets as well for this to be homogenous. In addition I'd like to get away from mostly supporting string and support bytes as well, currently this is only possible with send!, not on receive (yet). These are a phase 2 sort of thing.

I am requesting pulls for a clojurescript variant of the client, ideally I'd like to make the client-handler the exact same but specify in connection details something like (conj conn-details {:cljs true}) and implement a backend set of functions that converts and builds a basic javascript websocket client. Keeping the client-handler peice is key.

## License

Copyright © 2014 Chris Gill

Distributed under the Eclipse Public License.
Have fun with this code, please fork and push any updates you see fit. 
