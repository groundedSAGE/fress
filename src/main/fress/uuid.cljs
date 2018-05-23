(ns fress.uuid
(:require [clojure.string :as string]))

(defn rng []
  (let [r (atom 0)]
    (for [i (range 0 16)]
      (do
        (if (= (bit-and i 0x03) 0)
          (reset! r (* (rand) 0x1000000000)))
        (bit-and (bit-shift-right @r (bit-shift-left (bit-and i 0x03) 3))
                 0xFF)))))

(defn unparse [buf]
  (let [offset (atom 0)]
    (for [n [4 2 2 2 6]]
      (let [token (->> buf
                       (drop @offset)
                       (take n)
                       (map #(-> (js/Uint8Array. (int-array [%]))
                                 (aget 0)
                                 (+ 0x100)
                                 (.toString 16)
                                 (.substr 1)))
                       (apply str))]
        (swap! offset #(+ n %))
        token))))

(defn v4 []
  (->> (rng)
       (map-indexed (fn [idx item]
                      (if (= idx 6)
                        (bit-or (bit-and item 0x0f) 0x40)
                        item)))
       (map-indexed (fn [idx item]
                      (if (= idx 8)
                        (bit-or (bit-and item 0x3f) 0x80)
                        item)))
       (unparse)
       (string/join "-")))

(defn parse [s]
  (let [buf (make-array 16)
        idx (atom 0)]
    (string/replace (.toLowerCase s) #"[0-9a-f]{2}"
      (fn [oct]
        (when (< @idx 16)
          (aset buf @idx (js/parseInt (str "0x" oct)))
          (swap! idx inc))))
    buf))

(defn uuid->bytes [uuid]
  (js/Uint8Array. (parse (.-uuid uuid))))

(defn- create-array-from-typed [array-buffer-view]
  (let [arr (make-array (. array-buffer-view -length))]
    (dotimes [n (count arr)]
      (aset arr n (aget array-buffer-view n)))
    arr))

(defn bytes->uuid [bytes]
  (let [b-array (if (instance? js/Uint8Array bytes)
                  (create-array-from-typed bytes)
                  bytes)]
    (UUID. (string/join "-" (unparse b-array)) nil)))