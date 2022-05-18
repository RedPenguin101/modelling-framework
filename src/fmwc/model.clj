(ns fmwc.model
  (:require [fmwc.framework2 :as fw]
            [fmwc.model.utils :refer [equal-to?
                                      flagged? make-flag
                                      add-months end-of-month add-days
                                      month-of year-of
                                      date> date<=]]))

;; Inputs
;;;;;;;;;;;;;;;;

(def inputs
  #:inputs
   {:aquisition-date            {:units "date"   :starter "2021-03-31"}
    :first-date-of-time-rulers  {:units "date"   :starter "2020-07-01"}
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
   :calculator '(inc [:prev :self])})

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
   :calculator '(add-days (add-months [:this :time/model-period-beginning]
                                      [:const :inputs/length-of-operating-period])
                          -1)})

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
   :calculator '(* (+ [:prev :self] [:prev :time/end-of-contract-year-flag])
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

(def model (fw/make-model [time-rows inputs revenue-rows]))
(def deps (fw/dependency-order model))
(def output (fw/run2 model 5))

(comment
  (fw/check-model-deps model)

  (try (fw/run2 model 5)
       (catch Exception e (ex-data e)))


  (fw/vizi-deps model))

(defn select-qualified-keys [m qualifiers]
  (let [qualifiers (set qualifiers)]
    (into {} (filter (fn [[k]] (qualifiers (keyword (namespace k)))) m))))

(select-qualified-keys output [:inputs])

((group-by namespace deps) "time")
(defn output->table [output keys]
  (for [k keys]
    (into [k] (output k))))

(output->table output ((group-by namespace (reverse deps)) "time"))

(def table-header [:time/model-period-ending
                   :time/contract-year
                   :time/model-column-counter])

(output->table output table-header)

(def revenue-calc [:revenue/compound-degradation :revenue/seasonality-adjustment
                   :revenue/electricity-generation :revenue/electricity-generation-revenue])

(output->table output revenue-calc)
