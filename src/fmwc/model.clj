(ns fmwc.model
  (:require [fmwc.framework2 :as fw]
            [fmwc.model.time]
            [fmwc.model.utils :refer :all]))

;; Inputs
;;;;;;;;;;;;;;;;

(def standard-inputs
  ;;note that these inputs may be depended on by standard model rows
  #:inputs
   {:first-date-of-time-rulers  {:units "date"   :starter "2020-07-01"}
    :aquisition-date            {:units "date"   :starter "2021-03-31"}
    :annual-year-end-date-of-first-operating-period {:units "date" :starter "2020-07-31"}
    :operating-years-remaining  {:units "years"  :starter 25}
    :length-of-operating-period {:units "months" :starter 3}
    :periods-in-year            {:units ""       :starter 4}})

(def inputs
  #:inputs
   {:annual-degradation         {:units "percent" :starter 0.005}
    :year-1-p50-yield           {:units "KWh" :starter 250}
    :power-tariff               {:units "$/KWh" :starter 0.065}
    :yields                     {:units "percent" :starter [0.33 0.36 0.155 0.155]}
    :availability               {:units "percent" :starter 0.97}})

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

(def model (fw/make-model [fmwc.model.time/time-rows standard-inputs inputs revenue-rows]))
(def deps (fw/dependency-order model))
(def output (fw/run2 model 5))

(comment
  (fw/check-model-deps model)

  (try (fw/run2 model 5)
       (catch Exception e (ex-data e)))

  `(fw/vizi-deps model))


(def table-header [:time/model-period-ending
                   :time/contract-year
                   :time/model-column-counter])

(def revenue-calc [:revenue/compound-degradation :revenue/seasonality-adjustment
                   :revenue/electricity-generation :revenue/electricity-generation-revenue])

(comment
  (fw/output->table model output "time" ((group-by namespace (reverse deps)) "time"))

  (fw/output->table model output "header " table-header)

  (fw/output->table model output "revenue" revenue-calc))
