(ns fmwk.tables)

(comment
  ;; Collection manipulation
  ;; This NS contains functions for transforming between
  ;; several common formats of tabular data

  ;; GENERIC STRUCTURES:
  ;; Just ways to store data. No implied meaning to the
  ;; data
  ;; Record: A sequence of maps with consistent keys
  [{:a 1 :b 2 :c 3}
   {:a 4 :b 5 :c 6}
   {:a 7 :b 8 :c 9}]
  ;; Table: A 2d array, or vector of vectors. Can optionally
  ;; include headers as the first element. Inner elements are
  ;; rows
  [[:a :b :c]
   [1  2  3]
   [4  5  6]
   [7  8  9]]
  ;; Series: A map, where each value is a sequence
  {:a [1 4 7],
   :b [2 5 8],
   :c [3 6 9]}
  ;; Notice that these all contain the same information,
  ;; just in different formats

  ;; SEMANTIC STRUCTURES
  ;; Observation: An observation is a snapshot perception, 
  ;; with the data structure capturing everything that is 
  ;; perceived in that snapshot
  {:a 1 :b 2 :c 3} ;; as record
  [1  2  3]        ;; as table row

  ;; Time series: for a single attribute, values that that
  ;; attribute takes over time
  {:a [1 4 7]} ;; as series
  {:name :a :period1 1 :period2 4 :period3 7} ;; as record
  [:a 1 4 7] ;; as table row 

  ;; Different formats are appropriate for different situations
  ;; however series is most natural for time series,
  ;; record and table are most natural for observations
  ;; Series is not really doable for observations,
  ;; and records are rather inelegant for TS
  )

[{:name :a :period1 1 :period2 4 :period3 7}
 {:name :b :period1 2 :period2 5 :period3 8}
 {:name :c :period1 3 :period2 6 :period3 9}]

;; v from to > | records | series | table
;; records     |         |    Y   |  CW
;; series      |    Y    |        |  RW
;; table       |    CW   |   RW   | 

(defn transpose-table [table] (apply map vector table))

(defn series->row-wise-table [series]
  (for [[k vs] series] (into [k] vs)))

(defn row-wise-table->series [table]
  (into {} (for [[h & vs] table]
             [h (vec vs)])))

(defn records->column-wise-table
  ([records] (records->column-wise-table
              records
              (vec (keys (apply merge records)))))
  ([records order]
   (into [order] (mapv #(mapv % order) records))))

(defn col-wise-table->records [[hdrs & data]]
  (map #(zipmap hdrs %) data))

(def records->series (comp row-wise-table->series
                           transpose-table
                           records->column-wise-table))

(def series->records (comp col-wise-table->records
                           transpose-table
                           series->row-wise-table))

(transpose-table [[:a 1 4 7] [:b 2 5 8] [:c 3 6 9]])
(records->series [{:a 1 :b 2 :c 3}
                  {:a 4 :b 5 :c 6}
                  {:a 7 :b 8 :c 9}])
(series->records {:a [1 4 7], :b [2 5 8], :c [3 6 9]})

(defn transpose-records
  ([records] (transpose-records records (keys (apply merge records))))
  ([records order]
   (let [num-rec (count records)]
     (->> (records->column-wise-table records order)
          transpose-table
          (into [(vec (conj (range 0 num-rec) :name))])
          (col-wise-table->records)))))

(transpose-records [{:a 1 :b 2 :c 3}
                    {:a 4 :b 5 :c 6}
                    {:a 7 :b 8 :c 9}])

(comment
  (require '[clojure.pprint :as pp])
  (pp/print-table [{:a 1 :b 2 :c 3}
                   {:a 4 :b 5 :c 6}
                   {:a 7 :b 8 :c 9}])
  (pp/print-table (transpose-records [{:a 1 :b 2 :c 3}
                                      {:a 4 :b 5 :c 6}
                                      {:a 7 :b 8 :c 9}])))