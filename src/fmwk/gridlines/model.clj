(ns fmwk.gridlines.model
  (:require [fmwk.framework2 :as fw]
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
    :useful-life-of-asset       25})

(def time-calcs
  (merge
   #:time.period{:start-date '(if (true? [:flags/first-model-column])
                                [:inputs/model-start-date]
                                (add-days [:end-date :prev] 1))
                 :end-date   '(add-days (add-months [:start-date]
                                                    [:inputs/length-of-operating-period])
                                        -1)}
   #:time
    {:model-column-number     '(inc [:model-column-number :prev])
     :end-of-operating-period '(end-of-month [:inputs/aquisition-date]
                                             (* 12 [:inputs/operating-years-remaining]))}

   #:time.contract-year {:end-month      '(month-of [:inputs/aquisition-date])
                         :year-end-flag  '(= (month-of [:time.period/end-date]) [:end-month])
                         :number         '(if (and [:flags/operating-period] [:year-end-flag :prev])
                                            (inc [:number :prev])
                                            [:number :prev])
                         :quarter-number '(if [:flags/operating-period]
                                            (inc (mod [:quarter-number :prev] 4))
                                            0)}))

(def flags
  #:flags{:first-model-column '(= 1 [:time/model-column-number])
          :financial-close-period '(and (date>= [:inputs/aquisition-date]
                                                [:time.period/start-date])
                                        (date<= [:inputs/aquisition-date]
                                                [:time.period/end-date]))
          :financial-exit-period '(and (date>= [:time/end-of-operating-period]
                                               [:time.period/start-date])
                                       (date<= [:time/end-of-operating-period]
                                               [:time.period/end-date]))
          :operating-period '(and (date> [:time.period/start-date]
                                         [:inputs/aquisition-date])
                                  (date<= [:time.period/end-date]
                                          [:time/end-of-operating-period]))
          :first-operating-period  '(date= [:inputs/aquisition-date]
                                           (add-days [:time.period/start-date] -1))
          :last-operating-period  '(date= [:time/end-of-operating-period]
                                          [:time.period/end-date])})

(def revenue
  #:revenue{:compound-degradation '(when-flag [:flags/operating-period]
                                              (/ 1 (Math/pow (inc [:inputs/annual-degradation]) (dec [:time.contract-year/number]))))
            :seasonality-adjustment '(case [:time.contract-year/quarter-number]
                                       1 [:inputs/seasonal-adjustments-q1]
                                       2 [:inputs/seasonal-adjustments-q2]
                                       3 [:inputs/seasonal-adjustments-q3]
                                       4 [:inputs/seasonal-adjustments-q4]
                                       0)
            :electricity-generation '(* [:inputs/year1-p50-yield]
                                        [:inputs/availability]
                                        [:compound-degradation]
                                        [:seasonality-adjustment])
            :revenue-from-generation '(* 1000
                                         [:electricity-generation]
                                         [:inputs/power-tariff])})

(def costs
  #:om-costs{; TODO: Return when escalation factor done
             ; TODOL Put this on FS
             :escalation-factor     [:placeholder 1]
             :expense               '(when-flag [:flags/operating-period]
                                                (* [:escalation-factor]
                                                   (/ [:inputs/annual-real-om-cost]
                                                      [:inputs/periods-in-year])))})

(def depreciaion
  (merge #:depreciation{:starting-value            '(when-flag [:flags/financial-close-period]
                                                               [:inputs/asset-value-at-start])
                        :end-of-useful-life        '(end-of-month [:inputs/aquisition-date]
                                                                  (* 12 [:inputs/useful-life-of-asset]))
                        :in-useful-life-flag       '(and (date> [:time.period/start-date]
                                                                [:inputs/aquisition-date])
                                                         (date<= [:time.period/end-date]
                                                                 [:end-of-useful-life]))
                        :solar-asset-depreciation  '(when-flag [:in-useful-life-flag]
                                                               (/ [:inputs/asset-value-at-start]
                                                                  [:inputs/useful-life-of-asset]
                                                                  [:inputs/periods-in-year]))}
         (fw/corkscrew :asset-value
                       [:depreciation/starting-value]
                       [:depreciation/solar-asset-depreciation])))

(def fs
  (merge (fw/add-total :net-cashflow #:fs.cashflow{:cash-from-invoices [:revenue/revenue-from-generation]
                                                   :dividends-paid [:placeholder 0]
                                                   :share-capital-redemptions '(- [:equity.share-capital/redemption])})

         (fw/add-total :profit-after-tax #:fs.income{:revenue [:revenue/revenue-from-generation]
                                                     :depreciation '(- [:depreciation/solar-asset-depreciation])})

         (fw/add-total :total-assets #:fs.balance-sheet.assets{:retained-cash [:equity.retained-cash/end]
                                                               :accounts-receivable [:placeholder 0]
                                                               :solar-asset-value [:asset-value/end]})
         (fw/add-total :total-liabilities #:fs.balance-sheet.liabilities{:retained-earnings [:equity.retained-earnings/end]
                                                                         :share-capital     [:equity.share-capital.balance/end]})
         {:fs.balance-sheet/balance-check '(- [:fs.balance-sheet.assets/total-assets] [:fs.balance-sheet.liabilities/total-liabilities])}))

(def equity
  (merge (fw/corkscrew "equity.retained-earnings"
                       [:fs.income/profit-after-tax]
                       [:fs.cashflow/dividends-paid])
         (fw/corkscrew "equity.retained-cash"
                       [:fs.cashflow/net-cashflow]
                       [:fs.cashflow/dividends-paid])
         #:equity.share-capital{:drawdown '(when-flag [:flags/financial-close-period] [:inputs/asset-value-at-start])
                                :redemption '(when-flag [:flags/financial-exit-period] [:inputs/asset-value-at-start])}
         (fw/corkscrew "equity.share-capital.balance"
                       [:equity.share-capital/drawdown]
                       [:equity.share-capital/redemption])))

;; Build and run

(def model (fw/build-and-validate-model
            inputs
            [time-calcs flags
             revenue costs
             depreciaion
             fs equity]))

(comment
  (fw/fail-catch (fw/build-and-validate-model
                  inputs
                  [time-calcs flags
                   fs equity]))


  (fw/fail-catch (fw/run-model model 10)))

(defn print-calcs [results qualifiers period-range]
  (doseq [q qualifiers]
    (fw/slice-results results q period-range)))

(defn run-sheets [model sheets period-range]
  (let [results (time (fw/run-model-for-rows model (last period-range) (mapcat #(fw/rows-in-sheet model %) sheets)))]
    (print-calcs results
                 sheets
                 period-range)))

(run-sheets model ["fs.balance-sheet" "equity.share-capital.balance"] [1 10])

(comment
  (def results (time (fw/run-model model 120)))
  (take 20 (drop 90 (map (comp fw/round :equity.retained-cash/end) results)))
  (take 20 (drop 90 (map (comp fw/round :equity.retained-earnings/end) results))))