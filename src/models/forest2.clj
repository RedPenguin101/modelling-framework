(ns models.forest2
  (:require [fmwk.framework :as fw]
            [fmwk.irr :refer [irr]]
            [fmwk.utils :refer :all]))

(def inputs
  #:inputs
   {:model-start-date "2020-01-01"
    :aquisition-date "2020-12-31"
    :aquisition-price 2000000
    :length-of-operating-period 12
    :volume-at-aquisition 50000
    :growth-rate 0.05
    :inflation 0.02
    :starting-price 50.0
    :management-fee-rate 0.015
    :starting-tax 1250.00
    :starting-costs (+ 1.5 4.75)
    :ltv 0.6
    :interest-rate 0.03
    :origination-fee-rate 0.01
    :sale-date "2035-12-31"
    :disposition-fee-rate 0.01})

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

(def volume
  #:volume
   {:aquisition '(when-flag
                  [:period/financial-close-flag]
                  [:inputs/volume-at-aquisition])
    :growth '(* [:inputs/growth-rate] [:volume.balance/start])
    :harvest '(when-flag (not [:period/financial-exit-flag])
                         (/ (- (+ [:income/management-fee]
                                  [:income/interest]
                                  [:income/taxes]))
                            [:prices/profit]))})

(def volume-balance
  (fw/corkscrew "volume.balance"
                [:volume/aquisition :volume/growth]
                [:volume/harvest]))

(def prices-meta
  #:prices{:inflation-period {:units :counter}
           :compound-inflation {:units :percent}
           :sale {:units :currency}
           :cost {:units :currency}
           :profit {:units :currency}
           :tax {:units :currency}})

(def prices
  #:prices
   {:inflation-period '(dec [:period/number])
    :compound-inflation '(Math/pow (+ 1 [:inputs/inflation]) [:inflation-period])
    :sale '(* [:compound-inflation] [:inputs/starting-price])
    :cost '(* [:compound-inflation] [:inputs/starting-costs])
    :profit '(- [:sale] [:cost])
    :tax '(* [:compound-inflation] [:inputs/starting-tax])})

(def value
  #:value
   {:starting [:ending :prev]
    :ending '(* [:volume.balance/end] [:prices/profit])})

(def debt
  #:debt
   {:amount '(if [:period/financial-close-flag]
               (* [:inputs/ltv] [:value/ending])
               [:amount :prev])
    :origination-fee '(* [:inputs/origination-fee-rate]
                         [:initial-drawdown])
    :initial-drawdown '(when-flag
                        [:period/financial-close-flag]
                        [:amount])
    :repayment '(when-flag
                 [:period/financial-exit-flag]
                 [:amount])})

(def debt-balance
  (fw/corkscrew "debt.balance"
                [:debt/initial-drawdown]
                [:debt/repayment]))

(def interest
  #:debt.interest{:charge '(* [:inputs/interest-rate] [:debt.balance/start])})


(def income
  (fw/add-total :net-profit
                #:income
                 {:gross-profit '(* [:volume/harvest] [:prices/profit])
                  :management-fee '(- (* [:value/starting] [:inputs/management-fee-rate]))
                  :taxes '(when-flag [:operating-period/in-flag]
                                     (- [:prices/tax]))
                  :interest '(- [:debt.interest/charge])}))

(def disposition
  (fw/add-total :total
                #:disposition
                 {:sale '(when-flag [:period/financial-exit-flag]
                                    [:value/ending])
                  :disp-fee '(when-flag [:period/financial-exit-flag]
                                        (- (* [:inputs/disposition-fee-rate]
                                              [:sale])))
                  :debt-repayment '(- [:debt/repayment])}))

(def cashflow
  (fw/add-total :net-cashflow
                #:cashflow
                 {:asset-aquisition '(when-flag [:period/financial-close-flag]
                                                (- [:inputs/aquisition-price]))
                  :debt-drawdown [:debt/initial-drawdown]
                  :origination-fee '(- [:debt/origination-fee])
                  :net-income [:income/net-profit]

                  :disposition [:disposition/total]}))

(def calcs [periods op-period
            volume volume-balance prices
            value
            debt debt-balance interest
            disposition
            income cashflow])

(def metadata [prices-meta
               (fw/add-meta income {:units :currency-thousands})
               (fw/add-meta cashflow {:units :currency-thousands :total true})])

(def model (fw/build-model2 inputs calcs metadata))
(comment (fw/fail-catch (fw/build-model2 inputs calcs)))
(def header :period/end-date)

(def results (fw/run-model model 20))
#_(fw/print-category results header "prices" 0 10)
(fw/print-category results (:meta model) header "cashflow" 1 10)

#_(irr (:cashflow/net-cashflow (into {} results)))