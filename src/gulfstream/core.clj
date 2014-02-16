(ns gulfstream.core
  (:import (java.net InetAddress InetSocketAddress ServerSocket Socket SocketException)
           (java.io InputStreamReader OutputStream
                    OutputStreamWriter BufferedReader)) ;;todo-- clean this up!
  (:use [clojure.core.async :only [go thread close! <! >! alts!! put! take! timeout chan sliding-buffer dropping-buffer]])
  (:require [clojure.data.codec.base64 :as base64]))

(def ds (chan (sliding-buffer 100))) ;;debug stream, ignore me


(defn peek! [ch & args]
  "quickly looks at a channel for new incoming data, pulls it if it exists, quits otherwise;
returns seq if specifying max elements to pull"
  (if-not (nil? args)
    (let [s (remove nil? (repeatedly (first args) #(peek! ch)))] (if-not (empty? s) s nil))
    (let [[m ch] (alts!! [ch (timeout 0)])] m)))


(defn close-socket [^Socket s]
  (if-not (.isClosed s)
    (doto s
      (.shutdownInput)
      (.shutdownOutput)
      (.close))))

(defn- ws-mask [data]
  "creates and masks buffer data :todo merge decode mask for conciseness"
  (let [mask (take 4 (repeatedly #(unchecked-byte(rand-int 255))))
        buf (make-array Byte/TYPE (count data))]
    (loop [i 0, j 0]
      (aset-byte buf j (byte (bit-xor (nth data i) (nth mask (mod j 4)))))
      (if (< i (dec(count data))) (recur (inc i) (inc j))))
    {:mask mask :data buf}))


(defn- ws-encode [data & args]
  "takes in bytes, return websocket frame (no chunking available); todo:chunk"
  (let [mres (if-not (empty? (filter #{:mask} args)) (ws-mask data) nil)
        _ (prn "mres:" mres)
        data  (if mres (:data mres) data)
        len (count data)
        blen (if (> len 65535) 10 (if (> len 125) 4 2))
        buf (make-array Byte/TYPE (+ len blen (if mres 4 0)))
        _ (aset-byte buf 0 -127) ;;(bit-or (unchecked-byte 0x80) (unchecked-byte 0x1)
        _ (if (= 2 blen) 
            (aset-byte buf 1 (bit-or (if mres 1 0) len))
            (do
              (dorun(map #(aset-byte buf %1 (unchecked-byte (bit-and (bit-shift-right len (*(- %2 2) 8)) 255))) (range 2 blen) (into ()(range 2 blen))))
              (aset-byte buf 1 (if (> blen 4) 127 126))))
        _ (if mres (dorun (map #(aset-byte buf (+ % blen) (nth (:mask mres) %)) (range 4))))
        _ (System/arraycopy data 0 buf (+ blen (if mres 4 0)) len)]
    buf))

(defn send! [conn data]
  "writes to buffered output stream and flushes"
  (let [d (if (string? data) (.getBytes data "UTF8") data)
        d (if (or (:ws? conn)(:ws conn)) (ws-encode d (if (:ws conn) :mask)) d)] ;;encode if necessary
    (prn "sending:" (String. (if (:ws conn) (ws-decode {:data d :size (count d)}) d) "UTF8"))
     (doto (:bouts conn)
       (.write d 0 (count d))
       (.flush))))


;;websocket stuff- todo: move to another file?

(defn- ws-decode [frame]
  "decodes websocket frame"
  (let [data (:data frame)
        dlen (bit-and (second data) 127)
        mstart (if (== dlen 127) 10 (if (== dlen 126) 4 2))
        mask (drop mstart (take (+ mstart 4) data))
        msg (make-array Byte/TYPE (- (:size frame) (+ mstart 4)))]
    (loop [i (+ mstart 4), j 0]
      (aset-byte msg j (byte (bit-xor (nth data i) (nth mask (mod j 4)))))
      (if (< i (dec(:size frame))) (recur (inc i) (inc j))))
    msg))

(defn- get-ws-key [req]
  (first(clojure.string/split(second(clojure.string/split req #"Sec-WebSocket-Key: ")) #"\r\n")))

(defn- make-ws-hash [key]
  (if key
    (let [s (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")]
      (String.(base64/encode(.digest (java.security.MessageDigest/getInstance "sha1") (.getBytes s))) "UTF8"))))

(defn- ws-handshake [req]
  (if-let [key (make-ws-hash (get-ws-key req))]
    (str "HTTP/1.1 101 Switching Protocols\r\n"
       "Upgrade: websocket\r\n"
       "Connection: Upgrade\r\n"
       "Sec-WebSocket-Accept: " key "\r\n"
       ;"Sec-WebSocket-Protocol: chat\r\n" ;;todo: provide subprotocol support for non-ws sockets as well
       "\r\n")))

(defn- req-ws! [req conn]
  (if (.startsWith req "GET") ;;websocket upgrade?
    (do(put! ds (str "websocket?!" req))
      (send! conn (ws-handshake req))
       true)
    false))
;;end websocket stuff

;;todo, take out buf and use only conn map
(defn get-chunk [conn buf]
  "returns map of bytes and size of buffer for data recieved in stream"
    (let [bufsize (.read (:bins conn) buf)] ;;blocking
      {:size bufsize :data buf}))

(defn try-get-chunk [conn buf]
  (if (> (.available (:ins conn)) 0)
    (get-chunk conn buf)))

(defn str-chunk [chunk]
  "returns a utf8 string of the data-chunk map received in stream from get-chunk"
  (String. (:data chunk) 0 (:size chunk) "UTF8"))



(defn listen! [conn fun]
  "listens to clients instream socket"
  (loop [data (if-not (:ws? conn)(:frame conn))]
    (when(> (or (:size data)0) -1)
      (if-let [data (if data(if (or (:ws conn)(:ws? conn)) (String. (ws-decode data) "UTF8") (str-chunk data)))]
        ;; todo: handle onclose op codes for websockets
        (try(fun data)
            (catch Exception e (put! ds {:listen-error (.getMessage e)}))))

      (recur (get-chunk conn (:data(:frame conn)))))))



(defmacro with-data [c v & body] 
  "preps with-data function similar to with-client, wraps it with the listen! function; the data is then applied to the functions specified within"
  `(listen! ~c (fn with-data#[d#] (let [~v d#] ~@body ))))

(defmacro with-conn [v & body]
  "connection var is filled with conn-map once passed through the handle-client function"
  `(fn with-conn#[c#] (let [~v c#] ~@body)))


(defn- build-client [^Socket cs]
  "builds inital client map to be merged with"
  (let [ins (.getInputStream cs)
        bins (java.io.BufferedInputStream. ins)
        outs (.getOutputStream cs)
        bouts (java.io.BufferedOutputStream. outs)]
    {:ins ins :bins bins :outs outs :bouts bouts :socket cs}))


(defn- handle-conn [conn]
  "typical logic for client: connect, receive loop, close"

  ;;we need to potentially upgrade websocket, attempt to capture first frame
  (let [conn 
        (if (:serversocket conn)
          (let [buf (make-array Byte/TYPE 4096)
                data (get-chunk conn buf) ;;blocks and waits
                ws-handshake? (req-ws! (str-chunk data) conn)
                conn (conj conn {:ws? ws-handshake? :frame data})]
            conn)
          ;;handle if client is connecting to websocket server
          (let [conn (conj conn {:frame {:size 0 :data (make-array Byte/TYPE 4096)}})]
            (if (:ws? conn) 
              (do(prn "connecting via websockets") 
               conn)
              conn)))]
    (if-not (:serversocket conn)(prn conn))

    (try ((:handler conn) conn)
         (catch Exception e (put! ds (str (if (:serversocket conn) "server-side ") "handle error:" (.getMessage e)))))
    (close-socket (:socket conn))))



(defn- await-clients [server]
  "spins off 1 thread to accept new clients on, spins off go blocks for client handling, merges client-map with server-map to supply serversocket and handler info"
  (let [server (conj server {:host (.getInetAddress (:serversocket server))})
        server (conj server 
                     {:thread (thread (loop [] 
                         (if-let [socket (.accept (:serversocket server))]
                           (let [client (conj (build-client socket) server)]
                             (thread(handle-conn client))))
                         (if-not (.isClosed (:serversocket server)) (recur))))
                      })]
    server))



(defn start-server [server]
  (if-let [socket (ServerSocket. (:port server) 0 (InetAddress/getByName (:host server)))]
    (await-clients (conj server {:serversocket socket}))))
(defn stop-server [server]
  (.close (:serversocket server))
  (close! (:thread server)))


(defn start-client [server]
  (let [client (conj server (build-client (Socket. (:host server) (:port server))))
        client (conj client {:thread (thread(handle-conn (conj client server)))})]
    client))
(defn stop-client [client]
  (close-socket (:socket client))
  (close! (:thread client)))
