(ns models.forest3
  (:require [fmwk.framework2 :as f :refer [calculation! totalled-calculation! base-case! corkscrew!]]
            [fmwk.utils :refer :all]))

;; Rewrite using the stateful version of the framework

(f/reset-model!)

(base-case!
 "base-case"
 :model-start-date            "2020-01-01"
 :aquisition-date             "2020-12-31"
 :aquisition-price            2000000
 :length-of-operating-period  12
 :volume-at-aquisition        50000
 :growth-rate                 0.05
 :inflation                   0.02
 :starting-price              50.0
 :management-fee-rate         0.015
 :starting-tax                1250.00
 :starting-costs              (+ 1.5 4.75)
 :ltv                         0.6
 :interest-rate               0.03
 :origination-fee-rate        0.01
 :sale-date                   "2035-12-31"
 :disposition-fee-rate        0.01)

(calculation!
 "period"
 :number                   '(inc [:number :prev])
 :first-model-column       '(= 1 [:number])

 :start-date               '(if [:first-model-column] [:inputs/model-start-date]
                                (add-days [:end-date :prev] 1))
 :end-date                 '(-> [:start-date]
                                (add-months [:inputs/length-of-operating-period])
                                (add-days -1))

 :aquisition-flag           '(and (date>= [:inputs/aquisition-date]
                                          [:start-date])
                                  (date<= [:inputs/aquisition-date]
                                          [:end-date]))

 :exit-flag                 '(and (date>= [:inputs/sale-date]
                                          [:start-date])
                                  (date<= [:inputs/sale-date]
                                          [:end-date])))

(calculation!
 "operating-period"
 :end               [:inputs/sale-date]
 :in-flag           '(and (date>  [:period/start-date]
                                  [:inputs/aquisition-date])
                          (date<= [:period/end-date]
                                  [:end]))
 :first-flag        '(date= [:inputs/aquisition-date]
                            (add-days [:period/start-date] -1))
 :last-flag         '(date= [:end]
                            [:period/end-date]))

(calculation!
 "volume"
 :growth     '(when-flag
               [:operating-period/in-flag]
               (* [:inputs/growth-rate] [:volume.balance/start]))

 :expenses   '(+ [:income/management-fee]
                 [:income/interest]
                 [:income/taxes])

 :harvest    '(when-not-flag
               [:period/exit-flag]
               (/ (- [:expenses])
                  [:prices/profit]))
 :exit       '(when-flag [:period/exit-flag]
                         (+ [:growth] [:volume.balance/start] (- [:harvest]))))

(corkscrew!
 "volume.balance"
 :starter         [:inputs/volume-at-aquisition]
 :start-condition [:period/aquisition-flag]
 :increases       [:volume/growth]
 :decreases       [:volume/harvest :volume/exit])

#_(def prices-meta
    #:prices{:inflation-period {:units :counter}
             :compound-inflation {:units :percent}
             :sale {:units :currency}
             :cost {:units :currency}
             :profit {:units :currency}
             :tax {:units :currency}})

(calculation!
 "prices"
 :inflation-period   '(dec [:period/number])
 :compound-inflation '(Math/pow (+ 1 [:inputs/inflation]) [:inflation-period])
 :sale               '(* [:compound-inflation] [:inputs/starting-price])
 :cost               '(* [:compound-inflation] [:inputs/starting-costs])
 :profit             '(- [:sale] [:cost])
 :tax                '(* [:compound-inflation] [:inputs/starting-tax]))

(calculation!
 "value"
 :starting [:ending :prev]
 :ending   '(* [:volume.balance/end] [:prices/profit]))

(calculation!
 "debt"
 :drawdowns           '(when-flag
                        [:period/aquisition-flag]
                        (* [:inputs/ltv] [:value/ending]))
 :origination-fee     '(* [:inputs/origination-fee-rate]
                          [:drawdowns])
 :repayment           '(when-flag
                        [:period/exit-flag]
                        [:debt.balance/start])
 :interest            '(* [:inputs/interest-rate] [:debt.balance/start]))

(corkscrew!
 "debt.balance"
 :increases       [:debt/drawdowns]
 :decreases       [:debt/repayment])

(totalled-calculation!
 "income" :net-profit
 :gross-profit    '(* [:volume/harvest] [:prices/profit])
 :management-fee  '(- (* [:value/starting] [:inputs/management-fee-rate]))
 :taxes           '(when-flag [:operating-period/in-flag]
                              (- [:prices/tax]))
 :interest        '(- [:debt/interest]))

(totalled-calculation!
 "disposition" :total
 :sale           '(when-flag [:period/exit-flag]
                             [:value/ending])
 :disp-fee       '(when-flag [:period/exit-flag]
                             (- (* [:inputs/disposition-fee-rate]
                                   [:sale])))
 :debt-repayment '(- [:debt/repayment]))


(totalled-calculation!
 "cashflow" :net-cashflow
 :asset-aquisition       '(when-flag [:period/aquisition-flag]
                                     (- [:inputs/aquisition-price]))
 :debt-drawdown          [:debt/drawdowns]
 :origination-fee        '(- [:debt/origination-fee])
 :net-income             [:income/net-profit]
 :disposition            [:disposition/total])

(def model (f/compile-model!))

(def results (f/run-model model 20))
(f/print-category-html results :period/end-date "volume" 13 20)