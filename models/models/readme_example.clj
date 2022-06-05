(ns models.readme-example
  (:require [fmwk.framework :as f :refer [base-case! calculation! bulk-metadata! metadata! cork-metadata! corkscrew! totalled-calculation! check! outputs!]]
            [fmwk.results-display :refer [print-result-summary!]]
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
 :drawdown             '(when-flag [:TIME.periods/first-flag]
                                   [:inputs/debt-drawdown])
 :repayment-term-years [:placeholder 5]
 :repayment-amount     '(if (pos? [:DEBT.Principal-Balance/start])
                          (/ [:inputs/debt-drawdown]
                             (* [:repayment-term-years]
                                [:inputs/periods-in-year]))
                          0))

(corkscrew!
 "DEBT.Principal-Balance"
 :increases       [:DEBT.Principal/drawdown]
 :decreases       [:DEBT.Principal/repayment-amount])

(metadata!
 "DEBT.Principal"
 :repayment-amount         {:total true})

(calculation!
 "DEBT.Interest"
 :calculation-basis       [:DEBT.Principal-Balance/start]
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
 :year-frac         {:units :factor :total true}
 :amount            {:units :currency :total true})

(f/compile-run-display! 25 {:header :TIME.periods/end-date
                            :sheets ["DEBT"]
                            :start 1
                            :charts []})
