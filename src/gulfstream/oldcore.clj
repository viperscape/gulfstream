(ns gulfstream.core
  (:gen-class)
  (:import (java.net ServerSocket Socket SocketException)
           (java.io InputStreamReader OutputStream
                    OutputStreamWriter PrintWriter BufferedReader))
  (:use [clojure.core.async]))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))

(def ds (chan(sliding-buffer 100)))


(defn- peek! [ch & args]
  (if-not (nil? args)
    (let [s (remove nil? (repeatedly (first args) #(peek! ch)))] (if-not (empty? s) s nil))
    (let [[m ch] (alts!! [ch (timeout 1)])] m)))

(defn- send! [out data]
     (doto out
       (.println data)
       (.flush)))

(defn- listen! [cs ss]
  (let [ins (.getInputStream cs)
        ;in (BufferedReader. (InputStreamReader. ins))
        bins (java.io.BufferedInputStream. ins)
        buf (make-array Byte/TYPE 500)
        out (PrintWriter. (.getOutputStream cs))]
  (loop []
    (if (> (.available ins) 0)
     ; (if-not (nil? ws?)
      (do
        (let [bufsize (.read bins buf)]
         (go(>! ds (String. buf 0 bufsize "UTF8"))))))
    (if (.isClosed ss)
      (.close cs))
    (if-not (.isClosed cs)
      (recur)))))

(defn server [^ServerSocket ss] 
  (thread 
    (loop []
      (if-let [cs (.accept ss)]
        (go(listen! cs ss)))
      (if-not (.isClosed ss) (recur)))))
;;;


(def ss (let [s (ServerSocket. 8000)]
          (server s)
          s))

(prn (peek! ds 5))

(.close ss)

