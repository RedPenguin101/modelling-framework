(ns models.template
  (:require [fmwk.framework2 :as f :refer [base-case! calculation! metadata!]]
            [fmwk.utils :refer :all]))

(f/reset-model!)

(base-case!
 "base-case"
 :model-start-date           "2020-01-01"
 :months-in-period           3
 :operating-period-start     "2021-01-01")

(calculation!
 "period"
 :number                   '(inc [:number :prev])
 :first-period-flag        '(= 1 [:number])
 :start-date               '(if [:first-period-flag]
                              [:inputs/model-start-date]
                              (add-days [:end-date :prev] 1))
 :end-date                 '(-> [:start-date]
                                (add-months [:inputs/months-in-period])
                                (add-days -1)))

(metadata!
 "period"
 :number     {:units :counter}
 :start-date {:units :date}
 :end-date   {:units :date})

(calculation!
 "period.operating"
 :start-date               [:inputs/operating-period-start]
 :in-flag                  '(date>= [:period/start-date]
                                    [:inputs/operating-period-start])
 :first-flag               '(date= [:start-date]
                                   [:period/start-date]))

(metadata!
 "period.operating"
 :start-date {:units :date :hidden true})

(calculation!
 "period.calendar-year"
 :last-flag  '(= 12 (month-of [:period/end-date]))
 :first-flag '(= 1 (month-of [:period/start-date])))

(def model (f/compile-model!))
(def results (time (f/run-model model 30)))
(f/print-category-html results (:meta model) :period/end-date  "period" 1 10)
