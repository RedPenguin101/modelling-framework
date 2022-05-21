(ns fmwk.framework2
  (:require [clojure.walk :refer [postwalk]]
            [clojure.spec.alpha :as spec]
            [ubergraph.core :as uber]
            [ubergraph.alg :as uberalg]))

;; utils
;;;;;;;;;;;;;;

(defn map-vals [f m] (update-vals m f))

(defn qualify [qualifier-str kw]
  (if (or (= :placeholder kw) (qualified-keyword? kw)) kw
      (keyword qualifier-str (name kw))))

(defn- expand
  "Where the value in a kv pair is a sequence, this function
   will 'unpack' the value. So 
     [:a [:b :c :d]]
   will become
     [[:a :b] [:a :c] [:a :d]]"
  [[k vs]] (map #(vector k %) vs))

(comment
  (qualify "hello.world" :foo)
  (qualify "hello.world" :other.ns/foo))

;; References and calculation expressions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(spec/def :framework.reference/current (spec/tuple keyword?))
(spec/def :framework.reference/previous (spec/tuple keyword? #(= :prev %)))
(spec/def :framework.reference/placeholder (spec/tuple #(= :placeholder %) any?))

(defn placeholder-ref? [ref] (= :placeholder (first ref)))
(defn previous-period-ref? [ref] (= :prev (second ref)))
(defn current-period-ref? [ref] (= 1 (count ref)))
(defn constant? [ref] (#{:placeholder :constant} (first ref)))

(spec/valid? :framework.reference/current [:hello])
(spec/valid? :framework.reference/previous [:hello :prev])
(spec/valid? :framework.reference/placeholder [:placeholder 6])

(defn extract-refs
  "Given an expression containing references (defined as a vector), 
   will extract all of the references and return them as a vector"
  ([expr] (if (coll? expr)
            (extract-refs [] expr)
            (throw (ex-info "extract-refs: expression is a constant" {:expr expr}))))
  ([found [fst & rst :as expr]]
   (cond (vector? expr) (conj found expr)
         (nil? fst) found
         (vector? fst) (recur (conj found fst) rst)
         (coll? fst) (recur (into found (extract-refs [] fst)) rst)
         :else (recur found rst))))

(defn replace-local-references [qualifier expr]
  (postwalk #(if (vector? %) (update % 0 (partial qualify qualifier)) %)
            expr))

(defn replace-refs-in-expr [expr replacements]
  (postwalk #(cond (and (vector? %) (= :placeholder (first %))) (second %)
                   (vector? %) (replacements (first %))
                   :else %)
            expr))

;; Row and calculation helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn inputs->rows [inputs]
  (update-vals inputs #(conj [:constant] (:value %))))

;; calculations helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Calc validations
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expression-is-atomic? [expr] (not (coll? expr)))

(defn multiple-placeholders-in-expr? [expr]
  (> (count (filter #{:placeholder} (map first (extract-refs expr))))
     1))

(expression-is-atomic? '(+ [:placeholder 10] [:placeholder 10]))
(expression-is-atomic? 10)
(multiple-placeholders-in-expr? '(+ [:placeholder 10] [:placeholder 10]))

;; model helpers
;;;;;;;;;;;;;;;;;;;;;


;; Model building
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Model dependecies
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rows->graph [rows]
  (->> (map-vals extract-refs rows)
       (map-vals #(filter current-period-ref? %))
       (map-vals #(map first %))
       (mapcat expand)
       (uber/add-directed-edges* (uber/digraph))))

(defn calculate-order [rows]
  (->> (rows->graph rows)
       (uberalg/topsort)
       reverse))

;; model validations
;;;;;;;;;;;;;;;;;;;;;

(defn circular? [rows] (not (uberalg/dag? (rows->graph rows))))

;; Model running
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn zero-period
  [rows]
  (update-vals rows #(if (constant? %) (second %) 0)))

(defn resolve-reference [ref this-record [previous-record]]
  (cond (constant? ref)            (second ref)
        (previous-period-ref? ref) (get previous-record (first ref))
        (current-period-ref? ref)  (get this-record (first ref))))

(defn next-period [prv-recs rows calc-order]
  (reduce (fn [record row-name]
            (let [refs (extract-refs (row-name rows))]
              (->> (map #(resolve-reference % record prv-recs) refs)
                   (zipmap (map first refs))
                   (replace-refs-in-expr (row-name rows))
                   eval
                   (assoc record row-name))))
          {}
          calc-order))

;; Table printing and model selection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
