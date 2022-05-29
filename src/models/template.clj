(ns models.template
  (:require [fmwk.framework :as fw]
            [fmwk.utils :refer :all]))

(def inputs
  #:inputs
   {:model-start-date           "2020-01-01"
    :aquisition-date            "2020-12-31"
    :sale-date                  "2035-12-31"
    :length-of-operating-period 12})

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
    :in-flag           '(and (date> [:period/start-date]
                                    [:inputs/aquisition-date])
                             (date<= [:period/end-date]
                                     [:end]))
    :first-flag        '(date= [:inputs/aquisition-date]
                               (add-days [:period/start-date] -1))
    :last-flag         '(date= [:end]
                               [:period/end-date])})


;; FINANCIAL STATEMENTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def income
  #:income
   {:revenues           [:placeholder 0]
    :operating-expenses [:placeholder 0]
    :overheads          [:placeholder 0]
    :EBITDA             '(+ [:revenues] [:operating-expenses] [:overheads])
    :depreciation       [:placeholder 0]
    :interest           [:placeholder 0]
    :pbt                '(+ [:EBITDA] [:depreciation] [:interest])
    :tax-expense        [:placeholder 0] ;; TODO put in a check for placeholders without 2nd element
    :pat                '(+ [:pbt] [:tax-expense])})

(def income-meta (fw/add-meta income {:units :currency :total true}))

(def cashflow
  (array-map
   :cashflows/invoices                             [:placeholder 0]
   :cashflows/operating-costs                      [:placeholder 0]
   :cashflows/tax-paid                             [:placeholder 0]
   :cashflows/cashflow-available-for-debt-service  '(+ [:invoices] [:operating-costs] [:tax-paid])
   :cashflows/interest-paid                        [:placeholder 0]
   :cashflows/debt-facility-drawdown               [:placeholder 0]
   :cashflows/cashflow-available-for-equity        '(+ [:cashflow-available-for-debt-service]
                                                       [:interest-paid]
                                                       [:debt-facility-drawdown])
   :cashflows/dividends-paid                       [:placeholder 0]
   :cashflows/net-cashflow                         '(+ [:cashflow-available-for-equity]
                                                       [:dividends-paid])))

(def cashflow-meta (fw/add-meta cashflow {:units :currency :total true}))

(def bs-assets
  (fw/add-total
   :TOTAL-ASSETS
   #:balance-sheet.assets
    {:cash                [:placeholder 0]
     :accounts-receivable [:placeholder 0]
     :non-current-assets  [:placeholder 0]}))

(def bs-liabs
  (fw/add-total
   :TOTAL-LIABILITIES
   #:balance-sheet.liabilities
    {:accounts-payable  [:placeholder 0]
     :debt              [:placeholder 0]
     :share-capital     [:placeholder 0]
     :retained-earnings [:placeholder 0]}))

(def bs-check #:balance-sheet.checks
               {:balance '(- [:balance-sheet.assets/TOTAL-ASSETS]
                             [:balance-sheet.liabilities/TOTAL-LIABILITIES])})

(def bs-meta (fw/add-meta (merge bs-assets bs-liabs) {:units :currency}))

(def calcs [periods op-period income cashflow bs-assets bs-liabs bs-check])
(def metadata [income-meta cashflow-meta bs-meta])

(fw/fail-catch (fw/build-model2 inputs calcs metadata))
(def model (fw/build-model2 inputs calcs metadata))
(def header :period/end-date)

(def results (fw/run-model model 10))

(fw/print-category results (:meta model) header "balance-sheet" 1 10)
