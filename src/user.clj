(ns user
  (:require [fmwk.framework :as fw]))


(let [row-names [:model-period-number :first-period-flag
                 :compound-inflation :sale-price]
      num-periods (inc 10)
      num-rows 5
      rows (to-array-2d (repeat num-rows (repeat num-periods 0)))]
  (doseq [period (range 1 num-periods)]
    ;; 0 = model col num
    (aset rows 0 period
          (inc (aget rows 0 (dec period))))
    ;; 1 = first preiod flag
    (aset rows 1 period
          (if (= 1 (aget rows 0 period)) 1 0))
    ;; 2 = compound inflation
    (aset rows 2 period
          (Math/pow 1.02 (dec (aget rows 0 period))))
    ;; 3 = sale-price
    (aset rows 3 period (* (aget rows 2 period) 50)))
  (zipmap row-names (map vec rows)))

(defn rewrite [expr row-num ref-repls]
  (list 'aset 'rows row-num 'period
        (fw/replace-refs-in-expr expr ref-repls)))

(rewrite '(Math/pow [:inputs/inflation-rate]
                    (dec [:model-column-number]))
         2
         {:inputs/inflation-rate 1.02
          :model-column-number '(aget rows 0 period)})

(rewrite '(inc [:model-column-number :prev])
         2
         {:model-column-number '(aget rows 0 (dec period))})

(defn array-format [calc-order ordered-exprs periods replacements]
  (let [new-exprs (for [r (range 0 (count calc-order))]
                    (rewrite (nth ordered-exprs r) r replacements))
        rows (list 'to-array-2d (vec (repeat (count calc-order) (vec (repeat periods 0)))))]
    (list 'let ['row-names calc-order
                'num-periods periods
                'num-rows (count calc-order)
                'rows rows]
          (into new-exprs '([period (range 1 num-periods)] doseq))
          '(zipmap row-names (map vec rows)))))

(eval (array-format [:period :inflation]
                    ['(inc [:prev-period])
                     '(Math/pow [:inputs/inflation-rate]
                                (dec [:model-column-number]))]
                    10
                    {:inputs/inflation-rate 1.02
                     :prev-period '(aget rows 0 (dec period))
                     :model-column-number '(aget rows 0 period)}))
