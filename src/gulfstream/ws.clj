(ns gulfstream.ws
  (:require [clojure.data.codec.base64 :as base64]))

(defn mask [data]
  "creates and masks buffer data :todo merge decode mask for conciseness"
  (let [mask (take 4 (repeatedly #(unchecked-byte(rand-int 255))))
        buf (make-array Byte/TYPE (count data))]
    (loop [i 0, j 0]
      (aset-byte buf j (byte (bit-xor (nth data i) (nth mask (mod j 4)))))
      (if (< i (dec(count data))) (recur (inc i) (inc j))))
    {:mask mask :data buf}))


(defn encode [data & args]
  "takes in bytes, return websocket frame (no chunking available); todo:chunk"
  (let [mres (if (some #{:mask} args) (mask data))
        data  (if mres (:data mres) data)
        len (count data)
        blen (if (> len 65535) 10 (if (> len 125) 4 2))
        buf (make-array Byte/TYPE (+ len blen (if mres 4 0)))
        op (or(->> args (filter map?)(map :op) first)1) ;(first(map #(% :op) (filter #(map? %) args)))1)
        _ (aset-byte buf 0 (unchecked-byte(bit-or 0x80 op)))
        _ (if (= 2 blen) 
            (aset-byte buf 1 (unchecked-byte (bit-or (bit-shift-left (if mres 1 0) 7) len)))
            (do
              (dorun(map #(aset-byte buf %1 (unchecked-byte (bit-and (bit-shift-right len (*(- %2 2) 8)) 255))) (range 2 blen) (into ()(range 2 blen))))
              (aset-byte buf 1  (unchecked-byte (bit-or (bit-shift-left (if mres 1 0) 7) (if (> blen 4) 127 126))))))
        _ (if mres (dorun (map #(aset-byte buf (+ % blen) (nth (:mask mres) %)) (range 4))))
        _ (System/arraycopy data 0 buf (+ blen (if mres 4 0)) len)]
    buf))

(defn decode [frame & args]
  "decodes websocket frame"
  (let [data (:data frame)
        op (bit-and (first data) 0x0f)]
    (if-not (>= op 8)
      (let [dlen (bit-and (second data) 127) ;0x7f/127
            mask? (= 1(bit-and(bit-shift-right (second data) 7) 1)) ;;has a mask?
            mstart (if (== dlen 127) 10 (if (== dlen 126) 4 2))
            mask (if mask? (drop mstart (take (+ mstart 4) data)))
            msg (make-array Byte/TYPE (- (:size frame) (+ mstart (if mask? 4 0))))]
        (if mask? 
          (loop [i (+ mstart 4), j 0]
            (aset-byte msg j (byte (bit-xor (nth data i) (nth mask (mod j 4)))))
            (if (< i (dec(:size frame))) (recur (inc i) (inc j))))
          (System/arraycopy data mstart msg 0 (- (:size frame) mstart)))
        {:data msg :op op})
      {:data nil :op op})))

(defn get-key [req]
  (first(clojure.string/split(second(clojure.string/split req #"Sec-WebSocket-Key: ")) #"\r\n")))


(defn make-hash [key]
  (if key
    (let [s (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")]
      (String.(base64/encode(.digest (java.security.MessageDigest/getInstance "sha1") (.getBytes s))) "UTF8"))))

(defn handshake [req]
  (if-let [key (make-hash (get-key req))]
    (str "HTTP/1.1 101 Switching Protocols\r\n"
       "Upgrade: websocket\r\n"
       "Connection: Upgrade\r\n"
       "Sec-WebSocket-Accept: " key "\r\n"
       ;"Sec-WebSocket-Protocol: chat\r\n" ;;todo: provide subprotocol support for non-ws sockets as well
       "\r\n")))

(defn new-handshake [host port & args]
  (let [route (some #{:route} args)]
    (str "GET /" route " HTTP/1.1\r\n"
         "Host: " host ":" port "\r\n"
         "Upgrade: websocket\r\nConnection: Upgrade\r\n"
         "Origin: gulfstream/clojure\r\n"
         "Pragma: no-cache\r\nCache-Control: no-cache\r\n"
         "Sec-WebSocket-Key: " (String.(clojure.data.codec.base64/encode (byte-array(map #(byte %)(take 16(repeatedly #(unchecked-byte(rand 255))))))) "UTF8")  "\r\n"
         "Sec-WebSocket-Version: 13\r\n"
      ; "Sec-WebSocket-Extensions: x-webkit-deflate-frame\r\n"
      ; "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/31.0.1650.63 Safari/537.36\r\n"
         "\r\n")))
