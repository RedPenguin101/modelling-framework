(ns fmwk-test.test-forest-model
  (:require [fmwk.framework :as fw]
            [fmwk.utils :refer :all]))


;;;;;;;;;;;;;;;;;;;;
;; Testing Model
;;;;;;;;;;;;;;;;;;;;

;; Time
;;;;;;;;;;;;;;;;;;;;

(def time-calcs
  #:time
   {:model-column-number '(inc [:model-column-number :prev])
    :period-start-date '(if (= 1 [:flags/first-model-column])
                          [:inputs/model-start-date]
                          (add-days [:period-end-date :prev] 1))
    :period-end-date '(add-days (add-months [:period-start-date]
                                            [:inputs/length-of-operating-period])
                                -1)
    :end-of-operating-period '(end-of-month [:inputs/aquisition-date]
                                            (* 12 [:inputs/operating-years-remaining]))})

(def flags
  #:flags{:first-model-column '(if (= 1 [:time/model-column-number]) 1 0)
          :financial-close-period '(and (date>= [:inputs/aquisition-date]
                                                [:time/period-start-date])
                                        (date<= [:inputs/aquisition-date]
                                                [:time/period-end-date]))
          :financial-exit-period '(and (date>= [:time/end-of-operating-period]
                                               [:time/period-start-date])
                                       (date<= [:time/end-of-operating-period]
                                               [:time/period-end-date]))
          :operating-period '(and (date> [:time/period-start-date]
                                         [:inputs/aquisition-date])
                                  (date<= [:time/period-end-date]
                                          [:time/end-of-operating-period]))})

;; Inputs
;;;;;;;;;;;;;;;;;;;;

(def inputs
  #:inputs
   {:model-start-date            "2020-01-01"
    :inflation-rate              1.02
    :length-of-operating-period  12
    :aquisition-date             "2020-12-31"
    :operating-years-remaining   15
    :sale-date                   "2035-12-31"
    :starting-price              50.0
    :starting-costs              (+ 4.75 1.5)
    :starting-tax                1250
    :purchase-price              2000000.0
    :volume-at-aquisition        50000.0
    :management-fee-rate         0.015
    :ltv                         0.6
    :growth-rate                 0.05
    :interest-rate               0.03
    :disposition-fee-rate        0.01
    :origination-fee-rate        0.01})

;; Model
;;;;;;;;;;;;;;;;;;;;

(def prices
  #:prices{:compound-inflation '(Math/pow
                                 [:inputs/inflation-rate]
                                 (dec [:time/model-column-number]))
           :sale-price '(* [:compound-inflation]
                           [:inputs/starting-price])
           :costs  '(* [:compound-inflation]
                       [:inputs/starting-costs])
           :profit '(- [:sale-price] [:costs])})

(def expenses
  (fw/add-total
   #:expenses{:tax '(if [:flags/operating-period]
                      (* [:prices/compound-inflation]
                         [:inputs/starting-tax]) 0)
              :interest '(* [:inputs/interest-rate]
                            [:debt.debt-balance/start])
              :management-fee '(if [:flags/operating-period]
                                 (* [:value/start]
                                    [:inputs/management-fee-rate])
                                 0)}))

(def debt
  (merge
   #:debt{:drawdown '(if [:flags/financial-close-period]
                       (* [:inputs/ltv] [:value/end]) 0)
          :repayment '(if [:flags/financial-exit-period]
                        [:debt.debt-balance/start] 0)}
   (fw/corkscrew :debt.debt-balance
                 [:debt/drawdown]
                 [:debt/repayment])))

(def volume
  (merge
   #:volume {:growth '(* [:volume.balance/start]
                         [:inputs/growth-rate])
             :harvest '(if (and [:flags/operating-period]
                                (not [:flags/financial-exit-period]))
                         (/ [:expenses/total] [:prices/profit]) 0)
             :purchased '(if [:flags/financial-close-period]
                           [:inputs/volume-at-aquisition] 0)}

   (fw/corkscrew :volume.balance
                 [:volume/growth :volume/purchased]
                 [:volume/harvest])))

(def value
  {:value/start [:end :prev]
   :value/end '(* [:volume.balance/end]
                  [:prices/profit])})

(def closing
  #:capital.closing
   {:aquisition-cashflow '(if [:flags/financial-close-period]
                            [:inputs/purchase-price] 0)
    :origination-fee '(if [:flags/financial-close-period]
                        (* [:inputs/origination-fee-rate]
                           [:debt/drawdown])
                        0)})

(def exit
  #:capital.exit
   {:sale-proceeds '(if [:flags/financial-exit-period]
                      [:value/end] 0)
    :disposition-fee '(if [:flags/financial-exit-period]
                        (* [:sale-proceeds] [:inputs/disposition-fee-rate])
                        0)})

(def cashflows
  (fw/add-total
   :net-cashflow
   #:cashflows
    {:aquisition '(- [:debt/drawdown]
                     [:capital.closing/origination-fee]
                     [:capital.closing/aquisition-cashflow])
     :disposition '(- [:capital.exit/sale-proceeds]
                      [:capital.exit/disposition-fee]
                      [:debt/repayment])
     :gross-profit '(* [:prices/profit] [:volume/harvest])
     :expenses-paid '(- [:expenses/total])}))

(def model (fw/build-model2
            inputs
            [time-calcs flags
             prices expenses closing
             debt volume value
             exit cashflows]))

(def header :time/period-end-date)
(fw/print-category (fw/run-model model 20) header "cashflows" 10 20)