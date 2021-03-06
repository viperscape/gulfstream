(ns gulfstream.core
  (:import (java.net InetAddress InetSocketAddress ServerSocket Socket SocketException)
           (java.io InputStreamReader OutputStream
                    OutputStreamWriter BufferedReader)) ;;todo-- clean this up!
  (:use [clojure.core.async :only [go thread <! >! alts!! put! take! timeout chan sliding-buffer dropping-buffer]])
  (:require [gulfstream.ws :as ws]
            [gulfstream.raw :as raw]))

(def ds (chan (sliding-buffer 100))) ;;debug stream, ignore me
(declare stop-client)

(defn peek! [ch & args]
  "quickly looks at a channel for new incoming data, pulls it if it exists, quits otherwise;
returns seq if specifying max elements to pull"
  (if-not (nil? args)
    (let [s (remove nil? (repeatedly (first args) #(peek! ch)))] (if-not (empty? s) s nil))
    (let [[m ch] (alts!! [ch (timeout 0)])] m)))


(defn send! [conn data & args]
  "writes to buffered output stream and flushes"
  (let [prestine? (some #{:prestine} args)
        op (if (string? data) 1 2) ;;text or binary op code for websockets
        d (if (and(string? data)(not prestine?)) (.getBytes data "UTF8") data) ;;we have to send utf8 bytes for websockets
        d (if (and(:ws? conn)(not prestine?)) (ws/encode d {:op op} (if-not (:serversocket conn) :mask)) d)]
    (doto (:bouts conn)
      (.write d 0 (count d))
      (.flush))))


(defn close-socket [^Socket s]
  (if-not (.isClosed s)
    (doto s
      (.shutdownInput)
      (.shutdownOutput)
      (.close))))


(defn get-chunk [conn]
  "returns map of bytes and size of buffer for data recieved in stream"
    (let [size (.read (:bins conn) (:data (:frame conn)))] ;;blocking
      {:size size :data (:data (:frame conn))}))

(defn try-get-chunk [conn]
  "non-waiting version of get-chunk"
  (if (> (.available (:ins conn)) 0)
    (get-chunk conn)))

(defn str-chunk [chunk]
  "returns a utf8 string of the data-chunk map received in stream from get-chunk"
  (String. (:data chunk) 0 (:size chunk) "UTF8"))



(defn listen! [conn fun]
  "listens to clients instream socket"
  (loop [data (if-not (:ws? conn) (:frame conn)) cont []]
    (when(> (or (:size data)0) -1)
      (let [d (if data (if (:ws? conn) 
                         (let [d (ws/decode data)] 
                           (cond 
                            (= (:op d) 0) (do(prn "cont. frame!")(conj d {:cont true})) ;;in ws only first frame represents type
                            (= (:op d) 1) (if (:final? d) (conj d {:text? true}) (conj d {:text? true :cont true})) ;;text frame
                            (= (:op d) 2) (if (:final? d) d (conj d {:cont true})) ;;binary frame
                            (= (:op d) 8) (do(stop-client conn)nil) ;;op close?
                            (= (:op d) 9) (send! conn (ws/encode (:data d) {:op 10} (if-not (:serversocket conn) :mask)) :prestine) ;;ping frame? return identical data in a pong frame
                            (= (:op d) 10) (prn "pong frame!")
                            :else (do(put! ds (str "else" (:op d)))nil)))
                         (if (:raw? conn)
                           (let [d (raw/read-frames data)]
                             (cond
                              (= (:op d) 1) (do(prn "route change!"){:route (String. (:data d) "UTF8")})
                              (not(:final?(:header d))) (do(prn "cont. frame!")(conj d{:text? (:text? (:header d)) :cont true}))
                              :else (if (empty? cont) (conj {:text? (:text? (:header d))} d) {:text? (:text? (:header d)) :cont true :final? true})))
                           {:final? true :data (:data data)})))]
        ;(let [r {:route (or (:route d) (:route conn))}
              ;]

        (if (:final? d)
          (let [d (if-not (:cont d) (if (:text? d) (String. (:data d) "UTF8") (:data d))
                          (let [ba (byte-array (mapcat vec (conj cont (:data d))))]
                            (if (:text? d) (String. ba "UTF8") ba)))]
            (try(fun d)
                (catch Exception e (put! ds {:listen-error (.getMessage e)})))))

        (recur (get-chunk conn) (if-not (:final? d) (conj cont (:data d)) []))))))


(defmacro with-data [c v & body] 
  "preps with-data function similar to with-client, wraps it with the listen! function; 
the data is then applied to the functions specified within"
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

(defn- req-ws! [req conn]
  (if (.startsWith req "GET") ;;websocket upgrade?
    (do(send! conn (ws/handshake req))
       (conj conn {:ws? true :route (first(clojure.string/split (second(clojure.string/split req #" /")) #" "))}))
       ;true)
    false))

(defn- handle-conn [conn]
  "typical logic for client: connect, receive loop, close"
  ;;we need to potentially upgrade websocket, attempt to capture first frame
  (let [conn 
        (if (:serversocket conn)
          (let [conn (conj conn {:frame {:size 0 :data (make-array Byte/TYPE 4096)}})
                data (get-chunk conn) ;;blocks and waits
                conn (or(req-ws! (str-chunk data) conn)(conj conn {:ws? false}))
                conn (conj conn {:frame data})]
            conn)
          ;;handle if client is connecting to websocket server
          (let [conn (conj conn {:frame {:size 0 :data (make-array Byte/TYPE 4096)}})]
            (if (:ws? conn) 
              (do
                 (send! (conj conn {:ws? nil}) (ws/new-handshake (:host conn) (:port conn) {:route (:route conn)}))
                 (get-chunk conn))) ;;wait and accept handshake, throw it away and start handler down below; todo: verify handshake sec key?
            conn))]

    (try ((:handler conn) conn)
         (catch Exception e (put! ds (str (if (:serversocket conn) "server-side ") "handle error:" (.getMessage e)))))

    (stop-client conn)))


(defn- await-clients [server]
  "spins off 1 thread to accept new clients on, spins off threads for client handling, 
merges client-map with server-map to supply serversocket and handler info"
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
  "note:i'm not graceful"
  (.close (:serversocket server)))


(defn start-client [server]
  (let [server (conj server (if-not (:port server) {:port 80}))
        server (conj server (if (.startsWith (:host server) "ws://") 
                              {:ws? true
                               :host (second(clojure.string/split (:host server) #"ws://"))}))
        server (conj server (let [h (clojure.string/split (:host server) #"/")]
                              {:host (first h)
                               :route (second h)}))
        client (conj server (build-client (Socket. (:host server) (:port server))))
        client (conj client {:thread (thread(handle-conn (conj client server)))})]
    client))
(defn stop-client [conn]
  (when-not (.isClosed (:socket conn));(if-not (:serversocket conn)(.isClosed (:serversocket conn))))
    (if(:ws? conn) (send! conn (ws/encode (.getBytes "\r\n" "UTF8") {:op 8} (if-not (:serversocket conn) :mask)) :prestine)) ;;send websocket close, but don't wait for response
    (close-socket (:socket conn))))

