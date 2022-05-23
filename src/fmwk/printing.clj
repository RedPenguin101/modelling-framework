(ns fmwk.printing
  (:require [fmwk.tables :as tables]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(defn select-keys-with-qualifier [qualifier ks]
  ((group-by namespace ks) qualifier))

(defn de-qualify [k] (vec (str/split (namespace k) #"\.")))
(defn sheet [k] (first (de-qualify k)))
(defn calc [k] (second (de-qualify k)))
(defn sub-calc [k] (nth (de-qualify k) 2))
(def row name)

(comment
  (filter #(= (sheet %) "fs") (keys fmwk.gridlines.model/model))
  (de-qualify :sheet.calc.subcalc/hello)
  (sheet :sheet.calc.subcalc/hello)
  (calc :sheet.calc.subcalc/hello)
  (sub-calc :sheet.calc.subcalc/hello)
  (row :sheet.calc.subcalc/hello)
  (sheet :sheet/hello)
  (calc :sheet/hello))

(defn rows-in-sheet [row-names sheet-name]
  (filter #(= (sheet %) sheet-name) row-names))

(defn rows-in-group [row-names group-name]
  (let [quals (str/split group-name #"\.")
        level (count quals)]
    (filter #(= quals (take level (de-qualify %))) row-names)))

(comment
  (rows-in-group (keys fmwk.gridlines.model/model) "fs.balance-sheet.assets"))

(defn round [x] (if (int? x) x (Math/round x)))

(defn round-results [results]
  (let [series (fmwk.tables/records->series results)]
    (tables/series->records (update-vals series #(if (number? (second %)) (mapv round %) %)))))

(defn slice-period [group-name period]
  (select-keys period (rows-in-group (keys period) group-name)))

(rows-in-sheet (keys (first fmwk.gridlines.model/results)) "fs")


(defn print-results [results [start end]]
  (pp/print-table (into [:name] (range start (inc end)))
                  (sort-by (comp namespace :name) (tables/transpose-records (round-results results)))))

(defn slice-results [results sheet-name period-range]
  (print-results (map #(slice-period sheet-name %)
                      results) period-range))

(print-results (map #(slice-period "fs.balance-sheet" %)
                    (take 10 fmwk.gridlines.model/results))
               [1 10])


(comment
  (require '[fmwk-test.test-forest-model :as mtest]
           '[fmwk.utils :refer :all])
  (def model mtest/model)
  (def results (time (fmmk.framework2/run-model model 10)))
  (second results)

  (round-results results)

  (let [series (fmwk.tables/records->series results)]
    (fmwk.tables/series->records (update-vals series #(if (number? (second %)) (map round %) %))))

  (select-keys (first results)
               (select-keys-with-qualifier "flags" (keys model)))

  (doall [(slice-results results "prices" [1 5])
          (slice-results results "time" [1 5])
          (slice-results results "cashflows" [1 5])])

  (slice-results results "capital.closing" [1 5])
  (slice-results results "debt.debt-balance" [1 5])

  (map :cashflows/net-cashflow results)

  (tables/records->series results))
