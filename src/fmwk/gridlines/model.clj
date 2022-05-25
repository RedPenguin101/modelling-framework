(ns fmwk.gridlines.model
  (:require [fmwk.framework :as fw]
            [fmwk.simple-viz.main :refer [series-scatter series-line series-lines]]
            [fmwk.utils :refer :all]
            [fmwk.table-runner :as tr]))

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
    :useful-life-of-asset       25})

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



(def fs
  (merge
   (fw/add-total
    :cash-available-for-dividends
    #:fs.cashflow
     {:cash-from-invoices        [:revenue/revenue-from-generation]
      :om-expense-paid           '(- [:om-costs/expense])
      :share-capital-redemptions '(- [:equity.share-capital/redemption])})

   #:fs.cashflow
    {:dividends-paid             '(- [:equity.dividends/dividend-paid])
     :net-cashflow               '(+ [:cash-available-for-dividends] [:dividends-paid])}

   (fw/add-total
    :profit-after-tax
    #:fs.income
     {:revenue                   [:revenue/revenue-from-generation]
      :om-expense                '(- [:om-costs/expense])
      :depreciation              '(- [:depreciation/solar-asset-depreciation])})

   #:fs.income
    {:dividends-paid             '(- [:equity.dividends/dividend-paid])
     :net-income                 '(+ [:profit-after-tax] [:dividends-paid])}

   (fw/add-total
    :total-assets
    #:fs.balance-sheet.assets
     {:retained-cash             [:equity.retained-cash/end]
      :accounts-receivable       [:placeholder 0]
      :solar-asset-value         [:asset-value/end]})

   (fw/add-total
    :total-liabilities
    #:fs.balance-sheet.liabilities
     {:retained-earnings         [:equity.retained-earnings/end]
      :share-capital             [:equity.share-capital.balance/end]})

   #:fs.balance-sheet
    {:balance-check              '(- [:fs.balance-sheet.assets/total-assets]
                                     [:fs.balance-sheet.liabilities/total-liabilities])}))

(def equity
  (merge
   (fw/corkscrew
    "equity.retained-earnings"
    [:fs.income/profit-after-tax]
    [:equity.dividends/dividend-paid])

   (fw/corkscrew
    "equity.retained-cash"
    [:fs.cashflow/cash-available-for-dividends]
    [:equity.dividends/dividend-paid])

   #:equity.share-capital
    {:drawdown            '(when-flag
                            [:time.period/financial-close-flag]
                            [:inputs/asset-value-at-start])

     :redemption          '(when-flag
                            [:time.period/financial-exit-flag]
                            [:inputs/asset-value-at-start])}

   (fw/corkscrew
    "equity.share-capital.balance"
    [:equity.share-capital/drawdown]
    [:equity.share-capital/redemption])

   #:equity.dividends
    {:earnings-available  '(max (+ [:equity.retained-earnings/start]
                                   [:equity.retained-earnings/increase])
                                0)
     :cash-available      '(+ [:equity.retained-cash/start]
                              [:equity.retained-cash/increase])
     :dividend-paid       '(min [:earnings-available] [:cash-available])}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Build and run
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def model (fw/build-and-validate-model
            inputs
            [time-calcs
             revenue costs
             depreciation
             fs equity]))

(comment
  (fw/deps-graph model)
  (fw/deps-graph (:data (fw/fail-catch (fw/build-and-validate-model
                                        inputs
                                        [time-calcs flags
                                         fs equity]))))


  (fw/fail-catch (fw/run-model model 10)))


(comment
  (def results2 (time (tr/run-model-table (fw/calculate-order model) model 120)))
  results2)