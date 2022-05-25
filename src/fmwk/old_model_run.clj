(ns fmwk.old-model-run
  (:require [clojure.walk :refer [postwalk]]
            [fmwk.framework2 :refer [extract-refs calculate-order]]))

;; predicates for types of expression, for conditionals
(def atomic? (complement coll?))
(def expression? list?)
(defn constant-ref? [ref] (and (vector? ref) (#{:placeholder :constant} (first ref))))
(def link? (every-pred vector? (complement constant-ref?)))
(defn current-period-link? [ref] (and (link? ref) (= 1 (count ref))))
(defn previous-period-link? [ref] (and (link? ref) (= :prev (second ref))))

(defn resolve-reference [ref this-record [previous-record]]
  (cond (constant-ref? ref)        (second ref)
        (previous-period-link? ref) (get previous-record (first ref))
        (current-period-link? ref)  (get this-record (first ref))))

(defn replace-refs-in-expr [expr replacements]
  (postwalk #(cond (constant-ref? %) (second %)
                   (link? %) (replacements (first %))
                   :else %)
            expr))

(defn zero-period
  [rows]
  (update-vals rows #(if (constant-ref? %) (second %) 0)))

(defn next-period [prv-recs rows calc-order]
  (reduce (fn [record row-name]
            (let [refs (extract-refs (row-name rows))]
              (try
                (->> (map #(resolve-reference % record prv-recs) refs)
                     (zipmap (map first refs))
                     (replace-refs-in-expr (row-name rows))
                     eval
                     (assoc record row-name))
                (catch Exception _e
                  (throw (ex-info (str "Error calculating " row-name)
                                  {:name row-name
                                   :calc (row-name rows)
                                   :replaced-calc (replace-refs-in-expr (row-name rows) (zipmap (map first refs) (map #(resolve-reference % record prv-recs) refs)))}))))))
          {}
          calc-order))

(defn roll-model [prvs rows order] (conj prvs (next-period prvs rows order)))

(defn run-model [rows periods]
  (reverse
   (let [order (calculate-order rows)]
     (loop [records (list (zero-period rows))
            prd periods]
       (if (zero? prd)
         records
         (recur (roll-model records rows order) (dec prd)))))))
