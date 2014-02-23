(ns gulfstream.raw)

(defn mask [f]
  "final?,text?,op?,size 8 16 32, & two reserved bits
32bit max size value, though having no size specified could be used for double/64bit"
  (let [b (bit-shift-left 1 7)
        b (bit-or (if(:final? f)1 0) b)
        b (bit-or (if(:text? f)2 0) b)
        b (bit-or (if(:op? f)4 0) b)
        b (bit-or (if (> (:size f) 65535) 32(if (> (:size f) 125) 16 8)) b)
        b (- b 128)] ;;clear last bit if planning on using reserved bit
    (unchecked-byte b)))

(defn bits-lit [f]
  "cerates seq of bits that are flagged; consider (map pos? (bits-lit b))"
  (map #(bit-and f (bit-shift-left 1 %)) (range 8)))

(defn read-mask [f]
  "first frame is header- lets convert;
size returned is in bits ex: 32bit"
  (let [h (bits-lit f)]
    (if (> (reduce + h) 0)
    {:final? (pos? (nth h 0))
     :text? (pos? (nth h 1))
     :op? (pos? (nth h 2))
     :size (+ (nth h 3)(nth h 4)(nth h 5))
     })
    ))

(defn frames [d & args]
  "input op code if any, and size of payload, returns bytes"
  (let [c (count d)
        op (some #{:op} args)
        h (raw-mask {:size c :text? (if (string? d) true) :final? (or (some #{:final?} args)true) :op? op})
        s (map #(unchecked-byte(bit-shift-right c (* % 8))) (range 0 (if (> c 65535) 4 (if (> c 125) 2 1))))
        d (if (string? d) (.getBytes d "UTF8") d)
        ba (byte-array (flatten(if op [h op s] [h s])))
        f (make-array Byte/TYPE (+ (count ba)(count d)))
        _ (System/arraycopy ba 0 f 0 (count ba))
        _ (System/arraycopy d 0 f (count ba) (count d))]
    f))

(defn read-frames [b]
  (if-let [h (read-raw-mask (first b))]
    (let [offset (if (:op? h) 2 1)
          s (/ (:size h) 8)
          bs (reduce +(map #(bit-shift-left (bit-and %1 255) (* %2 8)) (drop offset b) (range 0 s)))
          d (drop (+ s offset) b)]
    (if-not (empty? d) {:header h :size bs :data d}))))


(read-frames (frames "hello"))
