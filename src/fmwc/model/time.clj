(ns fmwc.model.time)

;; TIME
;;;;;;;;;;;;;;;;

(def model-column-counter
  {:units "counter"
   :starter 0
   :calculator '(inc [:prev :self])})

(def first-model-column-flag
  {:units "flag"
   :calculator '((equal-to? 1) [:this :time/model-column-counter])})

(def model-period-beginning
  {:units "date"
   :calculator
   '(if (flagged? [:this :time/first-model-column-flag])
      [:const :inputs/first-date-of-time-rulers]
      (add-months [:prev :self] [:const :inputs/length-of-operating-period]))})

(def model-period-ending
  {:units "date"
   :calculator '(add-days (add-months [:this :time/model-period-beginning]
                                      [:const :inputs/length-of-operating-period])
                          -1)})

(def end-of-contract-year-flag
  {:units "flag"
   :starter 0
   :calculator '(make-flag (= (month-of [:const :inputs/aquisition-date])
                              (month-of [:this :time/model-period-ending])))})

(def end-of-operating-period
  {:units "date"
   :calculator '(end-of-month [:const :inputs/aquisition-date]
                              (* 12 [:const :inputs/operating-years-remaining]))})

(def operating-period-flag
  {:units "flag"
   :calculator '(make-flag (and (date> [:this  :time/model-period-ending]
                                       [:const :inputs/aquisition-date])
                                (date<= [:this  :time/model-period-ending]
                                        [:this :time/end-of-operating-period])))})

(def financial-close-period-flag
  {:units "flag" :starter 0
   :calculator '(make-flag
                 (and (date>= [:const :inputs/aquisition-date]
                              [:this  :time/model-period-beginning])
                      (date<= [:const :inputs/aquisition-date]
                              [:this  :time/model-period-ending])))})

(def first-operating-period-flag
  {:units "flag" :starter 0
   :calculator [:prev :time/financial-close-period-flag]})

(def period-number
  {:units "counter"
   :starter 0
   :calculator  '(* (if (= [:prev :self] [:const :inputs/periods-in-year]) 1
                        (inc [:prev :self]))
                    [:this :time/operating-period-flag])})

(def contract-year-number
  {:units "counter"
   :starter 0
   :calculator '(* (+ [:prev :self] [:prev :time/end-of-contract-year-flag])
                   [:this :time/operating-period-flag])})

(def contract-year-applicable-to-period
  {:units "date"
   :calculator '(cond (flagged? [:this :time/first-model-column-flag])
                      [:const :inputs/annual-year-end-date-of-first-operating-period]

                      (flagged? [:prev :time/end-of-contract-year-flag])
                      (end-of-month [:prev :self] 12)
                      :else [:prev :self])})

(def contract-year
  {:units "year"
   :calculator '(year-of [:this :time/contract-year-applicable-to-period])})

(def time-rows
  #:time
   {:model-column-counter            model-column-counter
    :first-model-column-flag         first-model-column-flag
    :model-period-beginning          model-period-beginning
    :model-period-ending             model-period-ending
    :financial-close-period-flag     financial-close-period-flag
    :first-operating-period-flag     first-operating-period-flag
    :end-of-contract-year-flag       end-of-contract-year-flag
    :end-of-operating-period         end-of-operating-period
    :operating-period-flag           operating-period-flag
    :period-number                   period-number
    :contract-year-number            contract-year-number
    :contract-year-applicable-to-period contract-year-applicable-to-period
    :contract-year                   contract-year})