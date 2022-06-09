(ns models.gridlines
  (:require [fmwk.framework :as f :refer [base-case! calculation! bulk-metadata! metadata! corkscrew! totalled-calculation! check! outputs!]]
            [fmwk.utils :refer [when-flag when-not-flag round mean]]
            [fmwk.dates :refer [year-frac-act-360 month-of add-days add-months date= date< date<= date> date>=]]
            [fmwk.irr   :refer [irr-days]]))

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

 :co-invest-share            0.4

 :corporate-tax-rate         0.2
 :tax-depreciation-rate      0.05)

(calculation!
 "TIME.period"
 :number                   '(inc [:number :prev])
 :first-period-flag        '(= 1 [:number])
 :start-date               '(if [:first-period-flag]
                              [:inputs/model-start-date]
                              (add-days [:end-date :prev] 1))
 :end-date                 '(-> [:start-date]
                                (add-months [:inputs/months-in-period])
                                (add-days -1)))

(metadata!
 "TIME.period"
 :number     {:units :counter}
 :start-date {:units :date}
 :end-date   {:units :date})

(calculation!
 "TIME.Operating-Period"
 :close-flag               '(date= [:TIME.period/end-date] [:inputs/financial-close-date])
 :exit-flag                '(date= [:TIME.period/end-date]
                                   (add-months [:inputs/financial-close-date]
                                               (* 12 [:inputs/operating-years-remaining])))
 :start-date               [:inputs/operating-period-start]
 :end-date                 '(-> [:start-date]
                                (add-months (* 12 [:inputs/operating-years-remaining]))
                                (add-days -1))
 :in-flag                  '(and (date>= [:TIME.period/start-date]
                                         [:inputs/operating-period-start])
                                 (date<= [:TIME.period/end-date]
                                         [:end-date]))
 :first-flag               '(date= [:start-date]
                                   [:TIME.period/start-date])
 :last-flag                '(date= [:end-date]
                                   [:TIME.period/end-date]))

(metadata!
 "TIME.Operating-Period"
 :start-date {:units :date :hidden true})

(map (fn [x] (inc (quot (dec x) 3))) (range 1 13))

(calculation!
 "TIME.Calendar-Year"
 :last-flag      '(= 12 (month-of [:TIME.period/end-date]))
 :first-flag     '(= 1 (month-of [:TIME.period/start-date]))
 :quarter        '(inc (quot (dec (month-of [:TIME.period/end-date])) 3)))

(calculation!
 "TIME.Contract-Year"
 :end-flag         '(= (month-of [:inputs/financial-close-date])
                       (month-of [:TIME.period/end-date]))
 :start-flag       '(true? [:end-flag :prev])
 :number           '(+ [:number :prev]
                       (if (and [:start-flag] [:TIME.Operating-Period/in-flag]) 1 0))
 :quarter           '(when-flag [:TIME.Operating-Period/in-flag] (inc (mod [:quarter :prev] 4))))

;; Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "OPERATIONS.Revenue"
 :compound-degradation           '(when-flag [:TIME.Operating-Period/in-flag]
                                             (/ 1 (Math/pow (inc [:inputs/annual-degradation])
                                                            (dec [:TIME.Contract-Year/number]))))
 :seasonality-adjustment         '(case (int [:TIME.Contract-Year/quarter])
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
 "OPERATIONS.Revenue"
 :annual-degradation             {:units :percent}
 :compound-degradation           {:units :percent}
 :seasonality-adjustment         {:units :percent}
 :electricity-generation         {:total true :units-display "KWh"}
 :revenue                        {:units :currency-thousands :total true})

(calculation!
 "OPERATIONS.Opex"
 :escalation-factor [:placeholder 1] ;; TODO
 :om-expense-pos    '(when-flag
                      [:TIME.Operating-Period/in-flag]
                      (/ (* [:inputs/annual-opex-cost] [:escalation-factor])
                         [:inputs/periods-in-year]))
 :om-expense        '(- [:om-expense-pos]))

(metadata!
 "OPERATIONS.Opex"
 :escalation-factor {:units :factor}
 :om-expense-pos    {:hidden true}
 :om-expense        {:units :currency-thousands :total true})


;; Accounting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "ACCOUNTING.Depreciation"
 :cost-of-solar-asset  [:inputs/cost-of-solar-asset]

 :useful-life-of-asset [:inputs/useful-life-of-asset]
 :useful-life-end      '(add-days (add-months [:inputs/operating-period-start] (* 12 [:useful-life-of-asset])) -1)
 :useful-life-flag     '(and (date>= [:TIME.period/start-date]
                                     [:inputs/operating-period-start])
                             (date<= [:TIME.period/end-date]
                                     [:useful-life-end]))
 :purchase-cashflow     '(when-flag [:TIME.Operating-Period/close-flag]
                                    [:cost-of-solar-asset])
 :depreciation-pos     '(when-flag [:useful-life-flag]
                                   (/ [:cost-of-solar-asset]
                                      (* [:useful-life-of-asset]
                                         [:inputs/periods-in-year])))
 :depreciation         '(- [:depreciation-pos]))

(metadata!
 "ACCOUNTING.Depreciation"
 :depreciation-pos {:units :currency :total true})

(corkscrew!
 "ACCOUNTING.Depreciation.Balance"
 :increases       [:ACCOUNTING.Depreciation/purchase-cashflow]
 :decreases       [:ACCOUNTING.Depreciation/depreciation-pos])

;; EQUITY
;;;;;;;;;;;;;;;;;;;;

(calculation!
 "EQUITY.Share-Capital"
 :drawdown        '(when-flag [:TIME.Operating-Period/close-flag]
                              (* (- 1 [:inputs/senior-debt-ltv])
                                 [:inputs/cost-of-solar-asset]))
 :redemption-pos  '(when-flag [:TIME.Operating-Period/last-flag]
                              [:EQUITY.Share-Capital.Balance/start])
 :redemption       '(- [:redemption-pos]))

(corkscrew!
 "EQUITY.Share-Capital.Balance"
 :starter          [:EQUITY.Share-Capital/drawdown]
 :start-condition  [:TIME.Operating-Period/close-flag]
 :decreases        [:EQUITY.Share-Capital/redemption-pos])

(calculation!
 "EQUITY.Dividends"
 :earnings-available '(max 0 (+ [:INCOME.Retained/start] [:INCOME/profit-after-tax]))
 :cash-available     '(max 0 (+ [:CASHFLOW.Retained/start] [:CASHFLOW.Financing/available-for-dividends]))
 :dividend-paid-pos  '(min [:earnings-available] [:cash-available])
 :dividend-paid      '(- [:dividend-paid-pos])
 :cumulative         '(+ [:cumulative :prev] [:dividend-paid-pos]))

(bulk-metadata!
 "EQUITY.Dividends"
 {:units :currency-thousands})

(metadata!
 "EQUITY.Dividends"
 :dividend-paid {:total true})

;; DEBT
;;;;;;;;;;;;;;;;;;;;

(calculation!
 "SENIOR-DEBT"
 :drawdown-amount       '(* [:inputs/senior-debt-ltv] [:inputs/cost-of-solar-asset])
 :drawdown              '(when-flag
                          [:TIME.Operating-Period/close-flag]
                          [:drawdown-amount])
 :end-of-repayment       '(add-months [:inputs/financial-close-date]
                                      (* 12 [:inputs/senior-debt-repayment-term]))
 :repayment-period-flag  '(and (date>= [:TIME.period/start-date]
                                       [:inputs/operating-period-start])
                               (date<= [:TIME.period/end-date]
                                       [:end-of-repayment]))
 :repayment-amount-pos    '(when-flag [:repayment-period-flag]
                                      (/ [:drawdown-amount]
                                         (* [:inputs/periods-in-year]
                                            [:inputs/senior-debt-repayment-term])))
 :repayment-amount        '(- [:repayment-amount-pos])
 :year-frac               '(year-frac-act-360 (add-days [:TIME.period/start-date] -1)
                                              [:TIME.period/end-date])
 :interest-pos            '(* [:SENIOR-DEBT.Balance/start]
                              [:year-frac]
                              (+ [:inputs/senior-debt-margin]
                                 [:inputs/senior-debt-swap-rate]))
 :interest                 '(- [:interest-pos]))

(metadata!
 "SENIOR-DEBT"
 :drawdown             {:total true :units :currency-thousands}
 :drawdown-amount      {:units :currency-thousands}
 :end-of-repayment     {:units :date}
 :repayment-amount-pos {:total true :units :currency-thousands}
 :repayment-amount     {:total true :units :currency-thousands}
 :year-frac            {:units :factor}
 :interest-pos         {:total true :units :currency-thousands}
 :interest             {:total true :units :currency-thousands})

(corkscrew!
 "SENIOR-DEBT.Balance"
 :increases [:SENIOR-DEBT/drawdown]
 :decreases [:SENIOR-DEBT/repayment-amount-pos])

(bulk-metadata!
 "SENIOR-DEBT.Balance" {:units :currency-thousands})

(calculation!
 "SENIOR-DEBT.Dscr"
 :debt-service             '(+ [:SENIOR-DEBT/repayment-amount-pos]
                               [:SENIOR-DEBT/interest-pos])
 :cash-available           [:CASHFLOW.Operating/available-for-debt-service]
 :dscr                     '(if (true? [:SENIOR-DEBT/repayment-period-flag])
                              (/ [:cash-available]
                                 [:debt-service]) 0))

(metadata!
 "SENIOR-DEBT.Dscr"
 :dscr                 {:units :factor})


;; TAX
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "TAX.Accounting"
 :corp-tax-expense-pos '(* [:INCOME/profit-before-tax] [:inputs/corporate-tax-rate])
 :corp-tax-expense     '(- [:corp-tax-expense-pos]))

(metadata!
 "TAX.Accounting"
 :corp-tax-expense-pos  {:units :currency-thousands :total true}
 :corp-tax-expense      {:units :currency-thousands :total true})

(metadata!
 "TAX.Accounting"
 :tax-rate             {:units :percent}
 :corp-tax-expense     {:hidden true}
 :corp-tax-expense-pos {:units :currency-thousands :total true})

(calculation!
 "TAX.Depreciation"
 :purchase-cashflow '(when-flag [:TIME.Operating-Period/close-flag]
                                [:inputs/cost-of-solar-asset])
 :depreciation-pos  '(if [:TIME.Operating-Period/last-flag]
                       [:TAX.Depreciation.Balance/start]
                       (* [:inputs/tax-depreciation-rate]
                          [:TAX.Depreciation.Balance/start]))
 :depreciation      '(- [:depreciation-pos]))

(bulk-metadata!
 "TAX.Depreciation"
 {:units :currency-thousands :total true})

(metadata!
 "TAX.Depreciation"
 :depreciation-rate  {:units :percent :total false}
 :depreciation       {:hidden true})

(corkscrew!
 "TAX.Depreciation.Balance"
 :increases [:TAX.Depreciation/purchase-cashflow]
 :decreases [:TAX.Depreciation/depreciation-pos])

(bulk-metadata!
 "TAX.Depreciation.Balance"
 {:units :currency-thousands})

(totalled-calculation!
 "TAX.ThinCap" :interest-deduction-for-tax
 :interest-paid         [:SENIOR-DEBT/interest-pos]
 :other-allowable-interest [:placeholder 0])

(bulk-metadata!
 "TAX.ThinCap"
 {:units :currency-thousands :total true})

(metadata!
 "TAX.ThinCap"
 :arms-length-rate  {:units :percent :total false})

(calculation!
 "TAX.Payable"
 :taxable-income-or-losses '(- [:INCOME/EBITDA]
                               [:TAX.Depreciation/depreciation-pos]
                               [:TAX.ThinCap/interest-deduction-for-tax])
 :taxable-income           '(max 0 [:taxable-income-or-losses])
 :taxable-losses           '(- (min 0 [:taxable-income-or-losses]))
 :tax-losses-utilized      '(min [:taxable-income] [:TAX.Loss-Carry-Forward/start])
 :taxable-income-less-utilization '(- [:taxable-income] [:tax-losses-utilized])
 :tax-paid-pos             '(max 0 (* [:taxable-income-less-utilization] [:inputs/corporate-tax-rate]))
 :tax-paid                 '(- [:tax-paid-pos]))

(bulk-metadata!
 "TAX.Payable"
 {:units :currency-thousands :total true})

(metadata!
 "TAX.Payable"
 :taxable-income-or-losses {:hidden true}
 :tax-rate  {:units :percent :total false}
 :tax-paid  {:hidden true})

(corkscrew!
 "TAX.Loss-Carry-Forward"
 :increases [:TAX.Payable/taxable-losses]
 :decreases [:TAX.Payable/tax-losses-utilized])

(bulk-metadata!
 "TAX.Loss-Carry-Forward"
 {:units :currency-thousands :total true})

(corkscrew!
 "TAX.Deferred-Tax-Balance"
 :increases [:TAX.Accounting/corp-tax-expense-pos]
 :decreases [:TAX.Payable/tax-paid-pos])

(bulk-metadata!
 "TAX.Deferred-Tax-Balance"
 {:units :currency-thousands :total true})

;; Financial Statements
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(totalled-calculation!
 "CASHFLOW.Operating" :available-for-debt-service
 ;; TODO payment term delay for revenue 
 :revenue                        [:OPERATIONS.Revenue/revenue]
 :opex-expense                   [:OPERATIONS.Opex/om-expense]
 :puchase-of-solar-asset         '(- [:ACCOUNTING.Depreciation/purchase-cashflow])
 :tax-paid                       [:TAX.Payable/tax-paid])

(bulk-metadata!
 "CASHFLOW.Operating"
 {:units :currency-thousands :total true})

(calculation!
 "CASHFLOW.Financing"
 ;; TODO payment term delay for revenue
 :available-for-debt-service     [:CASHFLOW.Operating/available-for-debt-service]
 :senior-debt-principal          '(+ [:SENIOR-DEBT/drawdown] [:SENIOR-DEBT/repayment-amount])
 :senior-interest-paid           [:SENIOR-DEBT/interest]
 :available-for-rcf              '(+ [:available-for-debt-service]
                                     [:senior-debt-principal]
                                     [:senior-interest-paid])
 :rcf-principal                  [:placeholder 0]
 :rcf-interest-paid              [:placeholder 0]
 :available-for-shareholders     '(+ [:available-for-rcf]
                                     [:rcf-principal]
                                     [:rcf-interest-paid])
 :share-capital                  '(+ [:EQUITY.Share-Capital/drawdown] [:EQUITY.Share-Capital/redemption])
 :available-for-dividends        '(+ [:available-for-shareholders]
                                     [:share-capital]))
(corkscrew!
 "CASHFLOW.Retained"
 :increases [:CASHFLOW.Financing/available-for-dividends]
 :decreases [:EQUITY.Dividends/dividend-paid-pos])

#_(check!
   :no-negative-cash-balance
   '(>= [:CASHFLOW.Retained/end] 0))

(bulk-metadata!
 "CASHFLOW.Financing"
 {:units :currency-thousands :total true})

(metadata!
 "CASHFLOW.Financing"
 :available-for-debt-service {:hidden true}
 :available-for-rcf          {:total-row true}
 :available-for-shareholders {:total-row true})

(bulk-metadata! "CASHFLOW.Retained" {:units :currency-thousands})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; INCOME ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "INCOME"
 :revenue                        [:OPERATIONS.Revenue/revenue]
 :opex-expense                   [:OPERATIONS.Opex/om-expense]
 :EBITDA                         '(+ [:revenue]
                                     [:opex-expense])
 :depreciation-charge            [:ACCOUNTING.Depreciation/depreciation]
 :senior-debt-interest           [:SENIOR-DEBT/interest]
 :rcf-interest                   [:placeholder 0]
 :profit-before-tax              '(+ [:EBITDA]
                                     [:depreciation-charge]
                                     [:senior-debt-interest]
                                     [:rcf-interest])
 :corporate-tax-expense          [:TAX.Accounting/corp-tax-expense]
 :profit-after-tax               '(+ [:profit-before-tax]
                                     [:corporate-tax-expense]))

(bulk-metadata!
 "INCOME"
 {:units :currency-thousands :total true})

(metadata!
 "INCOME"
 :EBITDA            {:total-row true}
 :profit-before-tax {:total-row true}
 :profit-after-tax  {:total-row true})

(corkscrew!
 "INCOME.Retained"
 :increases [:INCOME/profit-after-tax]
 :decreases [:EQUITY.Dividends/dividend-paid-pos])

(bulk-metadata!
 "INCOME.Retained"
 {:units :currency-thousands})

(totalled-calculation!
 "BALANCE-SHEET.Assets" :total-assets
 :non-current-assets [:ACCOUNTING.Depreciation.Balance/end]
 :retained-cash      [:CASHFLOW.Retained/end])

(bulk-metadata!
 "BALANCE-SHEET.Assets"
 {:units :currency-thousands})

(totalled-calculation!
 "BALANCE-SHEET.Liabilities" :total-liabilities
 :senior-debt          [:SENIOR-DEBT.Balance/end]
 :rcf-balance          [:placeholder 0]
 :deferred-tax-balance [:TAX.Deferred-Tax-Balance/end]
 :share-capital        [:EQUITY.Share-Capital.Balance/end]
 :retained-earnings    [:INCOME.Retained/end])

(bulk-metadata!
 "BALANCE-SHEET.Liabilities"
 {:units :currency-thousands})

(calculation!
 "BALANCE-SHEET.Check"
 :balance-check '(- [:BALANCE-SHEET.Assets/total-assets]
                    [:BALANCE-SHEET.Liabilities/total-liabilities]))

(check!
 :balance-sheet-balances
 '(= (round [:BALANCE-SHEET.Assets/total-assets])
     (round [:BALANCE-SHEET.Liabilities/total-liabilities])))

;; Metrics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "EQUITY-RETURN"
 :cashflow-for-irr   '(- (+ [:EQUITY.Dividends/dividend-paid-pos]
                            [:EQUITY.Share-Capital/redemption-pos])
                         [:EQUITY.Share-Capital/drawdown]))

(metadata!
 "EQUITY-RETURN"
 :dividend {:total true})

(calculation!
 "INVESTMENT-PREMIUM"
 :premium-amount          [:placeholder 10299000]
 :premium                 '(when-flag
                            [:TIME.Operating-Period/close-flag]
                            [:premium-amount])
 :aurelius-share-of-distr '(- (* [:inputs/co-invest-share] (- (+ [:EQUITY.Dividends/dividend-paid-pos]
                                                                 [:EQUITY.Share-Capital/redemption-pos])
                                                              [:EQUITY.Share-Capital/drawdown]))
                              [:premium]))

(metadata!
 "INVESTMENT-PREMIUM"
 :share {:units :percent}
 :premium-amount          {:hidden true}
 :premium                 {:units :currency-thousands}
 :aurelius-share-of-distr {:units :currency-thousands :total true})

(outputs!
 :effective-tax-rate {:name "Effective Tax Rate"
                      :units :percent
                      :function '(/ (apply + :TAX.Payable/tax-paid-pos) (apply + :INCOME/profit-before-tax))}
 :irr       {:name "IRR to Equity Holders"
             :units :percent
             :function '(irr-days :TIME.period/end-date :EQUITY-RETURN/cashflow-for-irr)}
 :irr-coinv {:name "IRR to Coinvest"
             :units :percent
             :function '(irr-days :TIME.period/end-date :INVESTMENT-PREMIUM/aurelius-share-of-distr)}
 #_#_:dividends {:name "Dividends paid (thousands)"
                 :units :currency-thousands
                 :function '(apply + :EQUITY.Dividends/dividend-paid-pos)}
 #_#_:min-dscr  {:name "Min DSCR"
                 :units :factor
                 :function '(apply min (remove zero? :SENIOR-DEBT.Dscr/dscr))}
 #_#_:avg-dscr  {:name "Avg DSCR"
                 :units :factor
                 :function '(mean (remove zero? :SENIOR-DEBT.Dscr/dscr))})

(f/compile-run-display! 10 {:header       :TIME.period/end-date
                            :sheets       ["CASHFLOW"]
                            :show-imports false
                            :start        1
                            :outputs      false
                            :charts       []})
