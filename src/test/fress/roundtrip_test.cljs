(ns fress.roundtrip-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.tools.reader :refer [read-string]]
            [fress.impl.raw-input :as rawIn]
            [fress.impl.codes :as codes]
            [fress.impl.ranges :as ranges]
            [fress.reader :as r]
            [fress.writer :as w]
            [fress.samples :as samples]
            [fress.util :refer [byte-array] :as util]
            [fress.test-helpers :as helpers :refer [log is= seq= are-nums= float=]]))

#_(deftest int-test
  (doseq [{:keys [form bytes value throw?]} samples/int-samples]
    (testing form
      (let [out (byte-array (count bytes))
            wrt (w/writer out)
            rdr (r/reader out)]
        (if-not throw?
          (do
            (testing (str "writing " form)
              (w/writeObject wrt value)
              (are-nums= bytes out))
            (testing (str "reading " form)
              (is= value (r/readObject rdr))))
          (testing "unsafe ints"
            (testing "writing unsafe int"
              (is (thrown? js/Error (w/writeObject wrt value))))
            (testing "reading unsafe int"
              (is (thrown? js/Error (r/readObject (r/reader (byte-array bytes))))))))))))

#_(deftest float-test
  (doseq [{:keys [form bytes value throw?]} (concat samples/float-samples samples/double-samples)]
    (testing form
      (let [out (byte-array (count bytes))
            wrt (w/writer out)
            rdr (r/reader out)]
        (w/writeObject wrt value)
        (are-nums= bytes out)
        (is (float= value (r/readObject rdr)))))))

#_(deftest rawUTF8-test
  (doseq [{:keys [form bytes value tag?]} samples/utf8-samples]
    (testing form
      (let [out (byte-array (count bytes))
            wrt (w/writer out)
            rdr (r/reader out)]
        (binding [w/*write-raw-utf8* true
                  w/*write-utf8-tag* tag?]
          (w/writeObject wrt value))
        (are-nums= bytes out)
        (is value (r/readObject rdr))))))

#_(deftest misc-roundtrip
  (doseq [{:keys [form bytes value]} samples/misc-samples]
    (testing form
      (let [out (byte-array (count bytes))
            wrt (w/writer out)
            rdr (r/reader out)]
        (w/writeObject wrt value)
        (are-nums= bytes out)
        (is= value (r/readObject rdr))))))

#_(deftest uri-roundtrip
  (doseq [{:keys [form bytes input]} samples/uri-samples]
    (testing form
      (let [out (byte-array (count bytes))
            wrt (w/writer out)
            rdr (r/reader out)
            value (goog.Uri. input)]
        (w/writeObject wrt value)
        (are-nums= bytes out)
        (is= (.toString value) (.toString (r/readObject rdr)))))))


(def typed-array-sym->writer
  {'long-array   w/writeLongArray
   'object-array w/writeObjectArray
   'boolean-array w/writeBooleanArray})

#_(deftest typed-array-test
  (doseq [{:keys [form bytes value input byte-count]} samples/typed-array-samples]
    (testing form
      (let [out (byte-array (or byte-count (count bytes)))
            wrt (w/writer out)
            rdr (r/reader out)
            sym (first (read-string form))
            value ((helpers/typed-array-sym->fn sym) input)]
        (if-let [bypass-writer (typed-array-sym->writer sym)]
          (bypass-writer wrt value)
          (w/writeObject wrt value))
        (are-nums= bytes out)
        (is (seq= value (r/readObject rdr)))))))

#_(deftest footer-roundtrip
  (doseq [{:keys [form bytes value input]} samples/footer-samples]
    (testing form
      (let [out (byte-array (count bytes))
            wrt (w/writer out)
            rdr (r/reader out)
            value (or value (byte-array input))]
        (w/writeObject wrt value)
        (w/writeFooter wrt)
        (are-nums= bytes out)
        (is (seq= value (r/readObject rdr)))
        (is (nil? (r/validateFooter rdr)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype Person [firstName lastName]
  IEquiv
  (-equiv [this that]
    (and (= (type this) (type that))
         (= firstName (.-firstName that))
         (= lastName (.-lastName that)))))

(defn writePerson
  [wrt person]
  (w/writeTag wrt "org.fressian.Examples.Person" 2)
  (w/writeObject wrt (.-firstName person))
  (w/writeObject wrt (.-lastName person)))

(defn readPerson [rdr tag fields]
  (Person. (r/readObject rdr) (r/readObject rdr)))

(deftest struct+cache-test
  (let [bytes [-17 -29 28 111 114 103 46 102 114 101 115 115 105 97 110 46 69 120 97 109 112 108 101 115 46 80 101 114 115 111 110 2 -33 106 111 110 110 121 -29 9 103 114 101 101 110 119 111 111 100 -96 -34 116 104 111 109 -33 121 111 114 107 101]
        tag "org.fressian.Examples.Person"
        out (byte-array (count bytes))
        wrt (w/writer out :handlers {Person writePerson})
        jonny (->Person "jonny" "greenwood")
        thom (->Person "thom" "yorke")]
    (w/writeObject wrt jonny) ;<= triggers struct caching
    (w/writeObject wrt thom)
    (are-nums= bytes out)
    (testing "read by component"
      (let [rdr (r/reader out)]
        (is= (r/readNextCode rdr) codes/STRUCTTYPE)
        (is= (r/readObject rdr) tag)
        (is= (r/readObject rdr) 2)
        (is= (r/readObject rdr) "jonny")
        (is= (r/readObject rdr) "greenwood")
        ;; writer has now cached the object..., reader keeps record
        ;; at cache index 0
        (is= (r/readNextCode rdr) (+ codes/STRUCT_CACHE_PACKED_START 0))
        (is= (r/readObject rdr) "thom")
        (is= (r/readObject rdr) "yorke")))
    (testing "without handler => tagged-object"
      (let [rdr (r/reader out)
            o1 (r/readObject rdr)
            o2 (r/readObject rdr)]
        (is (and (instance? r/TaggedObject o1)
                 (= (.-tag o1) tag)
                 (= (vec (.-value o1)) ["jonny" "greenwood"])))
        (is (and (instance? r/TaggedObject o2)
                 (= (.-tag o2) tag)
                 (= (vec (.-value o2)) ["thom" "yorke"])))))
    (testing "with handler"
      (let [rdr (r/reader out :handlers {tag readPerson})]
        (is= (r/readObject rdr) jonny)
        (is= (r/readObject rdr) thom)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; records

(defrecord Book [author title])

(deftest record-test
  (let [{:keys [bytes author title class-sym]} samples/record-sample
        out (byte-array (count bytes))
        wrt (w/writer out)
        value (Book. author title)]
    (binding [w/*record->name* {Book "fress.api.Book"}]
      (w/writeObject wrt value)
      (are-nums= bytes out))
    (testing "no bound reader ... => tagged-object"
      (let [rdr (r/reader out)
            o (r/readObject rdr)]
        (is (instance? r/TaggedObject o))
        (is= (.-tag o) "record")
        (let [v (.-value o)]
          (is (aget v 0) 'fress.api.Book)
          (is (js->clj (aget v 1)) {:author author :title title}))))
    (testing "binding defrecord ctor  => record instance"
      (binding [r/*record-name->map-ctor* {"fress.api.Book" map->Book}]
        (let [rdr (r/reader out)
              o (r/readObject rdr)]
          (is (instance? Book o))
          (is= o (Book. author title)))))))






;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; caching
; #_(deftest cached-test
;   (let [{:keys [value bytes]} samples/cached-sample
;         out (byte-array (count bytes))
;         wrt (w/writer out)]
;     (w/writeObject wrt value true)
;     (w/writeObject wrt value true)
;     (are-nums= bytes out)))
;
; #_(deftest read-cached-test
;   (let [{:keys [bytes value]} samples/cached-sample
;         rdr (r/reader (byte-array bytes))
;         raw (:raw-in rdr)]
;     (testing "by component"
;       (is= (r/readNextCode rdr) codes/PUT_PRIORITY_CACHE)
;       (is= (r/readObject rdr) value)
;       (is= (r/readNextCode rdr) (+ codes/PRIORITY_CACHE_PACKED_START 0))
;       (is (thrown-with-msg? js/Error #"EOF" (r/readObject rdr))))
;     (rawIn/reset raw)
;     (r/resetCaches rdr)
;     (testing "normal use"
;       (is= (r/readObject rdr) value)
;       (is= (r/readObject rdr) value))))