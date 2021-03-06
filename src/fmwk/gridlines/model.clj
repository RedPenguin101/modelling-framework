(ns fmwk.gridlines.model
  (:require [fmwk.framework :as fw]
            [fmwk.simple-viz.main :refer [series-scatter series-line series-lines]]
            [fmwk.utils :refer :all]))

(def inputs
  #:inputs
   {:dummy                      1
    :model-start-date           "2020-07-01"
    :length-of-operating-period 3
    :operating-years-remaining  25
    :aquisition-date            "2021-03-31"
    :periods-in-year            4
    :annual-degradation         0.005
    :seasonal-adjustments-q1    0.33
    :seasonal-adjustments-q2    0.36
    :seasonal-adjustments-q3    0.155
    :seasonal-adjustments-q4    0.155
    :year1-p50-yield            250
    :availability               0.97
    :power-tariff               0.065
    :annual-real-om-cost        1500
    :asset-value-at-start       100000
    :useful-life-of-asset       25
    :senior-debt-gearing        0.7
    :senior-debt-repayment-term 10
    :senior-debt-swap-rate      0.025
    :senior-debt-margin         0.04})

(def time-calcs
  (merge
   #:time.period
    {:start-date               '(if (true? [:first-model-column])
                                  [:inputs/model-start-date]
                                  (add-days [:end-date :prev] 1))
     :end-date                 '(add-days
                                 (add-months [:start-date]
                                             [:inputs/length-of-operating-period])
                                 -1)
     :number                   '(inc [:number :prev])
     :first-model-column       '(= 1 [:number])
     :sale-date                '(end-of-month
                                 [:inputs/aquisition-date]
                                 (* 12 [:inputs/operating-years-remaining]))
     :financial-close-flag     '(and (date>= [:inputs/aquisition-date]
                                             [:start-date])
                                     (date<= [:inputs/aquisition-date]
                                             [:end-date]))
     :financial-exit-flag      '(and (date>= [:sale-date]
                                             [:start-date])
                                     (date<= [:sale-date]
                                             [:end-date]))}

   #:time.operating-period
    {:end               '(end-of-month [:inputs/aquisition-date]
                                       (* 12 [:inputs/operating-years-remaining]))
     :in-flag           '(and (date> [:time.period/start-date]
                                     [:inputs/aquisition-date])
                              (date<= [:time.period/end-date]
                                      [:end]))
     :first-flag        '(date= [:inputs/aquisition-date]
                                (add-days [:time.period/start-date] -1))
     :last-flag         '(date= [:end]
                                [:time.period/end-date])}

   #:time.contract-year
    {:end-month               '(month-of [:inputs/aquisition-date])
     :year-end-flag           '(= (month-of [:time.period/end-date]) [:end-month])
     :number                  '(if (and [:time.operating-period/in-flag]
                                        [:year-end-flag :prev])
                                 (inc [:number :prev])
                                 [:number :prev])
     :quarter-number          '(if [:time.operating-period/in-flag]
                                 (inc (mod [:quarter-number :prev] 4))
                                 0)}))

;; REVENUE
;;;;;;;;;;;;;;;;;;;;

(def revenue
  #:revenue
   {:compound-degradation         '(when-flag
                                    [:time.operating-period/in-flag]
                                    (/ 1 (Math/pow (inc [:inputs/annual-degradation])
                                                   (dec [:time.contract-year/number]))))
    :seasonality-adjustment       '(case [:time.contract-year/quarter-number]
                                     1 [:inputs/seasonal-adjustments-q1]
                                     2 [:inputs/seasonal-adjustments-q2]
                                     3 [:inputs/seasonal-adjustments-q3]
                                     4 [:inputs/seasonal-adjustments-q4]
                                     0)
    :electricity-generation       '(* [:inputs/year1-p50-yield]
                                      [:inputs/availability]
                                      [:compound-degradation]
                                      [:seasonality-adjustment])
    :revenue-from-generation      '(* 1000
                                      [:electricity-generation]
                                      [:inputs/power-tariff])})

(def costs
  #:om-costs
   {; TODO: Return when escalation factor done
    :escalation-factor     [:placeholder 1]
    :expense               '(when-flag
                             [:time.operating-period/in-flag]
                             (* [:escalation-factor]
                                (/ [:inputs/annual-real-om-cost]
                                   [:inputs/periods-in-year])))})

(def depreciation
  (merge
   #:depreciation
    {:starting-value            '(when-flag
                                  [:time.period/financial-close-flag]
                                  [:inputs/asset-value-at-start])

     :end-of-useful-life        '(end-of-month
                                  [:inputs/aquisition-date]
                                  (* 12 [:inputs/useful-life-of-asset]))

     :in-useful-life-flag       '(and (date> [:time.period/start-date]
                                             [:inputs/aquisition-date])
                                      (date<= [:time.period/end-date]
                                              [:end-of-useful-life]))

     :solar-asset-depreciation  '(when-flag
                                  [:in-useful-life-flag]
                                  (/ [:inputs/asset-value-at-start]
                                     [:inputs/useful-life-of-asset]
                                     [:inputs/periods-in-year]))}

   (fw/corkscrew
    :asset-value
    [:depreciation/starting-value]
    [:depreciation/solar-asset-depreciation])))

(def fs-cashflow
  #:fs.cashflow
   {:cash-from-invoices        [:revenue/revenue-from-generation]
    :om-expense-paid           '(- [:om-costs/expense])
    :share-capital-redemptions '(- [:equity.share-capital/redemption])
    :debt-principal            '(+ [:debt.senior.balance/increase]
                                   [:debt.senior.balance/decrease])
    :debt-interest              '(- [:debt.senior.interest/charge])
    :cash-available-for-dividends '(+ [:cash-from-invoices]
                                      [:om-expense-paid]
                                      [:debt-principal] [:debt-interest]
                                      [:share-capital-redemptions])
    :dividends-paid             '(- [:equity.dividends/dividend-paid])
    :net-cashflow               '(+ [:cash-available-for-dividends] [:dividends-paid])})

(def fs-income
  #:fs.income
   {:revenue               [:revenue/revenue-from-generation]
    :om-expense            '(- [:om-costs/expense])
    :interest              '(- [:debt.senior.interest/charge])
    :depreciation          '(- [:depreciation/solar-asset-depreciation])
    :profit-after-tax      '(+ [:revenue] [:om-expense] [:depreciation]
                               [:interest])
    :dividends-paid        '(- [:equity.dividends/dividend-paid])
    :net-income            '(+ [:profit-after-tax] [:dividends-paid])})

(def fs-balance-sheet-assets
  (fw/add-total
   :total-assets
   #:fs.balance-sheet.assets
    {:retained-cash             [:equity.retained-cash/end]
     :accounts-receivable       [:placeholder 0]
     :solar-asset-value         [:asset-value/end]}))

(def fs
  (merge
   (fw/add-total
    :total-liabilities
    #:fs.balance-sheet.liabilities
     {:retained-earnings         [:equity.retained-earnings/end]
      :senior-debt               [:debt.senior.balance/end]
      :share-capital             [:equity.share-capital.balance/end]})

   #:fs.balance-sheet
    {:balance-check              '(- [:fs.balance-sheet.assets/total-assets]
                                     [:fs.balance-sheet.liabilities/total-liabilities])}))


(def retained-earnings
  (fw/corkscrew
   "equity.retained-earnings"
   [:fs.income/profit-after-tax]
   [:equity.dividends/dividend-paid]))

(def retained-cash
  (fw/corkscrew
   "equity.retained-cash"
   [:fs.cashflow/cash-available-for-dividends]
   [:equity.dividends/dividend-paid]))

(def share-cap-balance
  (fw/corkscrew
   "equity.share-capital.balance"
   [:equity.share-capital/drawdown]
   [:equity.share-capital/redemption]))

(def share-cap
  #:equity.share-capital
   {:drawdown            '(when-flag
                           [:time.period/financial-close-flag]
                           [:inputs/asset-value-at-start])

    :redemption          '(when-flag
                           [:time.period/financial-exit-flag]
                           [:inputs/asset-value-at-start])})

(def dividends
  #:equity.dividends
   {:earnings-available  '(max (+ [:equity.retained-earnings/start]
                                  [:equity.retained-earnings/increase])
                               0)
    :cash-available      '(+ [:equity.retained-cash/start]
                             [:equity.retained-cash/increase])
    :dividend-paid       '(min [:earnings-available] [:cash-available])})

;; Debt
;;;;;;;;;;;;;;;;;;;;;;;;;;

(def sdd
  #:debt.senior
   {:drawdown-amount             '(* [:inputs/senior-debt-gearing]
                                     [:inputs/asset-value-at-start])
    :initial-drawdown            '(when-flag [:time.period/financial-close-flag]
                                             [:drawdown-amount])
    :additional-drawdown         [:placeholder 0]
    :repayment-date              '(end-of-month [:inputs/aquisition-date]
                                                (* [:inputs/senior-debt-repayment-term]
                                                   12))
    :repayment-period-flag       '(and (date> [:time.period/start-date]
                                              [:inputs/aquisition-date])
                                       (date<= [:time.period/end-date]
                                               [:repayment-date]))
    :level-principal-repayment   '(when-flag
                                   [:repayment-period-flag]
                                   (* (/ [:drawdown-amount]
                                         [:inputs/senior-debt-repayment-term]
                                         [:inputs/periods-in-year])))})

(def sdc
  (fw/corkscrew "debt.senior.balance"
                [:debt.senior/initial-drawdown :debt.senior/additional-drawdown]
                [:debt.senior/level-principal-repayment]))

(def sdi
  #:debt.senior.interest
   {:all-in-rate '(+ [:inputs/senior-debt-margin]
                     [:inputs/senior-debt-swap-rate])
    :charge      '(* [:debt.senior.balance/start]
                     (/ [:all-in-rate] [:inputs/periods-in-year]))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Build and run
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def calcs [time-calcs
            revenue costs
            depreciation
            retained-earnings retained-cash
            share-cap share-cap-balance
            dividends
            fs-cashflow fs-income fs-balance-sheet-assets fs
            sdd sdi sdc])

(def model (fw/build-model2 inputs calcs))

(def header :time.period/end-date)
(fw/print-category (time (fw/run-model model 20)) header "fs.balance-sheet" 0 10)

(comment
  (fw/deps-graph model) ;; need to update fn
  (fw/fail-catch (fw/build-model2 inputs calcs))
  (fw/fail-catch (fw/run-model model 10)))

(comment
  (def results (time (fw/run-model model 120)))
  (take 2 results)
  (fw/print-category results header "equity.retained-earnings" 0 10))