(ns models.charging
  (:require [fmwk.framework :as fw]
            [fmwk.utils :refer :all]))

(def inputs
  #:inputs
   {:model-start-date           "2020-12-01"
    :aquisition-date            "2020-12-31"
    :sale-date                  "2035-12-31"
    :operating-period-start     "2022-01-01"
    :length-of-operating-period 1

    :cost-of-land               50000
    :construction-cost          360000
    :construction-length        12
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
    :first-opex-increase-date "2023-01-01"})

;; TIME
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
    :first-model-column       '(= 1 [:number])
    :sale-date                [:inputs/sale-date]
    :financial-close-flag     '(and (date>= [:inputs/aquisition-date]
                                            [:start-date])
                                    (date<= [:inputs/aquisition-date]
                                            [:end-date]))
    :financial-exit-flag      '(and (date>= [:sale-date]
                                            [:start-date])
                                    (date<= [:sale-date]
                                            [:end-date]))})

(def op-period
  #:operating-period
   {:end               [:inputs/sale-date]
    :in-flag           '(and (date>= [:period/start-date]
                                     [:inputs/operating-period-start])
                             (date<= [:period/end-date]
                                     [:end]))
    :first-flag        '(date= [:inputs/operating-period-start]
                               [:period/start-date])
    :last-flag         '(date= [:end]
                               [:period/end-date])})

(def calendar-period
  #:calendar-period
   {:last-period-flag  '(= 12 (month-of [:period/end-date]))
    :first-period-flag '(= 1 (month-of [:period/end-date]))})


;; Construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def construction-costs
  #:construction
   {:first-installment-flag  '(date= [:period/end-date] [:inputs/first-installment-date])
    :second-installment-flag '(date= [:period/end-date] [:inputs/second-installment-date])
    :installation-payment    '(cond [:first-installment-flag]  (* [:inputs/cost-of-equipment] [:inputs/first-installment-amount])
                                    [:second-installment-flag] (* [:inputs/cost-of-equipment] [:inputs/second-installment-amount])
                                    :else 0)
    :land-purchase           '(when-flag [:period/first-model-column] [:inputs/cost-of-land])
    :construction-end-date   '(add-months [:inputs/aquisition-date] [:inputs/construction-length])
    :construction-flag       '(and (date> [:period/start-date]
                                          [:inputs/aquisition-date])
                                   (date<= [:period/end-date]
                                           [:construction-end-date]))
    :construction-payment    '(when-flag [:construction-flag] (/ [:inputs/construction-cost] [:inputs/construction-length]))
    :total-construction-payments '(+ [:land-purchase] [:construction-payment] [:installation-payment])})

(def construction-meta
  #:construction
   {:installation-payment        {:units :currency :total true}
    :construction-payment        {:units :currency :total true}
    :land-purchase               {:units :currency :total true}
    :total-construction-payments {:units :currency :total true}})

(def nca-corkscrew
  (fw/corkscrew "ppe"
                [:construction/total-construction-payments]
                []))

;; DEBT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def debt
  #:debt
   {:construction-payments [:construction/total-construction-payments]
    :drawdown              '(* [:inputs/ltv] [:construction-payments])
    :year-frac             '(year-frac-act-365 (add-days [:period/start-date] -1) [:period/end-date])
    :interest              '(* [:debt.balance/start] [:inputs/interest-rate] [:year-frac])
    :last-repayment-date   '(add-months [:inputs/repayment-start-date] (dec (* 12 [:inputs/repayment-schedule])))
    :total-loan-amount     '(* [:inputs/ltv] (+ [:inputs/construction-cost] [:inputs/cost-of-land] [:inputs/cost-of-equipment]))
    :repayment-period-flag '(and (date>= [:period/end-date] [:inputs/repayment-start-date])
                                 (date<= [:period/end-date] [:last-repayment-date]))
    :repayment             '(when-flag [:repayment-period-flag]
                                       (/ [:total-loan-amount]
                                          (* 12 [:inputs/repayment-schedule])))})

(def debt-balance
  (fw/corkscrew "debt.balance"
                [:debt/drawdown]
                [:debt/repayment]))

(def debt-meta
  (merge #:debt{:construction-payments {:units :currency :total true}
                :debt/drawdown         {:units :currency :total true}
                :repayment             {:units :currency :total true}
                :year-frac             {:units :percent}}
         #:debt.balance{:start    {:units :currency}
                        :increase {:units :currency :total true}
                        :decrease {:units :currency :total true}
                        :end      {:units :currency}}))

;; Equity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def equity
  #:equity
   {:drawdown '(max 0 (- [:fs.cashflows/cashflow-before-subsidy]))})

;; ECars
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ecars
  (array-map
   :ecars.volumes/period-number         '(dec [:period/number])
   :ecars.volumes/end-of-high-growth    '(add-months [:inputs/operating-period-start] (* 12 [:inputs/high-growth-years]))
   :ecars.volumes/high-growth-flag      '(and [:operating-period/in-flag]
                                              (date< [:period/end-date]
                                                     [:end-of-high-growth]))
   :ecars.volumes/mini-growth           '(inc (cond (not [:operating-period/in-flag]) 0
                                                    [:high-growth-flag] [:inputs/mini-growth]
                                                    :else [:inputs/post-2026-growth]))
   :ecars.volumes/mini-volume           '(if [:operating-period/first-flag]
                                           (* [:inputs/initial-cars] [:inputs/mini-initial-share])
                                           (* [:mini-growth] [:mini-volume :prev]))
   :ecars.volumes/regular-growth           '(inc (cond (not [:operating-period/in-flag]) 0
                                                       [:high-growth-flag] [:inputs/regular-growth]
                                                       :else [:inputs/post-2026-growth]))
   :ecars.volumes/regular-volume           '(if [:operating-period/first-flag]
                                              (* [:inputs/initial-cars] [:inputs/regular-initial-share])
                                              (* [:regular-growth] [:regular-volume :prev]))
   :ecars.volumes/premium-growth           '(inc (cond (not [:operating-period/in-flag]) 0
                                                       [:high-growth-flag] [:inputs/premium-growth]
                                                       :else [:inputs/post-2026-growth]))
   :ecars.volumes/premium-volume           '(if [:operating-period/first-flag]
                                              (* [:inputs/initial-cars] [:inputs/premium-initial-share])
                                              (* [:premium-growth] [:premium-volume :prev]))))

(def ecars-meta
  #:ecars.volumes
   {:mini-growth {:units :percent}
    :regular-growth {:units :percent}
    :premium-growth {:units :percent}})

;; Sales
;;;;;;;;;;;;;;;;;;;;;;;;;;

(def inflation
  #:inflation
   {:increase '(if (and [:calendar-period/first-period-flag]
                        (date> [:period/end-date]
                               [:inputs/inflation-start-date]))
                 (inc [:inputs/inflation-electricity])
                 1.0)})

(def inflation-meta
  {:inflation/increase {:units :percent}})

(def sales
  (fw/add-total
   :total
   #:electricity-sales
    {:mini    '(* [:ecars.volumes/mini-volume] [:inputs/mini-battery-capacity] [:inputs/mini-battery-pct-charged])
     :regular '(* [:ecars.volumes/regular-volume] [:inputs/regular-battery-capacity] [:inputs/regular-battery-pct-charged])
     :premium '(* [:ecars.volumes/premium-volume] [:inputs/premium-battery-capacity] [:inputs/premium-battery-pct-charged])}))

(def revenue
  #:electricity-revenue
   {:sale-price     '(if [:period/first-model-column]
                       [:inputs/initial-sale-price]
                       (* [:sale-price :prev] [:inflation/increase]))
    :cost-price     '(if [:period/first-model-column]
                       [:inputs/initial-cost]
                       (* [:cost-price :prev] [:inflation/increase]))
    :revenue        '(* [:electricity-sales/total] [:sale-price])
    :cost-of-sales  '(* [:electricity-sales/total] [:cost-price])})


(def store-sales
  #:store-sales
   {:mini-spend     '(if [:period/first-model-column]
                       [:inputs/mini-store-sales]
                       (* [:mini-spend :prev] [:inflation/increase]))
    :regular-spend  '(if [:period/first-model-column]
                       [:inputs/regular-store-sales]
                       (* [:regular-spend :prev] [:inflation/increase]))
    :premium-spend  '(if [:period/first-model-column]
                       [:inputs/premium-store-sales]
                       (* [:premium-spend :prev] [:inflation/increase]))

    :mini     '(* [:ecars.volumes/mini-volume]    [:inputs/mini-take-rate] [:mini-spend])
    :regular  '(* [:ecars.volumes/regular-volume] [:inputs/regular-take-rate] [:regular-spend])
    :premium  '(* [:ecars.volumes/premium-volume] [:inputs/premium-take-rate] [:premium-spend])
    :revenue  '(+ [:mini] [:regular] [:premium])
    :costs    '(* [:inputs/store-cost-of-sale] [:revenue])})

(def operating-costs
  #:operating-costs
   {:opex-increase     '(if (and [:calendar-period/first-period-flag]
                                 (date> [:period/end-date]
                                        [:inputs/first-opex-increase-date]))
                          (inc [:inputs/opex-annual-increase])
                          1.0)
    :employees         '(when-flag [:operating-period/in-flag] [:inputs/employees])
    :employee-wage     '(if [:operating-period/first-flag]
                          [:inputs/employee-salary]
                          (* [:employee-wage :prev] [:opex-increase]))
    :social-security   '(* [:inputs/social-security] [:employee-wage])
    :employee-benefits '(* [:inputs/employee-benefits] [:employee-wage])
    :cost-per-employee '(+ [:employee-wage] [:social-security] [:employee-benefits])
    :employee-expense  '(* [:employees] [:cost-per-employee])
    :overheads         '(if [:operating-period/first-flag]
                          [:inputs/overheads]
                          (* [:overheads :prev] [:opex-increase]))})

;; FINANCIAL STATEMENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def income
  (array-map
   :fs.income/revenues           '(+ [:store-sales/revenue] [:electricity-revenue/revenue])
   :fs.income/cost-of-sales      '(- (+ [:store-sales/costs] [:electricity-revenue/cost-of-sales]))
   :fs.income/labor              '(- [:operating-costs/employee-expense])
   :fs.income/overheads          '(- [:operating-costs/overheads])
   :fs.income/EBITDA             '(+ [:revenues] [:cost-of-sales] [:labor] [:overheads])
   :fs.income/depreciation       [:placeholder 0]
   :fs.income/interest           '(- [:debt/interest])
   :fs.income/PBT                '(+ [:EBITDA] [:depreciation] [:interest])
   :fs.income/tax-expense        [:placeholder 0] ;; TODO put in a check for placeholders without 2nd element
   :fs.income/PAT                '(+ [:PBT] [:tax-expense])))

(def income-meta (fw/add-meta income {:units :currency :total true}))

(def cashflow
  (array-map
   :fs.cashflows/invoices                             '(+ [:store-sales/revenue] [:electricity-revenue/revenue])
   :fs.cashflows/invoices-paid                        '(- (+ [:operating-costs/overheads]
                                                             [:store-sales/costs]
                                                             [:electricity-revenue/cost-of-sales]))
   :fs.cashflows/wages                                '(- [:operating-costs/employee-expense])
   :fs.cashflows/tax-paid                             [:placeholder 0]
   :fs.cashflows/construction                         '(- [:construction/total-construction-payments])
   :fs.cashflows/cashflow-available-for-debt-service  '(+ [:invoices] [:invoices-paid] [:wages] [:tax-paid]
                                                          [:construction])
   :fs.cashflows/interest-paid                        '(- [:debt/interest])
   :fs.cashflows/debt-facility-drawdown               '(- [:debt/drawdown] [:debt/repayment])
   :fs.cashflows/cashflow-before-subsidy              '(+ [:cashflow-available-for-debt-service]
                                                          [:interest-paid]
                                                          [:debt-facility-drawdown])
   :fs.cashflows/subsidy                              [:equity/drawdown]
   :fs.cashflows/dividends-paid                       [:placeholder 0]
   :fs.cashflows/net-cashflow                         '(+ [:cashflow-before-subsidy]
                                                          [:subsidy]
                                                          [:dividends-paid])))

(def cashflow-meta (fw/add-meta cashflow {:units :currency :total true}))

(def bs-assets
  (fw/add-total
   :TOTAL-ASSETS
   #:fs.balance-sheet.assets
    {:cash                '(+ [:cash :prev] [:fs.cashflows/net-cashflow])
     :accounts-receivable [:placeholder 0]
     :non-current-assets  [:ppe/end]}))

(def bs-liabs
  (fw/add-total
   :TOTAL-LIABILITIES
   #:fs.balance-sheet.liabilities
    {:accounts-payable  [:placeholder 0]
     :debt              [:debt.balance/end]
     :share-capital     '(+ [:share-capital :prev] [:equity/drawdown])
     :retained-earnings '(+ [:retained-earnings :prev] [:fs.income/PAT])}))

(def bs-check #:fs.balance-sheet.checks
               {:balance '(- [:fs.balance-sheet.assets/TOTAL-ASSETS]
                             [:fs.balance-sheet.liabilities/TOTAL-LIABILITIES])})

(def bs-meta (fw/add-meta (merge bs-assets bs-liabs) {:units :currency}))

(def calcs [periods op-period calendar-period
            income cashflow bs-assets bs-liabs bs-check
            construction-costs nca-corkscrew
            debt debt-balance equity
            inflation
            ecars sales revenue store-sales
            operating-costs])
(def metadata [income-meta cashflow-meta bs-meta
               construction-meta debt-meta
               ecars-meta
               inflation-meta])

(fw/fail-catch (fw/build-model2 inputs calcs metadata))
(def model (fw/build-model2 inputs calcs metadata))
(def header :period/end-date)

(def results (time (fw/run-model model 120)))

(fw/print-category results (:meta model) header "fs" (inc (* 12 5)) (+ (* 12 5) 14))
