(ns fmwc.forest3
  (:require [fmwc.framework3 :as fw]
            [fmwc.model.utils :refer :all]
            [ubergraph.core :as ug]))


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
   :input/starting-volume {:value 50000.0}
   :input/management-fee {:value 0.015}
   :ltv {:value 0.6}
   :growth-rate {:value 0.05}
   :input/origination-fee {:value 0.01}})

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

(def inflation
  {:name :compound-inflation
   :category :prices
   :import [:model-column-number :inflation-rate]
   :rows {:inflation-period {:calculator '(dec [:model-column-number])}
          :compound-inflation {:export true
                               :calculator '(Math/pow
                                             [:inflation-rate]
                                             [:inflation-period])}}})

(def sale-price
  {:name :sale-price
   :import [:compound-inflation :starting-price]
   :category :prices
   :rows {:sale-price {:export true
                       :calculator '(* [:compound-inflation]
                                       [:starting-price])}}})

(def costs
  {:name :costs
   :import [:compound-inflation :starting-costs]
   :category :prices
   :rows {:costs {:export true
                  :calculator '(* [:compound-inflation]
                                  [:starting-costs])}}})

(def tax
  {:name :tax
   :import [:compound-inflation :starting-tax]
   :category :expenses
   :rows {:tax {:export true
                :calculator '(* [:compound-inflation]
                                [:starting-tax])}}})

(def management-fee
  {:name :management-fee
   :import [:compound-inflation :starting-tax]
   :category :expenses
   :rows {:management-fee {:export true
                           :calculator '(* [:ending-value :prev]
                                           [:input/management-fee])}}})

(def purchase-price
  {:name :aquisition-cashflow
   :category :closing
   :import [:purchase-price :financial-close-period-flag]
   :rows {:aquisition-cashflow {:export true
                                :calculator '(if (flagged? [:financial-close-period-flag])
                                               [:purchase-price]
                                               0.0)}}})

(def debt-drawdown
  {:name :debt-drawdown
   :category :closing
   :import [:financial-close-period-flag :ltv :ending-value]
   :rows {:debt-drawdown {:export true
                          :calculator '(if (flagged? [:financial-close-period-flag])
                                         (* [:ltv] [:ending-value])
                                         0)}}})

(def origination-fee
  {:name :origination-fee
   :category :closing
   :import [:debt-drawdown :input/origination-fee :financial-close-period-flag]
   :rows {:origination-fee {:export true
                            :calculator '(if (flagged? [:financial-close-period-flag])
                                           (* [:input/origination-fee] [:debt-drawdown])
                                           0)}}})

(def volume
  {:name :ending-volume
   :category :volume
   :import [:input/starting-volume :financial-close-period-flag]
   :rows {:starting-volume {:calculator [:ending-volume :prev]}
          :growth {:calculator '(* [:starting-volume]
                                   [:growth-rate])}
          :harvest {:calculator [:placeholder 100.0]}
          :ending-volume {:export true
                          :calculator '(if (flagged? [:financial-close-period-flag])
                                         [:input/starting-volume]
                                         (- (+ [:starting-volume]
                                               [:growth])
                                            [:harvest]))}}})

(def value
  {:name :ending-value
   :category :value
   :import [:ending-volume :sale-price :costs]
   :rows {:ending-value {:export true
                         :calculator '(* [:ending-volume]
                                         (- [:sale-price] [:costs]))}}})

(def calcs [model-column-number first-model-column-flag
            period-start-date period-end-date
            financial-close-period-flag
            end-of-operating-period operating-period-flag
            inflation sale-price costs tax
            purchase-price debt-drawdown origination-fee
            management-fee
            volume value])

(def model (fw/build-model calcs inputs))

(fw/calculation-validation-halting calcs)
(fw/check-model-halting model)

(def results (fw/run-model model 10))

(comment
  (try (fw/run-model model 10)
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

(def headers [:period-start-date :period-end-date])
(def rows (concat headers
                  (rows-in model :category :volume)
                  (rows-in model :category :value)
                  (rows-in model :category :expenses)))

(fw/print-results (fw/row-select results rows) [1 6])