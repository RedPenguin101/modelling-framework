(ns fmwc.model.forest
  (:require [fmwc.framework2 :as fw]
            [fmwc.model.time :as t]
            [fmwc.model.utils :refer :all]))

;; Inputs
;;;;;;;;;;;;;;;;;;;;

(def standard-inputs
  ;;note that these inputs may be depended on by standard model rows
  #:inputs
   {:first-date-of-time-rulers                      {:units "date"    :starter "2020-01-01"}
    :aquisition-date                                {:units "date"    :starter "2020-12-31"}
    :annual-year-end-date-of-first-operating-period {:units "date"    :starter "2021-12-31"}
    :operating-years-remaining                      {:units "years"   :starter 25}
    :length-of-operating-period                     {:units "months"  :starter 12}
    :periods-in-year                                {:units "periods" :starter 1}})

(def inputs
  #:inputs
   {:starting-volume  {:units "m3"      :starter 50000.0}
    :starting-price   {:units "$/m3"    :starter 50.0}
    :starting-cost    {:units "$/m3"    :starter (+ 4.75 1.5)}
    :growth-rate      {:units "percent" :starter 0.05}
    :inflation        {:units "percent" :starter 1.02}
    :interest-rate    {:units "percent" :starter 0.03}
    :starting-tax     {:units "$"       :starter 1250.0}
    :aquisition-price {:units "$"      :starter 2000000.0}
    :ltv              {:units "percent" :starter  0.6}
    :origination-fee-percent {:units "percent" :starter  0.01}
    :management-fee-rate {:units "percent" :starter 0.015}})

;; prices
;;;;;;;;;;;;;;;;;;;;;;;;

(def prices
  #:prices
   {:inflation-period {:units "counter" :calculator '(dec [:this :time/model-column-counter])}
    :compund-inflation {:units "percent" :calculator '(Math/pow
                                                       [:const :inputs/inflation]
                                                       [:this :prices/inflation-period])}
    :sale-price {:units "$"
                 :starter 0.0
                 :calculator '(* [:const :inputs/starting-price]
                                 [:this :prices/compund-inflation])}
    :costs {:units "$" :calculator '(* [:const :inputs/starting-cost]
                                       [:this :prices/compund-inflation])}
    :profit {:units "$" :calculator '(- [:this :prices/sale-price]
                                        [:this :prices/costs])}
    :tax {:units "$" :calculator '(* [:const :inputs/starting-tax]
                                     [:this :prices/compund-inflation])}})

;; Volume
;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (deref fw/debug)

  (fw/vizi-deps model))

(def volume
  #:volume
   {:opening {:units "m3" :calculator [:prev :volume/end]}
    :growth {:units "m3" :calculator '(* [:this :volume/opening]
                                         [:const :inputs/growth-rate])}
    :harvest {:units "m3" :calculator [:placeholder 50.0]
              #_'(- (/ [:this :expenses/total]
                       [:this :prices/profit]))}
    :end {:units "m3"
          :starter 0.0
          :calculator '(if (flagged? [:this :time/financial-close-period-flag])
                         [:const :inputs/starting-volume]
                         (- (+ [:this :volume/opening] [:this :volume/growth])
                            [:this :volume/harvest]))}})

;; value
;;;;;;;;;;;;;;;;;;;;;;;;;

(def value
  #:value
   {:starting {:units "$" :calculator [:prev :value/ending]}
    :ending {:units "$"
             :starter 0.0
             :calculator '(* [:this :volume/end]
                             (- [:this :prices/sale-price]
                                [:this :prices/costs]))}})

;; Closing
;;;;;;;;;;;;;;;;;;;;;;;;

(def closing
  #:closing
   {:purchase-price {:units "$"
                     :calculator '(if (flagged? [:this :time/financial-close-period-flag])
                                    [:const :inputs/aquisition-price]
                                    0.0)}
    :loan-drawn     {:units "$"
                     :calculator
                     '(if (flagged? [:this :time/financial-close-period-flag])
                        (* [:const :inputs/ltv] [:this :value/ending])
                        0.0)}
    :origination-fee {:units "$"
                      :calculator
                      '(if (flagged? [:this :time/financial-close-period-flag])
                         (* [:this :closing/loan-drawn]
                            [:const :inputs/origination-fee-percent])
                         0.0)}})

;; Debt
;;;;;;;;;;;;;;;;;;;;;;;;

(def debt
  #:debt
   {:opening-balance {:units "$" :calculator [:prev :debt/closing-balance]}
    :increase {:units "$" :calculator [:this :closing/loan-drawn]}
    :closing-balance {:units "$" :starter 0.0
                      :calculator '(+ [:this :debt/opening-balance]
                                      [:this :debt/increase])}
    :interest-charge {:units "$" :starter 0.0
                      :calculator '(* [:const :inputs/interest-rate]
                                      [:this :debt/closing-balance])}})

;; Expenses
;;;;;;;;;;;;;;;;;;;;;;;;

(def expenses
  #:expenses
   {:interest {:calculator [:this :debt/interest-charge]}
    :tax {:calculator [:this :prices/tax]}
    :mgmt-fee {:calculator '(* [:const :inputs/management-fee-rate]
                               [:this :value/starting])}
    :total {:calculator '(+ [:this :expenses/interest]
                            [:this :expenses/tax]
                            [:this :expenses/mgmt-fee])}})

;; Model defn and run
;;;;;;;;;;;;;;;;;;;;;;;;

(def model (fw/make-model [inputs standard-inputs t/time-rows
                           prices
                           volume value
                           closing
                           debt
                           expenses]))

(fw/check-model-deps model)

(comment
  (try (fw/run2 model 10)
       (catch Exception e (ex-data e))))

(def model-output (fw/run2 model 10))

(def headers [:time/model-period-beginning :time/model-period-ending
              #_:time/financial-close-period-flag
              :time/operating-period-flag])

(def temp [])

(sort model-output)

(fw/print-table (fw/output->table model model-output
                                  (concat headers temp
                                          #_(keys prices)
                                          #_(keys value)
                                          (keys expenses)
                                          (keys volume)
                                          #_(keys closing)
                                          #_(keys debt))))