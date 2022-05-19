(ns fmwc.forest3
  (:require [fmwc.framework3 :as fw]
            [fmwc.model.utils :refer :all]
            [ubergraph.core :as ug]
            [ubergraph.core :as uber]
            [ubergraph.alg :as alg]))


;; model
;;;;;;;;;;;;;;;

(def inputs
  {:model-start-date {:value "2020-01-01"}
   :inflation-rate {:value 1.02}
   :length-of-operating-period {:value 12}
   :aquisition-date {:value "2020-12-31"}
   :operating-years-remaining {:value 15}
   :sale-date {:value "2035-12-31"}
   :starting-price {:value 50.0}
   :starting-costs {:value (+ 4.75 1.5)}
   :starting-tax {:value 1250}
   :purchase-price {:value 2000000.0}
   :volume-at-aquisition {:value 50000.0}
   :management-fee-rate {:value 0.015}
   :ltv {:value 0.6}
   :growth-rate {:value 0.05}
   :interest-rate {:value 0.03}
   :disposition-fee-rate {:value 0.01}
   :origination-fee-rate {:value 0.01}})


;;; TIME
;;;;;;;;;;;;;;;;;;;;;;;

(def model-column-number
  {:name     :model-column-number
   :category :time
   :rows {:model-column-number {:units "counter"
                                :export true
                                :starter 0
                                :calculator '(inc [:model-column-number :prev])}}})

(def first-model-column-flag
  {:name :first-model-column-flag
   :category :time
   :import [:model-column-number]
   :rows {:first-model-column-flag {:units "flag"
                                    :export true
                                    :calculator
                                    '(if (= 1 [:model-column-number])
                                       1 0)}}})

(def period-start-date
  {:name :period-start-date
   :category :time
   :import [:model-start-date :first-model-column-flag :period-end-date]
   :rows {:period-start-date
          {:export true
           :calculator '(if (= 1 [:first-model-column-flag])
                          [:model-start-date]
                          (add-days [:period-end-date :prev] 1))}}})

(def period-end-date
  {:name :period-end-date
   :category :time
   :import [:period-start-date :length-of-operating-period]
   :rows {:period-end-date
          {:export true
           :calculator '(add-days (add-months [:period-start-date]
                                              [:length-of-operating-period])
                                  -1)}}})

(def financial-close-period-flag
  {:name :financial-close-period-flag
   :category :time
   :import [:aquisition-date :period-start-date :period-end-date]
   :rows {:financial-close-period-flag
          {:export true
           :calculator '(make-flag
                         (and (date>= [:aquisition-date]
                                      [:period-start-date])
                              (date<= [:aquisition-date]
                                      [:period-end-date])))}}})

(def financial-exit-period-flag
  {:name :financial-exit-period-flag
   :category :time
   :import [:aquisition-date :period-start-date :period-end-date]
   :rows {:financial-exit-period-flag
          {:export true
           :calculator '(make-flag
                         (and (date>= [:end-of-operating-period]
                                      [:period-start-date])
                              (date<= [:end-of-operating-period]
                                      [:period-end-date])))}}})

(def end-of-operating-period
  {:name :end-of-operating-period
   :category :time
   :import [:aquisition-date :operating-years-remaining]
   :rows {:end-of-operating-period
          {:export true
           :calculator '(end-of-month [:aquisition-date]
                                      (* 12 [:operating-years-remaining]))}}})

(def operating-period-flag
  {:name :operating-period-flag
   :category :time
   :import [:period-start-date :aquisition-date :period-end-date :end-of-operating-period]
   :rows {:operating-period-flag
          {:export true
           :calculator '(make-flag
                         (and (date> [:period-start-date]
                                     [:aquisition-date])
                              (date<= [:period-end-date]
                                      [:end-of-operating-period])))}}})

;;; PRICES
;;;;;;;;;;;;;;;;;;;;;;;

(def prices
  {:name :prices
   :import []
   :category :prices
   :rows {:inflation-period {:calculator '(dec [:model-column-number])}
          :compound-inflation {:export true
                               :calculator '(Math/pow
                                             [:inflation-rate]
                                             [:inflation-period])}
          :sale-price {:calculator '(* [:compound-inflation]
                                       [:starting-price])}
          :costs {:calculator '(* [:compound-inflation]
                                  [:starting-costs])}
          :profit {:export true
                   :calculator '(- [:sale-price] [:costs])}}})

;;; EXPENSES
;;;;;;;;;;;;;;;;;;;;;;;

(def expenses
  {:name :expenses
   :import [:compound-inflation :starting-tax]
   :category :expenses
   :rows {:tax {:calculator '(if (flagged? [:operating-period-flag])
                               (* [:compound-inflation]
                                  [:starting-tax])
                               0)}
          :interest {:calculator '(* [:interest-rate]
                                     [:starting-debt])}
          :management-fee {:calculator '(if (flagged? [:operating-period-flag])
                                          (* [:ending-value :prev]
                                             [:management-fee-rate])
                                          0)}
          :expenses {:export true
                     :calculator '(+ [:management-fee]
                                     [:tax] [:interest])}}})

;;; CLOSING
;;;;;;;;;;;;;;;;;;;;;;;

(def closing
  {:name :closing
   :category :capital
   :rows {:aquisition-cashflow {:calculator '(if (flagged? [:financial-close-period-flag])
                                               [:purchase-price]
                                               0.0)}
          :debt-drawdown {:export true
                          :calculator '(if (flagged? [:financial-close-period-flag])
                                         (* [:ltv] [:ending-value])
                                         0)}
          :origination-fee {:calculator '(if (flagged? [:financial-close-period-flag])
                                           (* [:origination-fee-rate] [:debt-drawdown])
                                           0)}
          :closing-cashflow {:export true
                             :calculator '(- [:debt-drawdown]
                                             [:origination-fee]
                                             [:aquisition-cashflow])}}})


;;; DEBT
;;;;;;;;;;;;;;;;;;;;;;;

(def debt
  {:name :debt-balance
   :category :debt
   :rows {:starting-debt {:export true
                          :calculator [:ending-debt :prev]}
          :debt-increases     {:calculator [:debt-drawdown]}
          :debt-decreases     {:calculator [:loan-repayment]}
          :ending-debt   {:export true
                          :calculator '(- (+ [:starting-debt]
                                             [:debt-increases])
                                          [:debt-decreases])}}})

;;; VOLUME AND VALUE
;;;;;;;;;;;;;;;;;;;;;;;

(def volume
  {:name :ending-volume
   :category :volume
   :import [:volume-at-aquisition :financial-close-period-flag]
   :rows {:starting-volume {:calculator [:ending-volume :prev]}
          :growth {:calculator '(* [:starting-volume]
                                   [:growth-rate])}
          :harvest {:export true
                    :calculator '(if (and (flagged? [:operating-period-flag])
                                          (not (flagged? [:financial-exit-period-flag])))
                                   (/ [:expenses] [:profit])
                                   0)}
          :ending-volume {:export true
                          :calculator '(if (flagged? [:financial-close-period-flag])
                                         [:volume-at-aquisition]
                                         (- (+ [:starting-volume]
                                               [:growth])
                                            [:harvest]))}}})

(def value
  {:name :value
   :category :value
   :import []
   :rows {:ending-value {:export true
                         :calculator '(* [:ending-volume]
                                         [:profit])}}})

;; Sale
;;;;;;;;;;;;;;;;;;;

(def exit
  {:name :exit
   :category :capital
   :rows {:sale-proceeds {:calculator '(if (flagged? [:financial-exit-period-flag])
                                         [:ending-value]
                                         0)}
          :disposition-fee {:calculator '(if (flagged? [:financial-exit-period-flag])
                                           (* [:sale-proceeds] [:disposition-fee-rate])
                                           0)}
          :loan-repayment {:calculator '(if (flagged? [:financial-exit-period-flag])
                                          [:starting-debt]
                                          0)}
          :exit-cashflow {:calculator '(- [:sale-proceeds] [:disposition-fee] [:loan-repayment])}}})

;; Cashflows
;;;;;;;;;;;;;;;;;;;

(def cashflows
  {:name :cashflows
   :category :financial-statements
   :rows {:aquisition {:calculator [:closing-cashflow]}
          :disposition {:calculator [:exit-cashflow]}
          :gross-profit {:calculator '(* [:profit] [:harvest])}
          :expenses-paid {:calculator '(- [:expenses])}
          :net-cashflow {:calculator '(+ [:closing-cashflow]
                                         [:disposition]
                                         [:gross-profit]
                                         [:expenses-paid])}}})

;; Orchestration
;;;;;;;;;;;;;;;;;;;

(def calcs [model-column-number first-model-column-flag
            period-start-date period-end-date
            financial-close-period-flag
            end-of-operating-period operating-period-flag financial-exit-period-flag
            prices
            closing
            debt
            expenses
            cashflows
            volume value
            exit])

(def model (fw/build-model calcs inputs))

(fw/calculation-validation-halting calcs)
(fw/check-model-halting model)

(comment
  (ubergraph.core/viz-graph (:full-graph model)))

(def results (time (fw/run-model model 25)))

(comment
  (try (fw/run-model model 25)
       (catch Exception e
         (ex-data e))))

(defn rows-in [model typ nm]
  (case typ
    :category (mapcat (comp keys :rows) (filter #(= (:category %) nm) (vals (:calculations model))))
    :calculation (mapcat (comp keys :rows) (filter #(= (:name %) nm) (vals (:calculations model))))
    :special (if (= :exports nm)
               (fw/exports model)
               (throw (ex-info (str "Rows in Not implemented for " typ " " nm)
                               model)))
    (throw (ex-info (str "Rows in Not implemented for " typ " " nm)
                    model))))

(def headers [:period-end-date])
(def rows (concat headers
                  #_(rows-in model :category :volume)
                  #_(rows-in model :category :value)
                  #_(rows-in model :calculation :exit)
                  (rows-in model :calculation :cashflows)
                  #_[:financial-exit-period-flag]

                  #_(rows-in model :category :debt)
                  #_(rows-in model :category :expenses)
                  [:operating-period-flag]))

(fw/print-results (fw/row-select results rows) [12 18])

(defn print-categories [cats results periods]
  (doall (for [cat cats]
           (do (println (str "\n\n" cat))
               (fw/print-results (fw/row-select results (rows-in model :category cat)) periods)))))

(set (map :category (vals (:calculations model))))
(def cats [:time :prices :capital :debt :volume  :value
           :expenses :financial-statements])

(comment
  (print-categories cats results [12 18]))