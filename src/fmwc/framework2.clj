(ns fmwc.framework2
  (:require [ubergraph.core :as uber]
            [ubergraph.alg :as alg]
            [clojure.set :as set]
            [clojure.walk :as walk]))
;; Util fns
;;;;;;;;;;;;;;;;

(defn make-flag [x] (if x 1 0))
(defn equal-to? [x] (fn [val] (if (= x val) 1 0)))
(defn flagged? [x] (not (zero? x)))
(defn sum [xs] (apply + xs))
(defn product [xs] (apply * xs))
(defn year-of [date] (first date))
(defn end-of-month [date months] date)

(defn add-keys-as-name [m]
  (into {} (map (fn [[k v]] [k (assoc v :name k)]) m)))

(defn extract-deps [found [fst & rst]]
  (cond (nil? fst) found
        (vector? fst) (recur (conj found fst) rst)
        (coll? fst) (recur (into found (extract-deps [] fst)) rst)
        :else (recur found rst)))

(defn get-edges [row-name row]
  (->> (:calculator row)
       (extract-deps [])
       (map #(if (= :self (second %)) [(first %) row-name] %))
       (map #(vector row-name (second %) {:relationship (first %)}))))

(defn get-edges-no-self-ref [row-name row]
  (->> (:calculator row)
       (extract-deps [])
       (remove #(= :self (second %)))
       (map #(vector row-name (second %) {:relationship (first %)}))))

(defn dependency-graph [model]
  (uber/add-directed-edges*
   (uber/digraph)
   (mapcat #(apply get-edges-no-self-ref %) model)))

(defn check-model-deps [model]
  (set/difference (set (uber/nodes (dependency-graph model)))
                  (set (keys model))))

(defn str-to-date [date-str] (java.time.LocalDate/parse date-str))
(defn date-to-str [date] (.toString date))

(defn date-comp [a b] (.compareTo (str-to-date a) (str-to-date b)))
(defn date< [a b] (neg? (date-comp a b)))
(defn date= [a b] (zero? (date-comp a b)))
(defn date> [a b] (pos? (date-comp a b)))
(defn date<= [a b] (or (date< a b) (date= a b)))
(defn date>= [a b] (or (date> a b) (date= a b)))
(defn month-of [d] (.getMonthValue (str-to-date d)))

(defn add-months [date months]
  (when date
    (date-to-str (.plusMonths (str-to-date date) months))))



;; Inputs
;;;;;;;;;;;;;;;;

(def inputs
  #:inputs
   {:aquisition-date            {:units "date"   :starter "2021-03-31"}
    :first-date-of-time-rulers  {:units "date"   :starter "2020-07-31"}
    :annual-year-end-date-of-first-operating-period {:units "date" :starter "2020-07-31"}

    :operating-years-remaining  {:units "years"  :starter 25}
    :length-of-operating-period {:units "months" :starter 3}
    :periods-in-year            {:units ""       :starter 4}

    :annual-degradation         {:units "percent" :starter 0.005}
    :year-1-p50-yield           {:units "KWh" :starter 250}
    :power-tariff               {:units "$/KWh" :starter 0.065}
    :yields                     {:units "percent" :starter [0.33 0.36 0.155 0.155]}
    :availability               {:units "percent" :starter 0.97}})


;; TIME
;;;;;;;;;;;;;;;;

(def model-column-counter
  {:units "counter"
   :starter 0
   :calculator  '(inc [:prev :self])})

(def first-model-column-flag
  {:units "flag"
   :calculator '((equal-to? 1) [:this :time/model-column-counter])})

(def model-period-beginning
  {:units "date"
   :calculator
   '(if (flagged? [:this :time/first-model-column-flag])
      [:const :inputs/first-date-of-time-rulers]
      (add-months [:prev :self] [:const :inputs/length-of-operating-period]))})

(def model-period-ending
  {:units "date"
   :calculator '(add-months [:this :time/model-period-beginning]
                            [:const :inputs/length-of-operating-period])})

(def end-of-contract-year-flag
  {:units "flag"
   :starter 0
   :calculator '(make-flag (= (month-of [:const :inputs/aquisition-date])
                              (month-of [:this :time/model-period-ending])))})

(def end-of-operating-period
  {:units "date"
   :calculator '(end-of-month [:const :inputs/aquisition-date]
                              (* 12 [:const :inputs/operating-years-remaining]))})

(def operating-period-flag
  {:units "flag"
   :calculator '(make-flag (and (date> [:this  :time/model-period-ending]
                                       [:const :inputs/aquisition-date])
                                (date<= [:this  :time/model-period-ending]
                                        [:this :time/end-of-operating-period])))})

(def period-number
  {:units "counter"
   :starter 0
   :calculator  '(* (if (= [:prev :self] [:const :inputs/periods-in-year]) 1
                        (inc [:prev :self]))
                    [:this :time/operating-period-flag])})

(def contract-year-number
  {:units "counter"
   :starter 0
   :calculator '(f* (f+ [:prev :self] [:prev :time/end-of-contract-year-flag])
                    [:this :time/operating-period-flag])})

(def contract-year-applicable-to-period
  {:units "date"
   :calculator '(cond (flagged? [:this :time/first-model-column-flag])
                      [:const :inputs/annual-year-end-date-of-first-operating-period]

                      (flagged? [:prev :time/end-of-contract-year-flag])
                      (end-of-month [:prev :self] 12)
                      :else [:prev :self])})

(def contract-year
  {:units "year"
   :calculator '(year-of [:this :time/contract-year-applicable-to-period])})

(def time-rows
  #:time
   {:model-column-counter            model-column-counter
    :first-model-column-flag         first-model-column-flag
    :model-period-beginning          model-period-beginning
    :model-period-ending             model-period-ending
    :end-of-contract-year-flag       end-of-contract-year-flag
    :end-of-operating-period         end-of-operating-period
    :operating-period-flag           operating-period-flag
    :period-number                   period-number
    :contract-year-number            contract-year-number
    :contract-year-applicable-to-period contract-year-applicable-to-period
    :contract-year                   contract-year})

(def compound-degradation
  {:units "percent"
   :calculator '(if (flagged? [:this :time/operating-period-flag])
                  (/ 1 (Math/pow (inc [:const :inputs/annual-degradation])
                                 (dec [:this :time/contract-year-number])))
                  0)})

(def seasonality-adjustment
  {:units "percent"
   :calculator '(if (flagged? [:this :time/operating-period-flag])
                  (nth [:const :inputs/yields]
                       (dec [:this :time/period-number]))
                  0)})

(def electricity-generation
  {:units "GWh"
   :calculator '(* [:this :revenue/compound-degradation]
                   [:this :revenue/seasonality-adjustment]
                   [:const :inputs/year-1-p50-yield]
                   [:const :inputs/availability])})

(def electricity-generation-revenue
  {:units "$000"
   :calculator '(/ (* 1000000
                      [:const :inputs/power-tariff]
                      [:this :revenue/electricity-generation]
                      [:this :time/operating-period-flag])
                   1000)})

(def revenue-rows
  #:revenue
   {:compound-degradation           compound-degradation
    :seasonality-adjustment         seasonality-adjustment
    :electricity-generation         electricity-generation
    :electricity-generation-revenue electricity-generation-revenue})

(def model (add-keys-as-name (merge time-rows inputs revenue-rows)))

(check-model-deps model)
(comment
  (uber/pprint (dependency-graph model))

  (uber/viz-graph (dependency-graph model)))

(alg/topsort (dependency-graph model))

(comment

  (get-edges :blah model-column-counter)
  (get-edges :blah first-model-column-flag)
  (get-edges :blah model-period-beginning)

  (extract-deps [] (:calculator model-period-beginning)))

;; runing the model

(defn create-table [model]
  (update-vals model #(vector (:starter %))))

(def start-table (create-table model))

(defn replace-reference [[relative target] table period]
  (if (= :const relative)
    (first (table target))
    (nth (target table)
         (if (= relative :prev) (dec period) period))))

(replace-reference [:prev :time/model-column-counter] start-table 1)
(replace-reference [:const :inputs/operating-years-remaining] start-table 10)

(defn replace-self-ref [nm [rel targ]] (if (= :self targ) [rel nm] [rel targ]))

(defn replace-refs-in-calc [calc replacements]
  (walk/postwalk
   #(if (vector? %) (replacements %) %)
   calc))

(replace-refs-in-calc
 (get-in model [:time/model-column-counter :calculator])
 {[:prev :self] 0})

(defn run-calc [row table period]
  (let [deps (extract-deps [] (:calculator row))
        deps-with-self-repl (map #(replace-self-ref (:name row) %) deps)
        replacements (zipmap deps (map #(replace-reference % table period) deps-with-self-repl))]
    (try (eval (replace-refs-in-calc (:calculator row) replacements))
         (catch Exception e (throw (ex-info "Error calculating"
                                            {:name (:name row)
                                             :calc (:calculator row)
                                             :replaced-calc (replace-refs-in-calc (:calculator row) replacements)}))))))

(run-calc (:time/model-column-counter model)
          start-table 1)

(defn update-table [model row-name table period]
  (update table row-name conj (run-calc (row-name model)
                                        table
                                        period)))

(update-table model :time/model-column-counter start-table 1)
(update-table model :inputs/operating-years-remaining start-table 1)

(defn run [model table period row-names]
  (reduce (fn [table row-name]
            (update-table model row-name table period))
          table
          row-names))

(try (let [order (reverse (alg/topsort (dependency-graph model)))]
       (run model start-table 1 order))
     (catch Exception e (ex-data e)))
;; => :time/operating-period-flag

(defn runner [model graph-order]
  (fn [table period]
    (run model table period graph-order)))

(defn run2 [model periods]
  (let [graph-order (reverse (alg/topsort (dependency-graph model)))
        runr (runner model graph-order)]
    (reduce runr
            (create-table model)
            (range 1 (inc periods)))))

(try (run2 model 5)
     (catch Exception e (ex-data e)))