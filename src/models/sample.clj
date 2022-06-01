(ns models.sample
  (:require [fmwk.framework :as fw]
            [fmwk.utils :refer :all]))

(def inputs
  #:inputs
   {:model-start-date        "2020-01-01"
    :period-length-in-months 3})

(def periods
  #:period
   {:number            '(inc [:number :prev])
    :first-period-flag '(= 1 [:number])
    :start-date        '(if [:first-period-flag]
                          [:inputs/model-start-date]
                          (add-days [:end-date :prev] 1))
    :end-date          '(add-days
                         (add-months "2020-01-01"
                                     [:inputs/period-length-in-months])
                         -1)})

(def invoices
  #:invoices
   {:sales})

(def calcs [periods])
(def model (fw/build-model2 inputs calcs))
(def results (fw/run-model model 20))
(fw/print-category results :period/number "period" 1 4)
