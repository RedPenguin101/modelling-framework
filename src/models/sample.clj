(ns models.sample
  (:require [fmwk.framework2 :as f]
            [fmwk.utils :refer :all]))

(f/reset-model!)

(f/base-case!
 "base-case"
 :model-start-date        "2020-01-01"
 :period-length-in-months 3
 :invoice-payment-terms   1 ;; months

 :inflation-rate          1.02
 :starting-price          10)

(f/calculation!
 "period"
 :number            '(inc [:number :prev])
 :first-period-flag '(= 1 [:number])
 :start-date        '(if [:first-period-flag]
                       [:inputs/model-start-date]
                       (add-days [:end-date :prev] 1))
 :end-date          '(add-days
                      (add-months [:start-date]
                                  [:inputs/period-length-in-months])
                      -1))

(f/calculation!
 "prices"
 :inflation-period   '(dec [:period/number])
 :compound-inflation '(Math/pow [:inputs/inflation-rate] [:inflation-period])
 :sale-price         '(* [:inputs/starting-price] [:compound-inflation])
 :curr-test          [:placeholder 123.51]
 :thousands-test     [:placeholder 100000]
 :true-test          '(= 1 1)
 :false-test         '(= 1 2))

;; types should be counter, percent, currency

(f/metadata!
 "prices"
 :inflation-period   {:units :counter}
 :compound-inflation {:units :percent}
 :sale-price         {:units :currency-cents}
 :thousands-test     {:units :currency-thousands}
 :true-test          {:units :boolean}
 :false-test         {:units :boolean})

(f/calculation!
 "invoices"
 :sales  '(if [:period/first-period-flag]
            20
            (* [:sales :prev] 1.2))
 :issued '(* [:sales] [:prices/sale-price])
 :paid   [:issued :prev])

(f/totalled-calculation!
 "cashflows" :net-cashflow
 :invoices    [:invoices/paid]
 :overheads   '(- [:placeholder 1]))

(f/corkscrew!
 "cashflows.retained-cash"
 :increases [:cashflows/net-cashflow]
 :decreases [])

(f/totalled-calculation!
 "income" :profit-after-tax
 :revenue  [:invoices/issued]
 :expenses [:placeholder -1])

(f/corkscrew!
 "income.retained-earnings"
 :increases [:income/profit-after-tax]
 :decreases [])

(f/corkscrew!
 "receivables"
 :increases [:invoices/issued]
 :decreases [:invoices/paid])

(f/totalled-calculation!
 "balance-sheet.assets" :total-assets
 :cash             [:cashflows.retained-cash/end]
 :receivables      [:receivables/end])

(f/totalled-calculation!
 "balance-sheet.liabilities" :total-liabilities
 :equity            [:placeholder 0]
 :retained-earnings [:income.retained-earnings/end])

(f/check!
 :balance-sheet-balances
 '(= [:balance-sheet.assets/total-assets]
     [:balance-sheet.liabilities/total-liabilities]))

(def model (f/compile-model!))

(comment
  (require '[fmwk.table-runner :as tr])
  model
  (eval (doall (tr/make-runner (:calculation-order model)
                               (:rows model)))))

(def results (time (f/run-model model 20)))

(:meta model)

(f/print-category results (:meta model) :period/end-date "prices" 1 5)
(f/print-category results (:meta model) :period/end-date "checks" 1 5)
