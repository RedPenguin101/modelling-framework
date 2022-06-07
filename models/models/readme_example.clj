(ns models.readme-example
  (:require [fmwk.framework :as f :refer [base-case! calculation! bulk-metadata! metadata! cork-metadata! corkscrew! totalled-calculation! check! outputs!]]
            [fmwk.utils :refer [when-flag when-not-flag round mean]]
            [fmwk.dates :refer [year-frac-act-360 month-of add-days add-months date= date< date<= date> date>=]]
            [fmwk.irr :refer [irr-days]]))

(f/reset-model!)

(base-case!
 "base-case"
 :model-start-date "2020-01-01"
 :length-of-period 3
 :periods-in-year  4
 :debt-drawdown    1000000
 :repayment-term   3
 :interest-rate    0.05)

(calculation!
 "TIME.periods"
 :number                   '(+ 1 [:number :prev])
 :first-flag               '(= 1 [:number])
 :start-date               '(if (= 1 [:number])
                              [:inputs/model-start-date]
                              (add-days [:end-date :prev] 1))
 :end-date                 '(-> [:start-date]
                                (add-months [:inputs/length-of-period])
                                (add-days -1)))

(calculation!
 "DEBT.Principal"
 :drawdown             '(when-flag
                         [:TIME.periods/first-flag]
                         [:inputs/debt-drawdown])
 :amortization-periods '(* [:inputs/repayment-term]
                           [:inputs/periods-in-year])
 :repayment-amount-pos '(when-flag
                         (pos? [:DEBT.Principal-Balance/start])
                         (/ [:inputs/debt-drawdown]
                            [:amortization-periods])))

(metadata!
 "DEBT.Principal"
 :drawdown              {:units :currency-thousands}
 :amortization-periods  {:units :counter}
 :repayment-amount-pos  {:units :currency-thousands})

(corkscrew!
 "DEBT.Principal-Balance"
 :starter         [:inputs/debt-drawdown]
 :decreases       [:DEBT.Principal/repayment-amount-pos]
 :start-condition [:TIME.periods/first-flag])

(bulk-metadata!
 "DEBT.Principal-Balance"
 {:units :currency-thousands})

(check!
 :debt-balance-gt-zero
 '(>= [:DEBT.Principal-Balance/end] 0))

(calculation!
 "DEBT.Interest"
 :calculation-basis       [:DEBT.Principal-Balance/start]
 :dummy                   [:placeholder true]
 :annual-rate             [:inputs/interest-rate]
 :year-frac              '(year-frac-act-360
                           (add-days [:TIME.periods/start-date] -1)
                           [:TIME.periods/end-date])
 :amount                 '(* [:calculation-basis]
                             [:annual-rate]
                             [:year-frac]))

(metadata!
 "DEBT.Interest"
 :calculation-basis {:hidden true}
 :annual-rate       {:units :percent}
 :year-frac         {:units :factor}
 :amount            {:units :currency :total true})

(totalled-calculation!
 "DEBT.Cashflows" :cashflow
 :drawdowns   '(- [:DEBT.Principal/drawdown])
 :redemptions [:DEBT.Principal/repayment-amount-pos]
 :interest    [:DEBT.Interest/amount])

(outputs!
 :irr       {:name "IRR to Equity Holders"
             :units :percent
             :function '(irr-days :TIME.periods/end-date
                                  :DEBT.Cashflows/cashflow)})

(f/compile-run-display! 24 {:header :TIME.periods/end-date
                            :sheets ["DEBT"]
                            :outputs true
                            :start 1
                            :show-imports false
                            :charts [:DEBT.Principal-Balance/end]})
