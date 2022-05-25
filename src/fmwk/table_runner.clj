(ns fmwk.table-runner
  (:require [clojure.walk :refer [postwalk]]))

;; predicates for types of expression, for conditionals
(def atomic? (complement coll?))
(def expression? list?)
(defn constant-ref? [ref] (and (vector? ref) (#{:placeholder :constant} (first ref))))
(def link? (every-pred vector? (complement constant-ref?)))
(defn current-period-link? [ref] (and (link? ref) (= 1 (count ref))))
(defn previous-period-link? [ref] (and (link? ref) (= :prev (second ref))))


;; Rewriting expressions as array lookups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rewrite-ref-as-get [reference row-num]
  (cond (current-period-link? reference)
        (list 'aget 'rows row-num 'period)
        (previous-period-link? reference)
        (list 'aget 'rows row-num '(dec period))
        (constant-ref? reference)
        (second reference)))


(defn rewrite-expression [row-name expr row->num]
  (conj (list 'aset 'rows (get row->num row-name) 'period
              (postwalk #(cond (vector? %) (rewrite-ref-as-get % (get row->num (first %)))
                               :else %)
                        expr))))

(comment
  (rewrite-ref-as-get [:model-column-number] 2)
  (rewrite-ref-as-get [:model-column-number :prev] 2)
  (rewrite-ref-as-get [:placeholder 10] 2)
  (rewrite-ref-as-get [:constant 10] 2)

  (rewrite-expression
   :hello-world
   '(Math/pow [:inputs/inflation-rate]
              (dec [:model-column-number]))
   {:inputs/inflation-rate 5
    :model-column-number  10
    :hello-world          15})

  (rewrite-expression
   :hello-world
   [:foo-bar]
   {:hello-world   15
    :foo-bar       42})

  (rewrite-expression
   :a
   [:constant 7]
   {:a   15}))


(defn make-runner [ordered-rows model]
  (let [row->num (into {} (map-indexed #(vector %2 %1) ordered-rows))
        expressions (map #(rewrite-expression % (model %) row->num) ordered-rows)]
    (list 'fn '[rows row-names periods]
          (into expressions '([period (range 1 periods)] doseq))
          '(zipmap row-names (map vec rows)))))

(defn run-model-table [ordered-rows model periods]
  (let [array (to-array-2d (vec (repeat (count ordered-rows)
                                        (vec (repeat periods 0)))))]
    ((eval (make-runner ordered-rows model))
     array ordered-rows periods)))

(comment
  (run-model-table [:a :b :c] {:a [:constant 4]
                               :b [:a :prev]
                               :c '(inc [:b])}
                   10)

  (require '[fmwk.utils :refer :all]
           '[fmwk-test.test-forest-model :as m]
           '[fmwk.gridlines.model :as m2]
           '[fmwk.framework :as fw])
  (def model m/model)
  (def order (fw/calculate-order model))
  (time (:cashflows/net-cashflow (run-model-table order model 25)))

  (def model2 m2/model)
  (def order2 (fw/calculate-order model2))
  (time (:fs.cashflow/dividends-paid (run-model-table order2 model2 183))))