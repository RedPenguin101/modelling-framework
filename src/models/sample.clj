(ns models.sample
  (:require [fmwk.framework2 :as f]
            [fmwk.utils :refer :all]))

(f/reset-model!)

(f/base-case!
 "base-case"
 :model-start-date        "2020-01-01"
 :period-length-in-months 3
 :invoice-payment-terms   1 ;; months
 )

(f/calculation!
 "period"
 :number            '(inc [:number :prev])
 :first-period-flag '(= 1 [:number])
 :start-date        '(if [:first-period-flag]
                       [:inputs/model-start-date]
                       (add-days [:end-date :prev] 1))
 :end-date          '(add-days
                      (add-months "2020-01-01"
                                  [:inputs/period-length-in-months])
                      -1))

(f/calculation!
 "invoices"
 :issued '(if [:period/first-period-flag]
            20
            (* [:issued :prev] 1.2))
 :paid   [:issued :prev])

(f/totalled-calculation!
 "cashflows" :net-cashflow
 :invoices    [:invoices/paid]
 :overheads   [:placeholder 1])

(f/totalled-calculation!
 "income" :profit-after-tax
 :revenue  [:invoices/issued]
 :expenses [:placeholder 1])

(f/corkscrew!
 "receivables"
 :increases [:invoices/issued]
 :decreases [:invoices/paid])

@f/calculation-store
(def model (f/compile-model!))

(comment
  (require '[fmwk.table-runner :as tr])
  model
  (eval (doall (tr/make-runner (:calculation-order model)
                               (:rows model)))))

(def results (time (f/run-model model 20)))

(f/print-category results :period/number "invoices" 1 4)
