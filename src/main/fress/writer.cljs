(ns fress.writer
  (:require-macros [fress.macros :refer [>>>]])
  (:require [fress.codes :as codes]
            [fress.ranges :as ranges]
            [fress.raw-output :as rawOut]
            [goog.string :as gstring]))

(comment
 (defn utf8-encoding-size
   "src/org/fressian/impl/Fns.java:117:4"
   [ch]
   (assert (int? ch) "ch should be charCode taken from string index")
   (if (<= ch 0x007f)
     1
     (if (< 0x07ff ch)
       3
       2))))

(comment
 (defn buffer-string-chunk-utf8
   "starting with position start in s, write as much of s as possible into byteBuffer
   using UTF-8.
   returns {stringpos, bufpos}"
   [s start buf]
   (loop [string-pos start
          buffer-pos 0]
     (if (< string-pos (alength s))
       (let [ ch (.charCodeAt s string-pos)
             encoding-size (utf8-encoding-size ch)]
         (if (< (alength buf) (+ buffer-pos encoding-size))
           [string-pos buffer-pos]
           (do
             (case encoding-size
               1 (aset buf buffer-pos ch)
               2 (do
                   (aset buf buffer-pos       (bit-or 0xc0 (bit-and (bit-shift-right ch 6) 0x1f)))
                   (aset buf (inc buffer-pos) (bit-or 0x80 (bit-and (bit-shift-right ch 0) 0x3f))))
               3 (do
                   (aset buf buffer-pos       (bit-or 0xe0 (bit-and (bit-shift-right ch 12) 0x0f)))
                   (aset buf (inc buffer-pos) (bit-or 0x80 (bit-and (bit-shift-right ch 6)  0x3f)))
                   (aset buf (+ buffer-pos 2) (bit-or 0x80 (bit-and (bit-shift-right ch 0)  0x3f)))))
             (recur (inc string-pos) (+ buffer-pos encoding-size)))))
       [string-pos buffer-pos]))))

(defprotocol IFressianWriter
  (writeNull ^FressianWriter [this])
  (writeNumber ^FressianWriter [this n]) ;<=unique
  (writeBoolean ^FressianWriter [this b])
  (writeInt ^FressianWriter [this i])
  (writeDouble ^FressianWriter [this d])
  (writeFloat ^FressianWriter  [this f])
  (writeString ^FressianWriter [this s])
  (writeIterator [this length it])
  (writeList ^FressianWriter [this o])
  (writeBytes ^FressianWriter
              [this bs]
              [this bs offset length])
  (writeFooterFor [this byteBuffer])
  (writeFooter ^FressianWriter [this])
  (internalWriteFooter [this length])
  (clearCaches [this])
  (resetCaches ^FressianWriter [this]"public")
  (getPriorityCache ^InterleavedIndexHopMap [this]"public")
  (getStructCache ^InterleavedIndexHopMap [this]"public")
  (writeTag ^FressianWriter [this] "public")
  (writeExt ^FressianWriter [this]"public")
  (writeCount [this n] "public")
  (bitSwitch ^int [this l] "private")
  ; (internalWriteInt [this i] "private")
  (shouldSkipCache ^boolean [this o] "private")
  (doWrite [this tag o w cache?] "private")
  (writeAs ^FressianWriter
           [this tag o]
           [this tag o cache?] "public")
  (writeObject ^FressianWriter
               [this o]
               [this o cache?] "public")
  (writeCode [this code] "public")
  (close [this] "public")
  (beginOpenList ^FressianWriter [this] "public")
  (beginClosedList ^FressianWriter [this] "public")
  (endList ^FressianWriter [this] "public")
  (getByte [this index]))

(defn ^number bit-switch
  "@return {number}(bits not needed to represent this number) + 1"
  [l]
  (- 64 (.-length (.toString (.abs js/Math l) 2))))

(defn internalWriteInt [wtr ^number n]
  (let [s (bit-switch n)
        raw (.-raw-out wtr)]
    (cond
      (<=  1 s 14)
      (do
        (writeCode wtr codes/INT)
        (rawOut/writeRawInt64 raw n))

      (<= 15 s 22)
      (do
        (rawOut/writeRawByte raw (+ codes/INT_PACKED_7_ZERO (>>> n 48)))
        (rawOut/writeRawInt48 raw n))

      (<= 23 s 30)
      (do
        (rawOut/writeRawByte raw (+ codes/INT_PACKED_6_ZERO (>>> n 40)))
        (rawOut/writeRawInt40 raw n))

      (<= 31 s 38)
      (do
        (rawOut/writeRawByte raw (+ codes/INT_PACKED_5_ZERO (>>> n 32)))
        (rawOut/writeRawInt32 raw n))

      (<= 39 s 44)
      (do
        (rawOut/writeRawByte raw (+ codes/INT_PACKED_4_ZERO (>>> n 24)))
        (rawOut/writeRawInt24 raw n))

      (<= 45 s 51)
      (do
        (rawOut/writeRawByte raw (+ codes/INT_PACKED_3_ZERO (>>> n 16)))
        (rawOut/writeRawInt16 raw n))

      (<= 52 s 57)
      (do
        (rawOut/writeRawByte raw (+ codes/INT_PACKED_2_ZERO (>>> n 8)))
        (rawOut/writeRawByte raw n))

      (<= 58 s 64)
      (do
        (when (< n -1)
          (rawOut/writeRawByte raw (+ codes/INT_PACKED_2_ZERO (>>> n 8))))
        (rawOut/writeRawByte raw n))

      :default
      (throw (js/Error. "more than 64 bits in a long!")))))

(def TextEncoder (js/TextEncoder.))

(defrecord FressianWriter [out raw-out priorityCache structCache sb handlers]
  IFressianWriter
  (getByte [this index] (rawOut/getByte raw-out index))

  (writeCode [this code] (rawOut/writeRawByte raw-out code))

  (writeCount [this n] (writeInt this n))

  (writeNull [this] (writeCode this codes/NULL))

  (writeNumber [this n]
    (if (int? n)
      (writeInt this n)
      (if (< (.pow js/Math 2 -126) n (.pow js/Math 2 128))
        (writeFloat this n)
        (writeDouble this n))))

  (writeInt [this i]
    (if (nil? i)
      (do
        (writeNull this)
        this)
      (do
        (assert (int? i))
        ;; in java this is coerced to a long
        (internalWriteInt this i)
        this)))

  (writeBytes [this bytes]
    (if (nil? bytes)
      (do
        (writeNull this)
        this)
      (writeBytes this bytes 0 (.-byteLength bytes))))

  (writeBytes [this bytes offset length]
    (assert (instance? js/Int8Array bytes) "writeRawBytes expects a Int8Array")
    (if (< length ranges/BYTES_PACKED_LENGTH_END)
      (do
        (rawOut/writeRawByte raw-out (+ codes/BYTES_PACKED_LENGTH_START length))
        (rawOut/writeRawBytes raw-out bytes offset length))
      (loop [len length
             off offset]
        (if (< ranges/BYTE_CHUNK_SIZE len)
          (do
            (writeCode this codes/BYTES_CHUNK)
            (writeCount this ranges/BYTE_CHUNK_SIZE)
            (rawOut/writeRawBytes raw-out bytes off ranges/BYTE_CHUNK_SIZE)
            (recur
              (- len ranges/BYTE_CHUNK_SIZE)
              (+ off ranges/BYTE_CHUNK_SIZE)))
          (do
            (writeCode this codes/BYTES)
            (writeCount this len)
            (rawOut/writeRawBytes raw-out bytes off len))))) ;;<== test this line
    this)


#_(writeString [this s] ;string packing needs to be relaxed for wasm, no necesary
   (let [max-buf-needed (min (* (count s) 3) 65536)
         string-buffer (js/Int8Array. (js/ArrayBuffer. max-buf-needed))]
     (loop [[string-pos buf-pos] (buffer-string-chunk-utf8 s 0 string-buffer)]
       (if (< buf-pos ranges/STRING_PACKED_LENGTH_END)
         (writeCode this (+ codes/STRING_PACKED_LENGTH_START buf-pos))
         (if (= string-pos (count s))
           (do
             (writeCode this codes/STRING)
             (writeCount this buf-pos))
           (do
             (writeCode this codes/STRING_CHUNK)
             (writeInt this buf-pos))))
       (rawOut/writeRawBytes raw-out string-buffer 0 buf-pos)
       (when (< string-pos (count s))
         (recur (buffer-string-chunk-utf8 s string-pos string-buffer))))))

  (writeString [this ^string s]
    (assert (string? s))
    ; breaking from fressian because  we can use TextEncoder to remove some dirty work
    ; and 8 byte chunking trigger is inefficient
    ; Instead, just replicating byte behavior... if string is >64kB, it will be chunked.
    ; Otherwise if < 64kb writing string bytes in one shot
    (let [bytes (.encode TextEncoder s)
          length (.-byteLength bytes)]
      (if-not (< BYTE_CHUNK_SIZE length)
        (do
          (writeCode this codes/STRING) ; may need unique code here, breaking std fressian behavior
          (writeCount this length)
          (rawOut/writeRawBytes raw-out bytes 0 length))
        (loop [len length
               off offset]
          (if (< ranges/BYTE_CHUNK_SIZE len)
            (do
              (writeCode this codes/STRING_CHUNK) ; may need unique code here, breaking std fressian behavior
              (writeCount this ranges/BYTE_CHUNK_SIZE) ; may need unique code here, breaking std fressian behavior*
              (rawOut/writeRawBytes raw-out bytes off ranges/BYTE_CHUNK_SIZE)
              (recur
                (- len ranges/BYTE_CHUNK_SIZE)
                (+ off ranges/BYTE_CHUNK_SIZE)))
            (do
              (writeCode this codes/STRING)
              (writeCount this len)
              (rawOut/writeRawBytes raw-out bytes off len))))))))



(def default-write-handlers {})

(defn Writer
  [out handlers]
  (let [handlers (merge default-write-handlers handlers)
        raw-out (rawOut/raw-output)]
    (FressianWriter. out raw-out nil nil nil handlers)))