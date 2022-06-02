(ns models.charging2
  (:require [fmwk.framework2 :as f :refer [calculation! totalled-calculation! base-case! corkscrew! metadata! check!]]
            [fmwk.utils :refer :all]))

(f/reset-model!)

(base-case!
 "base-case"
 :model-start-date           "2020-12-01"
 :months-in-period            1

 :construction-start-date    "2020-12-31"
 :construction-months        12

 :cost-of-land               50000
 :construction-cost          360000
 :cost-of-equipment          150000

 :first-installment-amount   0.5
 :first-installment-date     "2021-06-30"
 :second-installment-amount  0.5
 :second-installment-date    "2021-12-31"

 :ltv                        0.8
 :interest-rate              0.03
 :repayment-start-date       "2022-01-31"
 :repayment-schedule         9 ;;years

 :initial-cars               1000
 :mini-initial-share         0.1
 :regular-initial-share      0.5
 :premium-initial-share      0.4
 :mini-growth                0.02
 :regular-growth             0.03
 :premium-growth             0.01
 :high-growth-years          5
 :post-2026-growth           0.005

 :mini-battery-capacity       25
 :mini-battery-pct-charged    0.65
 :regular-battery-capacity    50
 :regular-battery-pct-charged 0.55
 :premium-battery-capacity    100
 :premium-battery-pct-charged 0.40

 :inflation-start-date        "2022-12-31"
 :initial-sale-price          0.35
 :initial-cost                0.15
 :inflation-electricity       0.03
 :inflation-store             0.03

 :mini-take-rate       0.15
 :regular-take-rate    0.4
 :premium-take-rate    0.8
 :mini-store-sales     5.0
 :regular-store-sales  10.0
 :premium-store-sales  15.0
 :store-cost-of-sale   0.5

 :employee-start-date      "2022-01-01"
 :employees                9
 :employee-salary          2500 ;monthly
 :social-security          0.2
 :employee-benefits        0.1
 :overheads                3000
 :opex-annual-increase     0.025
 :first-opex-increase-date "2023-01-01")

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

(calculation!
 "period.construction"
 :start-date [:inputs/construction-start-date]
 :end-date   '(add-months [:start-date] [:inputs/construction-months])
 :in-flag    '(and (date>= [:period/start-date]
                           [:start-date])
                   (date<= [:period/end-date]
                           [:end-date]))
 :first-flag       '(date= [:start-date]
                           [:period/start-date])
 :last-flag        '(date= [:end-date]
                           [:period/end-date]))

(calculation!
 "period.operating"
 :start-date '(add-days [:period.construction/end-date] 1)
 :in-flag    '(date>= [:period/start-date]
                      [:start-date])
 :first-flag       '(date= [:start-date]
                           [:period/start-date]))


;; CONSTRUCTION
;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "period.calendar-year"
 :last-flag  '(= 12 (month-of [:period/end-date]))
 :first-flag '(= 1 (month-of [:period/end-date])))

(calculation!
 "construction"
 :first-installment-flag  '(date= [:period/end-date] [:inputs/first-installment-date])
 :second-installment-flag '(date= [:period/end-date] [:inputs/second-installment-date])
 :installation-payment    '(cond [:first-installment-flag]  (* [:inputs/cost-of-equipment] [:inputs/first-installment-amount])
                                 [:second-installment-flag] (* [:inputs/cost-of-equipment] [:inputs/second-installment-amount])
                                 :else 0)
 :land-purchase           '(when-flag [:period/first-period-flag] [:inputs/cost-of-land])
 :construction-payment    '(when-flag [:period.construction/in-flag]
                                      (/ [:inputs/construction-cost]
                                         [:inputs/construction-months]))
 :total                   '(+ [:land-purchase]
                              [:construction-payment]
                              [:installation-payment]))

(metadata!
 "construction"
 :first-installment-flag  {:hidden true}
 :second-installment-flag {:hidden true}
 :installation-payment    {:units :currency :total true}
 :construction-payment    {:units :currency :total true}
 :land-purchase           {:units :currency :total true}
 :total                   {:units :currency :total true})

(corkscrew!
 "ppe"
 :increases [:construction/total])

;; DEBT
;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "debt"
 :drawdown              '(* [:inputs/ltv] [:construction/total])
 :year-frac             '(year-frac-act-365 (add-days [:period/start-date] -1) [:period/end-date])
 :interest              '(* [:debt.balance/start]
                            [:inputs/interest-rate]
                            [:year-frac])
 :last-repayment-date   '(add-months [:inputs/repayment-start-date]
                                     (dec (* 12 [:inputs/repayment-schedule])))
 :total-loan-amount     '(* [:inputs/ltv] (+ [:inputs/construction-cost]
                                             [:inputs/cost-of-land]
                                             [:inputs/cost-of-equipment]))
 :repayment-period-flag '(and (date>= [:period/end-date] [:inputs/repayment-start-date])
                              (date<= [:period/end-date] [:last-repayment-date]))
 :repayment             '(when-flag [:repayment-period-flag]
                                    (/ [:total-loan-amount]
                                       (* 12 [:inputs/repayment-schedule]))))

(metadata!
 "debt"
 :year-frac           {:hidden true}
 :last-repayment-date {:hidden true}
 :total-loan-amount   {:hidden true})

(corkscrew!
 "debt.balance"
 :increases [:debt/drawdown]
 :decreases [:debt/repayment])

;; Operations
;;;;;;;;;;;;;;;;;;;;;

(calculation!
 "car-volumes"
 :period-number         '(dec [:period/number])
 :end-of-high-growth    '(-> [:period.operating/start-date]
                             (add-months (* 12 [:inputs/high-growth-years]))
                             (add-days -1))
 :high-growth-flag      '(and [:period.operating/in-flag]
                              (date<= [:period/end-date]
                                      [:end-of-high-growth]))
 :mini-growth           '(inc (cond (not [:period.operating/in-flag]) 0
                                    [:high-growth-flag] [:inputs/mini-growth]
                                    :else [:inputs/post-2026-growth]))
 :mini-volume           '(if [:period.operating/first-flag]
                           (* [:inputs/initial-cars] [:inputs/mini-initial-share])
                           (* [:mini-growth] [:mini-volume :prev]))
 :regular-growth        '(inc (cond (not [:period.operating/in-flag]) 0
                                    [:high-growth-flag] [:inputs/regular-growth]
                                    :else [:inputs/post-2026-growth]))
 :regular-volume        '(if [:period.operating/first-flag]
                           (* [:inputs/initial-cars] [:inputs/regular-initial-share])
                           (* [:regular-growth] [:regular-volume :prev]))
 :premium-growth        '(inc (cond (not [:period.operating/in-flag]) 0
                                    [:high-growth-flag] [:inputs/premium-growth]
                                    :else [:inputs/post-2026-growth]))
 :premium-volume        '(if [:period.operating/first-flag]
                           (* [:inputs/initial-cars] [:inputs/premium-initial-share])
                           (* [:premium-growth] [:premium-volume :prev])))

(metadata!
 "car-volumes"
 :period-number      {:hidden true}
 :end-of-high-growth {:hidden true}
 :high-growth-flag   {:hidden true}

 :mini-growth    {:units :percent}
 :regular-growth {:units :percent}
 :premium-growth {:units :percent})

(totalled-calculation!
 "sales.electricity" :total-kwh
 :mini    '(* [:car-volumes/mini-volume]
              [:inputs/mini-battery-capacity]
              [:inputs/mini-battery-pct-charged])
 :regular '(* [:car-volumes/regular-volume] [:inputs/regular-battery-capacity] [:inputs/regular-battery-pct-charged])
 :premium '(* [:car-volumes/premium-volume] [:inputs/premium-battery-capacity] [:inputs/premium-battery-pct-charged]))

(calculation!
 "inflation"
 :increase-flag            '(and [:period.calendar-year/first-flag]
                                 (date> [:period/end-date]
                                        [:inputs/inflation-start-date]))
 :increase-electricity     '(when-flag [:increase-flag]
                                       [:inputs/inflation-electricity])
 :compound-electricity     '(if [:period/first-period-flag] 1
                                (* [:compound-electricity :prev] (inc [:increase-electricity])))
 :increase-store           '(when-flag [:increase-flag]
                                       [:inputs/inflation-store])
 :compound-store           '(if [:period/first-period-flag] 1
                                (* [:compound-store :prev] (inc [:increase-store]))))

(metadata!
 "inflation"
 :increase-electricity {:units :percent :total true}
 :compound-electricity {:units :percent}
 :increase-store       {:units :percent :total true}
 :compound-store       {:units :percent})

(calculation!
 ;; TODO: Inflation
 "sales.prices"
 :electricity-retail    '(* [:inflation/compound-electricity] [:inputs/initial-sale-price])
 :electricity-wholesale '(* [:inflation/compound-electricity] [:inputs/initial-cost]))

(metadata!
 "sales.prices"
 :inflation-increase    {:units :percent}
 :electricity-retail    {:units :currency-cents}
 :electricity-wholesale {:units :currency-cents})

(calculation!
 "operating-costs"
 :increase-flag            '(and [:period.calendar-year/first-flag]
                                 (date> [:period/end-date]
                                        [:inputs/first-opex-increase-date]))
 :increase                 '(when-flag [:increase-flag]
                                       [:inputs/opex-annual-increase])
 :compound-increase        '(if [:period/first-period-flag] 1
                                (* [:compound-increase :prev] (inc [:increase])))
 :employees                '(when-flag [:period.operating/in-flag] [:inputs/employees])
 :wage-per-employee        '(* [:compound-increase] [:inputs/employee-salary])
 :social-security          '(* [:inputs/social-security] [:wage-per-employee])
 :employee-benefits        '(* [:inputs/employee-benefits] [:wage-per-employee])
 :employee-cost            '(* [:employees] (+ [:wage-per-employee] [:social-security] [:employee-benefits]))
 :overhead                 '(when-flag [:period.operating/in-flag] (* [:inputs/overheads] [:compound-increase])))

(metadata!
 "operating-costs"
 :increase-flag     {:hidden true}
 :increase          {:hidden true}
 :compound-increase {:units :percent})

(totalled-calculation!
 "sales.income" :total
 :electricity-revenue '(* [:sales.electricity/total-kwh] [:sales.prices/electricity-retail])
 :electricity-cos     '(- (* [:sales.electricity/total-kwh] [:sales.prices/electricity-wholesale]))
 :store-mini          '(* [:car-volumes/mini-volume]    [:inputs/mini-take-rate] [:inflation/compound-store] [:inputs/mini-store-sales])
 :store-regular       '(* [:car-volumes/regular-volume] [:inputs/regular-take-rate] [:inflation/compound-store] [:inputs/regular-store-sales])
 :store-premium       '(* [:car-volumes/premium-volume] [:inputs/premium-take-rate] [:inflation/compound-store] [:inputs/premium-store-sales]))


;; FS
;;;;;;;;;;;;;;;;;;;;;

(totalled-calculation!
 "fs.cashflow" :net-cashflow
 :construction-costs '(- [:construction/total])
 :income             [:sales.income/total]
 :labor              '(- [:operating-costs/employee-cost])
 :overhead           '(- [:operating-costs/overhead])
 :net-debt-drawdowns '(+ [:debt.balance/increase] [:debt.balance/decrease])
 :interest-paid      '(- [:debt/interest]))

(metadata!
 "fs.cashflow"
 :construction-costs {:total true}
 :net-debt-drawdowns {:total true}
 :interest-paid      {:total true}
 :net-cashflow       {:total true})

(corkscrew!
 "fs.cashflow.retained-cash"
 :increases [:fs.cashflow/net-cashflow])

(totalled-calculation!
 "fs.income" :PAT
 :net-revenue          [:sales.income/total]
 :labor                '(- [:operating-costs/employee-cost])
 :overhead             '(- [:operating-costs/overhead])
 :interest-expense     '(- [:debt/interest]))

(metadata!
 "fs.income"
 :interest-expense {:total true}
 :PAT              {:total true})

(corkscrew!
 "fs.income.retained-earnings"
 :increases [:fs.income/PAT])

(totalled-calculation!
 "fs.balance-sheet.assets" :TOTAL
 :cash               [:fs.cashflow.retained-cash/end]
 :non-current-assets [:ppe/end])

(totalled-calculation!
 "fs.balance-sheet.liabilities" :TOTAL
 :debt              [:debt.balance/end]
 :share-capital     [:placeholder 0]
 :retained-earnings [:fs.income.retained-earnings/end])

(check!
 :balance-sheet-balances
 '(= (Math/round (float [:fs.balance-sheet.assets/TOTAL]))
     (Math/round (float [:fs.balance-sheet.liabilities/TOTAL]))))

(def model (f/compile-model!))
(def results (time (f/run-model model 30)))
(f/print-category results (:meta model) :period/end-date  "fs" 20 30)