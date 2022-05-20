(ns fmwk.forest
  (:require [fmwk.framework :as fw]
            [fmwk.forest.inputs]
            [fmwk.forest.time]
            [fmwk.utils :refer :all]))

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
          :loan-repayment {:export true
                           :calculator '(if (flagged? [:financial-exit-period-flag])
                                          [:starting-debt]
                                          0)}
          :exit-cashflow {:export true
                          :calculator '(- [:sale-proceeds] [:disposition-fee] [:loan-repayment])}}})

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

(def calcs [fmwk.forest.time/time-calcs
            prices
            closing
            debt
            expenses
            cashflows
            volume value
            exit])

(def model (fw/build-model calcs fmwk.forest.inputs/inputs))

(fw/calculation-validation-halting calcs)
(fw/check-model-halting model)

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