(ns fress.test-helpers
  (:require-macros [cljs.core.async.macros :refer [go-loop go]])
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [cljs.core.async :as casync :refer [close! put! take! alts! <! >! chan promise-chan]]
            [cljs.core.async.impl.protocols :as impl]
            [cljs.core.match :refer [match]]
            [clojure.string :as string]
            [cljs-node-io.proc :as proc]
            [cljs-node-io.async :as nasync]
            [cljs-node-io.core :as io :refer [slurp spit]]
            [cljs-node-io.fs :as fs]
            [cljs.tools.reader :refer [read-string]]))

(defn log [& args] (.apply js/console.log js/console (into-array args)))

(defn is=
  ([a b] (is (= a b)))
  ([a b c] (is (= a b c)))
  ([a b c d] (is (= a b c d))))

(let [arr (js/Int8Array. 1)]
  (defn overflow [n]
    (aset arr 0 n)
    (aget arr 0)))

(extend-type js/Int8Array
  IIndexed
  (-nth
   ([arr n]
    (if (and (<= 0 n) (< n (.-length arr)))
      (aget arr n)
      (throw  (js/Error. "Index out of bounds"))))
   ([arr n not-found]
    (if (and (<= 0 n) (< n (.-length arr)))
      (aget arr n)
      not-found))))

(defn byteseq [wrt]
  (-> (js/Int8Array. (.. wrt -raw-out -memory -buffer) 0 (.. wrt -raw-out -bytesWritten))
    array-seq))



(def ^:dynamic *conn*)
(def *last-write* nil)
(defonce conn-out (atom nil))

(defn parse-result [result]
  (let [lines (string/split-lines result)
        result (let [r (butlast lines)]
                 ; (assert (= 1 (count r)))
                 (read-string (first lines)))
        ; [err res] (if (string/includes resukt))
        prompt (last lines)]
    [result prompt]))

(defn tap-conn []
  (if (and @*conn* (not @conn-out))
    (let [c (chan (casync/sliding-buffer 1))] ; (casync/sliding-buffer 1)
      (casync/tap @*conn* c)
      (reset! conn-out c))
    false))

(defn cycle-tap []
  (do
    (reset! conn-out nil)
    (tap-conn)))

(defn cycle-conn []
  (notebook.core.repl/kill-conn)
  (notebook.core.repl/open-conn)
  (cycle-tap))

(defn ^boolean valid-form? [_str]
  (try (read-string _str) true
    (catch js/Error e false)))

(defn write [_str]
  (when @*conn*
    (assert (valid-form? _str) "invalid form") ;lets extra brackets through :-(
    (set! *last-write* _str)
    (.write @*conn* (str _str "\n"))))

(defn write-form [form]
  (when @*conn*
    (let [_str  (str "(bufseq "  form ")")]
      (write _str))))

(defn setup-repl []
  (cycle-tap)
  (let [out (promise-chan)]
    (take! (write "(use 'notebook.fress)")
     (fn [[write-err]]
       (if write-err
         (do
           (log "write-err" write-err)
           (put! out [write-err]))
         (take! @conn-out
           (fn [res]
             (log "setup res:" res))))))
    out))

(defn jvm-byteseq [form] ;=> chan<[?err ?res]>
  ; (cycle-tap)
  (let [out (promise-chan)]
    (take! (write-form form)
     (fn [[write-err]]
       (if write-err
         (put! out [write-err])
         (do
           (take! @conn-out
             (fn [{:keys [tag val ns ms form] :as res}]
               (if (= tag :ret)
                 (let [v (read-string val)]
                   (put! out [nil v]))
                 (put! out [val]))))))))
    out))