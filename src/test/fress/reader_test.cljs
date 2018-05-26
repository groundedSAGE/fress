(ns fress.reader-test
  (:require-macros [fress.macros :refer [>>>]]
                   [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :as casync
             :refer [close! put! take! alts! <! >! chan promise-chan timeout]]
            [cljs.test :refer-macros [deftest is testing async]]
            [cljs.tools.reader :refer [read-string]]
            [fress.impl.raw-output :as rawOut]
            [fress.impl.raw-input :as rawIn]
            [fress.codes :as codes]
            [fress.ranges :as ranges]
            [fress.reader :as r]
            [fress.test-helpers :as helpers
             :refer [log jvm-byteseq is= byteseq overflow into-bytes
                     precision= kinda=]]))

(defn rawbyteseq [rdr]
  (let [raw (.-raw-in rdr)
        acc #js[]]
    (loop []
      (let [byte (rawIn/readRawByte raw)]
        (if-not byte
          (vec acc)
          (do
            (.push acc byte)
            (recur)))))))

(def int-samples
  [{:form "Short/MIN_VALUE", :value -32768, :bytes [103 -128 0], :rawbytes [103 128 0]}
   {:form "Short/MAX_VALUE", :value 32767, :bytes [104 127 -1], :rawbytes [104 127 255]}
   {:form "Integer/MIN_VALUE", :value -2147483648, :bytes [117 -128 0 0 0], :rawbytes [117 128 0 0 0]}
   {:form "Integer/MAX_VALUE", :value 2147483647, :bytes [118 127 -1 -1 -1], :rawbytes [118 127 255 255 255]}
   ;;;;min int40
   {:form "(long -549755813887)", :value -549755813887, :bytes [121 -128 0 0 0 1], :rawbytes [121 128 0 0 0 1]}
   ;;; max int40
   {:form "(long 549755813888)", :value 549755813888, :bytes [122 -128 0 0 0 0], :rawbytes [122 128 0 0 0 0]}
   ;;;; max int48
   {:form "(long 1.4073749E14)", :value 140737490000000, :bytes [126 -128 0 0 25 24 -128], :rawbytes [126 128 0 0 25 24 128]}
   ; ;MAX_SAFE_INT                                                                                                         a    b  c   d   e   f   g   h
   {:form "(long  9007199254740991)", :value  9007199254740991, :bytes [-8  0  31 -1 -1 -1 -1 -1 -1],      :rawbytes [248   0  31 255 255 255 255 255 255]}
   ; MAX_SAFE_INT++
   {:form "(long 9007199254740992)", :value 9007199254740992, :bytes [-8 0 32 0 0 0 0 0 0], :rawbytes [248  0  32 0 0 0 0 0 0] :throw? true}
   ;;;;MIN_SAFE_INTEGER
   {:form "(long -9007199254740991)", :value -9007199254740991,       :bytes [-8 -1 -32 0 0 0 0 0 1],  :rawbytes [248 255 224 0 0 0 0 0 1] :throw? false}
   ;;;; MIN_SAFE_INTEGER--
   {:form "(long -9007199254740992)", :value -9007199254740992,       :bytes [-8 -1 -32 0 0 0 0 0 0],  :rawbytes [248 255 224 0 0 0 0 0 0] :throw? true}
   ;;;; MIN_SAFE_INTEGER - 2
   {:form "(long -9007199254740993)", :value -9007199254740993, :bytes [-8 -1 -33 -1 -1 -1 -1 -1 -1],  :rawbytes [248 255 223 255 255 255 255 255 255] :throw? true}
   {:form "Long/MAX_VALUE", :value 9223372036854775807,  :bytes [-8 127 -1 -1 -1 -1 -1 -1 -1], :rawbytes [248 127 255 255 255 255 255 255 255] :throw? true}
   {:form "Long/MIN_VALUE", :value -9223372036854775808, :bytes [-8 -128 0 0 0 0 0 0 0],  :rawbytes [248 128   0 0 0 0 0 0 0] :throw? true}])

#_(deftest readInt-test
  (testing "readRawInt40"
    (let [{:keys [form bytes value rawbytes]} {:form "(long -549755813887)"
                                               :value -549755813887
                                               :bytes [121 -128 0 0 0 1]
                                               :rawbytes [121 128 0 0 0 1]}
          rdr (r/reader (into-bytes bytes))
           raw (:raw-in rdr)]
      (is= 121 (rawIn/readRawByte raw))
      (let [i40 (rawIn/readRawInt40 raw)]
        (is= 549755813889 i40)
        (is= 549755813889 (.toNumber (goog.math.Long.fromNumber i40))))
      (rawIn/reset (:raw-in rdr))
      (is= value (r/readInt rdr))))
  (testing "readRawInt48"
    (let [{:keys [form bytes value rawbytes]} {:form "(long 1.4073749E14)"
                                               :value 140737490000000
                                               :bytes [126 -128 0 0 25 24 -128]
                                               :rawbytes [126 128 0 0 25 24 128]}
          rdr (r/reader (into-bytes bytes))
          raw (:raw-in rdr)]
      (is= 126 (rawIn/readRawByte raw))
      (let [i48 (rawIn/readRawInt48 raw)]
        (is= i48 140737490000000  (.toNumber (goog.math.Long.fromNumber i48))))
      (rawIn/reset (:raw-in rdr))
      (is= value (r/readInt rdr))))
  (testing "readRawInt64"
    (let [{:keys [form bytes value rawbytes]} {:form "(long 9007199254740991)"
                                               :value 9007199254740991
                                               :bytes [-8 0 31 -1 -1 -1 -1 -1 -1]
                                               :rawbytes [248 0 31 255 255 255 255 255 255]}
          rdr (r/reader (into-bytes bytes))
          raw (:raw-in rdr)]
      (when rawbytes
        (is= rawbytes (rawbyteseq rdr))
        (rawIn/reset (:raw-in rdr)))
      (is= 248 (rawIn/readRawByte raw))
      (let [i64 (rawIn/readRawInt64 raw)]
        (is= i64 9007199254740991 (.toNumber (goog.math.Long.fromNumber i64))))
      (rawIn/reset (:raw-in rdr))
      (is= value (r/readInt rdr))))
  (testing "unsafe i64"
    (let [{:keys [form bytes value rawbytes]}{:form "(long 9007199254740992)"
                                              :value 9007199254740992
                                              :bytes [-8 0 32 0 0 0 0 0 0]
                                              :rawbytes [248 0 32 0 0 0 0 0 0]}
          rdr (r/reader (into-bytes bytes))]
      (is= 248 (rawIn/readRawByte (:raw-in rdr)))
      (binding [rawIn/*throw-on-unsafe?* true]
        (is (thrown? js/Error (rawIn/readRawInt64 (:raw-in rdr)))))))
  (testing "int-samples"
    (doseq [{:keys [form bytes value rawbytes throw?]} int-samples]
      (testing form
        (let [rdr (r/reader (into-bytes bytes))]
          (is= rawbytes (rawbyteseq rdr))
          (rawIn/reset (:raw-in rdr))
          (if throw?
            (is (thrown? js/Error (r/readInt rdr)))
            (is= value (r/readInt rdr))))))))

(def float-samples
  [{:form "Float/MIN_VALUE", :value 1.4E-45, :bytes [-7 0 0 0 1], :rawbytes [249 0 0 0 1]}
   {:form "Float/MAX_VALUE", :value 3.4028235E38, :bytes [-7 127 127 -1 -1], :rawbytes [249 127 127 255 255]}])

#_(deftest read-floats-test
  (testing "Float/MAX_VALUE"
    (let [{:keys [form bytes value rawbytes throw?]} {:form "Float/MAX_VALUE",
                                                      :value 3.4028235E38,
                                                      :bytes [-7 127 127 -1 -1],
                                                      :rawbytes [249 127 127 255 255]}
          rdr (r/reader (into-bytes bytes))
          raw (:raw-in rdr)]
      (is= rawbytes (rawbyteseq rdr))
      (rawIn/reset raw)
      (is= 249 (rawIn/readRawByte raw))
      (is (precision= value (rawIn/readRawFloat raw) 8))
      (rawIn/reset raw)
      (is (precision= value (r/readFloat rdr) 8))))
  (testing "readFloat"
    (doseq [{:keys [form bytes value rawbytes throw?]} float-samples]
      (testing form
        (let [rdr (r/reader (into-bytes bytes))
              raw (:raw-in rdr)]
          (is= rawbytes (rawbyteseq rdr))
          (rawIn/reset raw)
          (is (kinda= value (r/readFloat rdr))))))))


(def double-samples
  [{:form "Double/MIN_VALUE", :value 4.9E-324, :bytes [-6 0 0 0 0 0 0 0 1], :rawbytes [250 0 0 0 0 0 0 0 1]}
   {:form "Double/MAX_VALUE", :value 1.7976931348623157E308, :bytes [-6 127 -17 -1 -1 -1 -1 -1 -1], :rawbytes [250 127 239 255 255 255 255 255 255]}])


#_(deftest read-double-test
  (testing "Double/MAX_VALUE"
    (let [{:keys [form bytes value rawbytes throw?]} {:form "Double/MAX_VALUE",
                                                      :value 1.7976931348623157E308
                                                      :bytes [-6 127 -17 -1 -1 -1 -1 -1 -1]
                                                      :rawbytes [250 127 239 255 255 255 255 255 255]}
          rdr (r/reader (into-bytes bytes))
          raw (:raw-in rdr)]
      (is= rawbytes (rawbyteseq rdr))
      (rawIn/reset raw)
      (is (precision= value (r/readDouble rdr) 16))))
  (testing "double-samples"
    (doseq [{:keys [form bytes value rawbytes throw?]} double-samples]
      (testing form
        (let [rdr (r/reader (into-bytes bytes))
              raw (:raw-in rdr)]
          (is= rawbytes (rawbyteseq rdr))
          (rawIn/reset raw)
          (is (kinda= value (r/readDouble rdr))))))))

(def misc-samples
  [{:form "[1 2 3]", :value [1 2 3], :bytes [-25 1 2 3], :rawbytes [231 1 2 3]}
   {:form "[true false [nil]]", :value [true false [nil]], :bytes [-25 -11 -10 -27 -9], :rawbytes [231 245 246 229 247]}

   ])

#_(deftest misc-types
  (doseq [{:keys [form bytes value rawbytes throw?]} misc-samples]
    (let [rdr (r/reader (into-bytes bytes))
          raw (:raw-in rdr)]
      (is= rawbytes (rawbyteseq rdr))
      (rawIn/reset raw)
      (is= (read-string form) (r/readObject rdr)))))

; boolean, string
;;int[] , long [], float[], double[], boolean[]
; list, openlist, closedlist
; structs
; footers, caching,, EOF
