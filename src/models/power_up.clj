(ns models.power-up
  (:require [fmwk.framework :as fw]
            [fmwk.utils :refer :all]))

(def contracts)

(def inputs
  #:inputs
   {:model-start-date "2021-01-01"
    :aquisition-date "2020-12-31"
    :sale-date "2035-12-31"
    :length-of-operating-period 1
    :overhead-per-month 500000
    :advance-payment 0.1
    :holdback 0.1
    :holdback-release-amount 2500000
    :holdback-release-date "2021-06-30"
    :existing-ppe-depreciation 3 ;years
    :new-ppe-depreciation      5 ;years
    })

(def periods
  #:period
   {:start-date               '(if (true? [:first-model-column])
                                 [:inputs/model-start-date]
                                 (add-days [:end-date :prev] 1))
    :end-date                 '(add-days
                                (add-months [:start-date]
                                            [:inputs/length-of-operating-period])
                                -1)
    :number                   '(inc [:number :prev])
    :first-model-column       '(= 1 [:number])})

(def ebitda
  (fw/add-total :EBITDA
                #:income.EBITDA
                 {:revenues [:placeholder 0]
                  :materials [:placeholder 0]
                  :labor [:placeholder 0]
                  :overhead [:placeholder 0]}))

(def net-profit
  (fw/add-total :net-profit
                #:income
                 {:EBITDA [:income.EBITDA/EBITDA]
                  :depreciation [:placeholder 0]
                  :interest [:placeholder 0]}))

(def bs-assets
  (fw/add-total :total-assets
                #:balance-sheet.assets
                 {:cash [:placeholder 0]
                  :inventory [:placeholder 0]
                  :accounts-recievable [:placeholder 0]
                  :holdbacks [:placeholder 5000000]
                  :ppe [:placeholder 3600000]}))

(def bs-liabs
  (fw/add-total
   :total-liabilities
   #:balance-sheet.liabilities
    {:accounts-payable [:placeholder 0]
     :advances [:placeholder 0]
     :rcf [:placeholder 2500000]
     :leasing [:placeholder 0]
     :share-capital [:placeholder 1000000]
     :retained-earnings [:placeholder 5100000]}))

(def contract-cfs
  ;; Materials	Salaries	OVH & Profit
  [[2890976  3395956  1304539]
   [8436341  8916972  3600813]
   [7078921  6821887  2884417]
   [9543584  12290519 4530576]
   [10637867 11845677 4665336]
   [16935098 19125335 7482540]
   [4193313  5262673  1962117]
   [9373739  5521378  3090737]
   [6011073  7906117  2887817]
   [3928424  3068321  1451825]])

(def contract-totals
  (mapv #(apply + %) contract-cfs))

(def contract-completions
  [[0.00 0.00 0.00 0.00 0.00 0.00 0.10 0.20 0.25 0.25 0.20 0.00]
   [0.00 0.05 0.10 0.15 0.20 0.20 0.20 0.10 0.00 0.00 0.00 0.00]
   [0.00 0.00 0.00 0.05 0.10 0.15 0.20 0.20 0.20 0.10 0.00 0.00]
   [0.00 0.00 0.00 0.00 0.05 0.10 0.15 0.20 0.20 0.20 0.10 0.00]
   [0.00 0.00 0.05 0.10 0.15 0.20 0.20 0.20 0.10 0.00 0.00 0.00]
   [0.00 0.05 0.08 0.10 0.15 0.15 0.15 0.15 0.15 0.02 0.00 0.00]
   [0.00 0.00 0.00 0.00 0.00 0.00 0.10 0.20 0.25 0.25 0.20 0.00]
   [0.00 0.00 0.00 0.05 0.10 0.15 0.20 0.20 0.20 0.10 0.00 0.00]
   [0.00 0.00 0.00 0.00 0.05 0.10 0.15 0.20 0.20 0.20 0.10 0.00]
   [0.00 0.10 0.20 0.25 0.25 0.20 0.00 0.00 0.00 0.00 0.00 0.00]])

(def contract-advance-months
  (mapv #(- 12 (count (drop-while zero? %))) contract-completions))

(def contract-revenues
  (mapv (fn [total compl] (mapv (partial * total) compl)) contract-totals contract-completions))

(def cashflow
  (fw/add-total
   :from-operations
   #:cashflows
    {:advances [:placeholder 0]
     :contract-revenue [:placeholder 0]
     :holdbacks '(if (date= [:inputs/holdback-release-date]
                            [:period/end-date])
                   [:inputs/holdback-release-amount]
                   0)}))

(def bs-check {:balance-sheet/check '(- [:balance-sheet.assets/total-assets]
                                        [:balance-sheet.liabilities/total-liabilities])})

(def bs-meta (fw/add-meta (merge bs-assets bs-liabs) {:units :currency-thousands}))

(def calcs [periods bs-assets bs-liabs bs-check
            ebitda net-profit
            cashflow])
(fw/fail-catch (fw/build-model2 inputs calcs))
(def model (fw/build-model2 inputs calcs [bs-meta]))
(def header :period/end-date)
(def results (fw/run-model model 20))
(fw/print-category results (:meta model) header "cashflows" 1 13)
