(ns gulfstream.core
  (:gen-class)
  (:import (java.net ServerSocket Socket SocketException)
           (java.io InputStreamReader OutputStream
                    OutputStreamWriter PrintWriter BufferedReader))
  (:use [clojure.core.async :only [go thread close! <! >! alts!! put! take! timeout chan sliding-buffer dropping-buffer]])
  (:require [clojure.data.codec.base64 :as base64]))

(defn -main
  [& args])

(def ds (chan(sliding-buffer 100)))

(defn- peek! [ch & args]
  (if-not (nil? args)
    (let [s (remove nil? (repeatedly (first args) #(peek! ch)))] (if-not (empty? s) s nil))
    (let [[m ch] (alts!! [ch (timeout 1)])] m)))

(defn send! [bouts data]
     (doto bouts
       (.write data 0 (dec(count data)))
       (.flush)))

(defn- get-ws-key [req]
  (first(clojure.string/split(second(clojure.string/split req #"Sec-WebSocket-Key: ")) #"\r\n")))
(defn- make-ws-hash [key]
  (let [s (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")]
    (String.(base64/encode(.digest (java.security.MessageDigest/getInstance "sha1") (.getBytes s))) "UTF8")))
(defn- ws-handshake [req]
  (let [key (make-ws-hash (get-ws-key req))]
    (.getBytes(str "HTTP/1.1 101 Switching Protocols\r\n"
       "Upgrade: websocket\r\n"
       "Connection: Upgrade\r\n"
       "Sec-WebSocket-Accept: " key "\r\n"
       ;"Sec-WebSocket-Protocol: chat\r\n"
       "\r\n\r\n") "UTF8")))

(defn- req-ws! [req bouts]
  (if (.startsWith req "GET") ;;websocket upgrade?
    (do(send! bouts (ws-handshake req))
       true)
    false))

(defn- ws-decode [data]
 ; (prn (bit-and (Integer/toBinaryString (second data)) "0111 1111"))
  )

(defn get-chunk [ins bins buf];;don't stringify
  (if (> (.available ins) 0)
      (let [bufsize (.read bins buf)]
        (prn "receiving buffer size" bufsize)
        (String. buf 0 bufsize "UTF8"))))

(defn- listen! [cs ss]
  (put! ds {"connected" cs})
  (let [ins (.getInputStream cs)
        bins (java.io.BufferedInputStream. ins)
        outs (.getOutputStream cs)
        bouts (java.io.BufferedOutputStream. outs)
        buf (make-array Byte/TYPE 4096)]
    (let [data (get-chunk ins bins buf)
          ws (req-ws! data bouts)]
      (if-not ws 
        (put! ds {cs data});;not websocket? deal with first chunk
        (put! ds {"websocket upgraded!" data}))

      ;(loop [ws? ws]
        (if-let [data (get-chunk ins bins buf)]
          (if ws 
            (let [data (.getBytes data "UTF8")]
              (prn(ws-decode data)))
            (do(put! ds {cs data}))))
        
        (if (.isClosed ss)
          (.close cs))
;        (if-not (.isClosed cs)
 ;         (recur ws?)))
))
  (put! ds {"dropping listen" cs})
)

(defn server [^ServerSocket ss]
  (let [cc (chan (dropping-buffer 100))
        th (thread
            (loop []
              (if-let [cs (.accept ss)]
                (thread(listen! cs ss)))
              (if-not (.isClosed ss) (recur))))]
    {:thread th :cc cc}))
;;;


(def svr (let [s (ServerSocket. 8080)
              svr (server s)]
          (conj svr {:ss s})))
(.close (:ss svr))

(defn- hunt-client [svr]
  (put! ds "beginning hunt")
  (loop []
    (try (take! (:cc svr) (fn [client] 
                       (if client (listen! client (:ss svr)))))
         (catch Exception e (put! ds {:error (.getMessage e)})))

    (if-not (.isClosed (:ss svr)) (recur)))
  (put! ds "end hunt"))

;(dotimes [n 4] (thread (hunt-client svr)))
(.isClosed (:ss svr))
(peek! ds 25)
