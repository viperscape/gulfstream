(ns gulfstream.client
  (:gen-class)
  (:use [gulfstream.core]
        [clojure.core.async :only [go thread close! <! >! alts!! put! take! timeout chan sliding-buffer dropping-buffer]]))

(def serverhandler
  (with-conn client
    (prn "client connected!")
    (send! client "welcome!")
    (with-data client data 
      (send! client data) 
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
myserver
;(stop-server myserver)


(def client-conn (start-client (conj conn-details {:handler clienthandler})))
client-conn
(send! client-conn "boot")
(stop-client client-conn)
