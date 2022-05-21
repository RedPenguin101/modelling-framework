(ns fmwk.forest
  (:require [fmwk.framework :as fw]
            [fmwk.forest.inputs]
            [fmwk.forest.time]
            [fmwk.utils :refer :all]))

;;; PRICES
;;;;;;;;;;;;;;;;;;;;;;;

(def prices
  #:prices{:inflation-period '(dec [:model-column-number])
           :compound-inflation '(Math/pow
                                 [:inflation-rate]
                                 [:inflation-period])
           :sale-price '(* [:compound-inflation]
                           [:starting-price])
           :costs {:calculator '(* [:compound-inflation]
                                   [:starting-price])}
           :profit '(- [:sale-price] [:costs])})

;;; EXPENSES
;;;;;;;;;;;;;;;;;;;;;;;

(def expenses
  #:expenses{:tax '(if (flagged? [:operating-period-flag])
                     (* [:compound-inflation]
                        [:starting-tax])
                     0)
             :interest '(* [:interest-rate]
                           [:starting-debt])
             :management-fee '(if (flagged? [:operating-period-flag])
                                (* [:ending-value :prev]
                                   [:management-fee-rate])
                                0)
             :expenses '(+ [:management-fee]
                           [:tax] [:interest])})

;;; CLOSING
;;;;;;;;;;;;;;;;;;;;;;;

(def closing
  #:capital.closing
   {:aquisition-cashflow '(if (flagged? [:financial-close-period-flag])
                            [:purchase-price]
                            0.0)
    :debt-drawdown '(if (flagged? [:financial-close-period-flag])
                      (* [:ltv] [:ending-value])
                      0)
    :origination-fee '(if (flagged? [:financial-close-period-flag])
                        (* [:origination-fee-rate] [:debt-drawdown])
                        0)
    :closing-cashflow '(- [:debt-drawdown]
                          [:origination-fee]
                          [:aquisition-cashflow])})


;;; DEBT
;;;;;;;;;;;;;;;;;;;;;;;

(def debt
  #:debt.debt-balance
   {:starting-debt  [:ending-debt :prev]
    :debt-increases [:debt-drawdown]
    :debt-decreases [:loan-repayment]
    :ending-debt    '(- (+ [:starting-debt]
                           [:debt-increases])
                        [:debt-decreases])})

;;; VOLUME AND VALUE
;;;;;;;;;;;;;;;;;;;;;;;

(def volume
  #:volume.volume
   {:starting-volume [:ending-volume :prev]
    :growth '(* [:starting-volume]
                [:growth-rate])
    :harvest '(if (and (flagged? [:operating-period-flag])
                       (not (flagged? [:financial-exit-period-flag])))
                (/ [:expenses] [:profit])
                0)
    :ending-volume '(if (flagged? [:financial-close-period-flag])
                      [:volume-at-aquisition]
                      (- (+ [:starting-volume]
                            [:growth])
                         [:harvest]))
    {:ending-value '(* [:ending-volume]
                       [:profit])}})

;; Sale
;;;;;;;;;;;;;;;;;;;

(def exit
  #:capital.exit
   {:sale-proceeds '(if (flagged? [:financial-exit-period-flag])
                      [:ending-value]
                      0)
    :disposition-fee '(if (flagged? [:financial-exit-period-flag])
                        (* [:sale-proceeds] [:disposition-fee-rate])
                        0)
    :loan-repayment '(if (flagged? [:financial-exit-period-flag])
                       [:starting-debt]
                       0)
    :exit-cashflow '(- [:sale-proceeds] [:disposition-fee] [:loan-repayment])})

;; Cashflows
;;;;;;;;;;;;;;;;;;;

(def cashflows
  #:financial-statements.cashflows
   {:aquisition [:closing-cashflow]
    :disposition [:exit-cashflow]
    :gross-profit '(* [:profit] [:harvest])
    :expenses-paid '(- [:expenses])
    :net-cashflow '(+ [:closing-cashflow]
                      [:disposition]
                      [:gross-profit]
                      [:expenses-paid])})

;; Orchestration
;;;;;;;;;;;;;;;;;;;

(def calcs [fmwk.forest.time/time-calcs
            prices
            closing
            debt
            expenses
            cashflows
            volume
            exit])

(def model (fw/build-model calcs fmwk.forest.inputs/inputs))

(comment
  (fw/calculation-validation-halting calcs)

  (fw/check-model-halting model))

(comment
  (ubergraph.core/viz-graph (:full-graph model)))

(def results (time (fw/run-model model 25)))

(comment
  "playing with name qualification"
  (require '[clojure.string :as str])
  (defn qualify-row-name [category calc row]
    (keyword (str (name category) "." (name calc)) (name row)))


  (qualify-row-name :financing :debt :drawdowns)
  ;; => :financing.debt/drawdowns

  (qualify-row-name :financing :junior :drawdowns)
  ;; => :financing.junior/drawdowns

  "use this for model validation?"
  (assert (= 1 (+ 1 1)))
  ;; => Execution error (AssertionError) at fmwk.forest-fmwk/eval48594 (REPL:257).
  ;;    Assert failed: (= 1 (+ 1 1))
  )


(comment
  (try (fw/run-model model 25)
       (catch Exception e
         (ex-data e))))



(def headers [:period-end-date])
(def rows (concat headers
                  #_(fw/rows-in model :category :volume)
                  #_(fw/rows-in model :category :value)
                  #_(fw/rows-in model :calculation :exit)
                  (fw/rows-in model :calculation :cashflows)
                  #_[:financial-exit-period-flag]

                  #_(fw/rows-in model :category :debt)
                  #_(fw/rows-in model :category :expenses)
                  [:operating-period-flag]))

(fw/print-results (fw/row-select results rows) [12 18])

(comment
  (keys model))

