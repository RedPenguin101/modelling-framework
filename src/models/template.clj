(ns models.template
  (:require [fmwk.framework :as fw]
            [fmwk.utils :refer :all]))

(def inputs
  #:inputs
   {:model-start-date "2020-01-01"
    :aquisition-date "2020-12-31"
    :sale-date "2035-12-31"
    :length-of-operating-period 12})

(def periods
  #:period
   {:start-date               '(if (true? [:first-model-column])
                                 [:inputs/model-start-date]
                                 (add-days [:end-date :prev] 1))
    :end-date                 '(add-days
                                (add-months [:start-date]
                                            [:inputs/length-of-operating-period])
                                -1)
    :number                   '(inc [:number :prev])
    :first-model-column       '(= 1 [:number])
    :sale-date                [:inputs/sale-date]
    :financial-close-flag     '(and (date>= [:inputs/aquisition-date]
                                            [:start-date])
                                    (date<= [:inputs/aquisition-date]
                                            [:end-date]))
    :financial-exit-flag      '(and (date>= [:sale-date]
                                            [:start-date])
                                    (date<= [:sale-date]
                                            [:end-date]))})

(def op-period
  #:operating-period
   {:end               [:inputs/sale-date]
    :in-flag           '(and (date> [:period/start-date]
                                    [:inputs/aquisition-date])
                             (date<= [:period/end-date]
                                     [:end]))
    :first-flag        '(date= [:inputs/aquisition-date]
                               (add-days [:period/start-date] -1))
    :last-flag         '(date= [:end]
                               [:period/end-date])})

(def calcs [periods op-period])
(fw/fail-catch (fw/build-model2 inputs calcs))
(def model (fw/build-model2 inputs calcs))
(def header :period/end-date)
(def results (fw/run-model model 20))
(fw/print-category results (:meta model) header "operating-period" 1 10)
