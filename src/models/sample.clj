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

(f/metadata!
 "period"
 :start-date {:units :date}
 :end-date {:units :date})

(f/calculation!
 "prices"
 :inflation-period   '(dec [:period/number])
 :compound-inflation '(Math/pow [:inputs/inflation-rate] [:inflation-period])
 :sale-price         '(* [:inputs/starting-price] [:compound-inflation])
 :curr-test          [:placeholder -0.1]
 :curr-cents-test    '(if (even? [:period/number]) -0.1 0.001)
 :thousands-test     [:placeholder 100000]
 :true-test          '(= 1 1)
 :false-test         '(= 1 2)
 :total-test         '(* [:period/number] 100)
 :hidden-row         '(* [:period/number] 100))

(f/metadata!
 "prices"
 :inflation-period   {:units :counter}
 :compound-inflation {:units :percent}
 :sale-price         {:units :currency-cents}
 :thousands-test     {:units :currency-thousands}
 :curr-test          {:units :currency}
 :curr-cents-test    {:units :currency-cents}
 :true-test          {:units :boolean}
 :false-test         {:units :boolean}
 :total-test         {:total true}
 :hidden-row         {:hidden true})

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
 :spoiler          [:placeholder 1]
 :receivables      [:receivables/end])

(f/totalled-calculation!
 "balance-sheet.liabilities" :total-liabilities
 :equity            [:placeholder 0]
 :retained-earnings [:income.retained-earnings/end])

(f/check!
 :balance-sheet-balances
 '(= (Math/round [:balance-sheet.assets/total-assets])
     (Math/round [:balance-sheet.liabilities/total-liabilities]))
 :other-thing '(= 1 1))

(def model (f/compile-model!))

(comment
  (require '[fmwk.table-runner :as tr])
  model
  (eval (doall (tr/make-runner (:calculation-order model)
                               (:rows model)))))

(def results (time (f/run-model model 20)))

(:meta model)

(defn print-helper [results cat start]
  (f/print-category-html results (:meta model) :period/end-date cat start (+ 11 start)))

(print-helper results "balance-sheet" 10)
