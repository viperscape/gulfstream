# gulfstream

My effort to combine both websockets and regular tcp sockets under the same roof, and to homogenize the processes and handling for both client and server with similar semantics.

## Installation

clone this repo, run lein repl.

## Usage

```clojure
(ns gulfstream.client
  (:gen-class)
  (:use [gulfstream.core]
        [clojure.core.async :only [go thread close! <! >! alts!! put! take! timeout chan sliding-buffer dropping-buffer]]))

(def serverhandler
  (with-conn client
    (prn "client connected!")
    (send! client "welcome!")
    (with-data client data ;;logical listen loop is here, anything before is just when initially connected, after is when disconnected
      (send! client data) ;;echo back
      (prn "received:" data))
    (prn client "disconnected")))

(def instream (chan (sliding-buffer 64)))
(def clienthandler
  (with-conn server
    (send! server "i'm here!")
    (with-data server data (put! instream data))
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
```


### Future implementations

I am going add an async option for both client and server using go blocks instead of threads-- though performance may stink; we'll see.

I am planning on include both ping-pong support as well as frame continuations for websockets, but need to consider raw tcp sockets as well for this to be homogenous-- this is a phase 2 sort of thing.

I am requesting pulls for a clojurescript variant of the client, ideally I'd like to make the client-handler the exact same but specify in connection details something like :cljs :true

## License

Copyright © 2014 Chris Gill

Distributed under the Eclipse Public License.
Have fun with this code, please fork and push any updates you see fit. 
