(ns models.gridlines
  (:require [fmwk.framework :as f :refer [base-case! calculation! bulk-metadata! metadata! cork-metadata! corkscrew! totalled-calculation! check! outputs!]]
            [fmwk.results-display :refer [print-result-summary!]]
            [fmwk.utils :refer [when-flag when-not-flag round mean]]
            [fmwk.dates :refer [year-frac-act-360 month-of add-days add-months date= date< date<= date> date>=]]
            [fmwk.irr :refer [irr-days]]))

(f/reset-model!)

(base-case!
 "base-case"
 :model-start-date           "2020-07-01"
 :months-in-period           3
 :periods-in-year            4
 :operating-period-start     "2021-04-01"

 :financial-close-date       "2021-03-31"
 :operating-years-remaining  25

 :cost-of-solar-asset        100000000
 :useful-life-of-asset       25

 :annual-degradation         0.005
 :seasonal-adjustment-q1     0.33
 :seasonal-adjustment-q2     0.36
 :seasonal-adjustment-q3     0.155
 :seasonal-adjustment-q4     0.155
 :year-1-p50-yield           250
 :availability               0.97
 :power-tariff               0.065

 :annual-opex-cost           1500000

 :senior-debt-ltv            0.7
 :senior-debt-swap-rate      0.025
 :senior-debt-margin         0.038
 :senior-debt-repayment-term 10 ;years
 )

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
 :close-flag               '(date= [:period/end-date] [:inputs/financial-close-date])
 :exit-flag                '(date= [:period/end-date]
                                   (add-months [:inputs/financial-close-date]
                                               (* 12 [:inputs/operating-years-remaining])))
 :start-date               [:inputs/operating-period-start]
 :end-date                 '(-> [:start-date]
                                (add-months (* 12 [:inputs/operating-years-remaining]))
                                (add-days -1))
 :in-flag                  '(and (date>= [:period/start-date]
                                         [:inputs/operating-period-start])
                                 (date<= [:period/end-date]
                                         [:end-date]))
 :first-flag               '(date= [:start-date]
                                   [:period/start-date])
 :last-flag                '(date= [:end-date]
                                   [:period/end-date]))

(metadata!
 "period.operating"
 :start-date {:units :date :hidden true})

(map (fn [x] (inc (quot (dec x) 3))) (range 1 13))

(calculation!
 "period.calendar-year"
 :last-flag      '(= 12 (month-of [:period/end-date]))
 :first-flag     '(= 1 (month-of [:period/start-date]))
 :quarter        '(inc (quot (dec (month-of [:period/end-date])) 3)))

(calculation!
 "period.contract-year"
 :end-flag         '(= (month-of [:inputs/financial-close-date])
                       (month-of [:period/end-date]))
 :start-flag       '(true? [:end-flag :prev])
 :number           '(+ [:number :prev]
                       (if (and [:start-flag] [:period.operating/in-flag]) 1 0))
 :quarter           '(when-flag [:period.operating/in-flag] (inc (mod [:quarter :prev] 4))))

;; Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "ops.revenue"
 :annual-degradation             [:inputs/annual-degradation]
 :contract-year-number           [:period.contract-year/number]
 :op-period-flag                 [:period.operating/in-flag]
 :compound-degradation           '(when-flag [:op-period-flag]
                                             (/ 1 (Math/pow (inc [:annual-degradation])
                                                            (dec [:contract-year-number]))))
 :seasonality-adjustment         '(case (int [:period.contract-year/quarter])
                                    0 0
                                    1 [:inputs/seasonal-adjustment-q1]
                                    2 [:inputs/seasonal-adjustment-q2]
                                    3 [:inputs/seasonal-adjustment-q3]
                                    4 [:inputs/seasonal-adjustment-q4])
 :electricity-generation         '(* [:seasonality-adjustment]
                                     [:compound-degradation]
                                     [:inputs/year-1-p50-yield]
                                     [:inputs/availability])
 :revenue '(* [:electricity-generation]
              [:inputs/power-tariff]
              1000000 ; kwh per gwh
              ))

(metadata!
 "ops.revenue"
 :annual-degradation             {:units :percent}
 :compound-degradation           {:units :percent}
 :seasonality-adjustment         {:units :percent}
 :electricity-generation         {:total true}
 :revenue {:units :currency-thousands :total true})

(calculation!
 "ops.opex"
 :escalation-factor [:placeholder 1] ;; TODO
 :om-expense-pos    '(when-flag
                      [:period.operating/in-flag]
                      (/ (* [:inputs/annual-opex-cost] [:escalation-factor])
                         [:inputs/periods-in-year]))
 :om-expense        '(- [:om-expense-pos]))

(metadata!
 "ops.opex"
 :om-expense-pos {:units :currency-thousands :total true}
 :om-expense     {:units :currency-thousands :total true})


;; Non-current assets
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "depreciation"
 :cost-of-solar-asset  [:inputs/cost-of-solar-asset]

 :useful-life-of-asset [:inputs/useful-life-of-asset]
 :useful-life-end      '(add-days (add-months [:inputs/operating-period-start] (* 12 [:useful-life-of-asset])) -1)
 :useful-life-flag     '(and (date>= [:period/start-date]
                                     [:inputs/operating-period-start])
                             (date<= [:period/end-date]
                                     [:useful-life-end]))
 :puchase-cashflow     '(when-flag [:period.operating/close-flag]
                                   [:cost-of-solar-asset])
 :depreciation-pos     '(when-flag [:useful-life-flag]
                                   (/ [:cost-of-solar-asset]
                                      (* [:useful-life-of-asset]
                                         [:inputs/periods-in-year])))
 :depreciation         '(- [:depreciation-pos]))

(metadata!
 "depreciation"
 :depreciation-pos {:units :currency :total true})

(corkscrew!
 "depreciation.balance"
 :increases       [:depreciation/puchase-cashflow]
 :decreases       [:depreciation/depreciation-pos])

;; EQUITY
;;;;;;;;;;;;;;;;;;;;

(calculation!
 "share-capital"
 :drawdown        '(when-flag [:period.operating/close-flag]
                              (* (- 1 [:inputs/senior-debt-ltv])
                                 [:inputs/cost-of-solar-asset]))
 :redemption-pos  '(when-flag [:period.operating/last-flag]
                              [:share-capital.balance/start])
 :redemption       '(- [:redemption-pos]))

(corkscrew!
 "share-capital.balance"
 :starter          [:share-capital/drawdown]
 :start-condition  [:period.operating/close-flag]
 :decreases        [:share-capital/redemption-pos])

(calculation!
 "dividends"
 :earnings-available '(max 0 (+ [:income.retained/start] [:income/profit-after-tax]))
 :cash-available     '(max 0 (+ [:cashflow.retained/start] [:cashflow.financing/available-for-dividends]))
 :dividend-paid-pos  '(min [:earnings-available] [:cash-available])
 :dividend-paid      '(- [:dividend-paid-pos]))

(metadata!
 "dividends"
 :dividend-paid {:total true})

;; DEBT
;;;;;;;;;;;;;;;;;;;;

(calculation!
 "senior-debt"
 :drawdown-amount       '(* [:inputs/senior-debt-ltv] [:inputs/cost-of-solar-asset])
 :drawdown              '(when-flag
                          [:period.operating/close-flag]
                          [:drawdown-amount])
 :end-of-repayment       '(add-months [:inputs/financial-close-date]
                                      (* 12 [:inputs/senior-debt-repayment-term]))
 :repayment-period-flag  '(and (date>= [:period/start-date]
                                       [:inputs/operating-period-start])
                               (date<= [:period/end-date]
                                       [:end-of-repayment]))
 :repayment-amount-pos    '(when-flag [:repayment-period-flag]
                                      (/ [:drawdown-amount]
                                         (* [:inputs/periods-in-year]
                                            [:inputs/senior-debt-repayment-term])))
 :repayment-amount        '(- [:repayment-amount-pos])
 :year-frac               '(year-frac-act-360 (add-days [:period/start-date] -1)
                                              [:period/end-date])
 :interest-pos            '(* [:senior-debt.balance/start]
                              [:year-frac]
                              (+ [:inputs/senior-debt-margin]
                                 [:inputs/senior-debt-swap-rate]))
 :interest                 '(- [:interest-pos]))

(metadata!
 "senior-debt"
 :drawdown             {:total true :units :currency-thousands}
 :drawdown-amount      {:units :currency-thousands}
 :end-of-repayment     {:units :date}
 :repayment-amount-pos {:total true :units :currency-thousands}
 :repayment-amount     {:total true :units :currency-thousands}
 :year-frac            {:units :currency-cents}
 :interest-pos         {:total true :units :currency-thousands}
 :interest             {:total true :units :currency-thousands})

(corkscrew!
 "senior-debt.balance"
 :increases [:senior-debt/drawdown]
 :decreases [:senior-debt/repayment-amount-pos])

(cork-metadata!
 "senior-debt.balance" :currency-thousands)

(calculation!
 "senior-debt.dscr"
 :debt-service             '(+ [:senior-debt/repayment-amount-pos]
                               [:senior-debt/interest-pos])
 :cash-available           [:cashflow.operating/available-for-debt-service]
 :dscr                     '(if (true? [:senior-debt/repayment-period-flag])
                              (/ [:cash-available]
                                 [:debt-service]) 0))

(metadata!
 "senior-debt.dscr"
 :dscr                 {:units :factor})


;; Financial Statements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(totalled-calculation!
 "cashflow.operating" :available-for-debt-service
 ;; TODO payment term delay for revenue 
 :revenue                        [:ops.revenue/revenue]
 :opex-expense                   [:ops.opex/om-expense]
 :puchase-of-solar-asset         '(- [:depreciation/puchase-cashflow]))

(bulk-metadata!
 "cashflow.operating"
 {:units :currency-thousands :total true})

(totalled-calculation!
 "cashflow.financing" :available-for-dividends
 ;; TODO payment term delay for revenue
 :available-for-debt-service     [:cashflow.operating/available-for-debt-service]
 :debt-principal                 '(+ [:senior-debt/drawdown] [:senior-debt/repayment-amount])
 :interest-paid                  [:senior-debt/interest]
 :share-capital                  '(+ [:share-capital/drawdown] [:share-capital/redemption]))

(corkscrew!
 "cashflow.retained"
 :increases [:cashflow.financing/available-for-dividends]
 :decreases [:dividends/dividend-paid-pos])

(check!
 :no-negative-cash-balance
 '(>= [:cashflow.retained/end] 0))

(bulk-metadata!
 "cashflow.financing"
 {:units :currency-thousands :total true})

(metadata!
 "cashflow.financing"
 :available-for-debt-service {:hidden true})

(cork-metadata! "cashflow.retained" :currency-thousands)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; INCOME ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(totalled-calculation!
 "income" :profit-after-tax
 :revenue                        [:ops.revenue/revenue]
 :opex-expense                   [:ops.opex/om-expense]
 :depreciation-charge            [:depreciation/depreciation]
 :interest                       [:senior-debt/interest])

(corkscrew!
 "income.retained"
 :increases [:income/profit-after-tax]
 :decreases [:dividends/dividend-paid-pos])

(bulk-metadata!
 "income"
 {:units :currency-thousands :total true})

(bulk-metadata!
 "income.retained"
 {:units :currency-thousands})

(totalled-calculation!
 "balance-sheet.assets" :total-assets
 :non-current-assets [:depreciation.balance/end]
 :retained-cash      [:cashflow.retained/end])

(bulk-metadata!
 "balance-sheet.assets"
 {:units :currency-thousands})

(totalled-calculation!
 "balance-sheet.liabilities" :total-liabilities
 :debt              [:senior-debt.balance/end]
 :share-capital     [:share-capital.balance/end]
 :retained-earnings [:income.retained/end])

(bulk-metadata!
 "balance-sheet.liabilities"
 {:units :currency-thousands})

(calculation!
 "balance-sheet.check"
 :balance-check '(- [:balance-sheet.assets/total-assets]
                    [:balance-sheet.liabilities/total-liabilities]))

(check!
 :balance-sheet-balances
 '(= (round [:balance-sheet.assets/total-assets])
     (round [:balance-sheet.liabilities/total-liabilities])))

;; Returns to equity holders
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(totalled-calculation!
 "equity-return" :cashflow-for-irr
 :share-capital-drawdown   '(- [:share-capital/drawdown])
 :share-capital-redemption '(- [:share-capital/redemption])
 :dividend                 [:dividends/dividend-paid-pos])

(metadata!
 "equity-return"
 :dividend {:total true})

(outputs!
 :irr       {:name "IRR to Equity Holders"
             :units :percent
             :function '(irr-days :period/end-date :equity-return/cashflow-for-irr)}
 :dividends {:name "Dividends paid (thousands)"
             :units :currency-thousands
             :function '(apply + :dividends/dividend-paid-pos)}
 :min-dscr  {:name "Min DSCR"
             :units :factor
             :function '(apply min (remove zero? :senior-debt.dscr/dscr))}
 :avg-dscr  {:name "Avg DSCR"
             :units :factor
             :function '(mean (remove zero? :senior-debt.dscr/dscr))})

(f/compile-run-display! 20 {:header :period/end-date
                            :sheets ["income"]
                            :start 1
                            :charts []})
