(ns fmwk.forest.time)

(def time-calcs
  {:name :time
   :category :time
   :rows {:model-column-number {:units "counter"
                                :export true
                                :starter 0
                                :calculator '(inc [:model-column-number :prev])}
          :first-model-column-flag {:units "flag"
                                    :export true
                                    :calculator
                                    '(if (= 1 [:model-column-number])
                                       1 0)}
          :period-start-date
          {:export true
           :calculator '(if (= 1 [:first-model-column-flag])
                          [:model-start-date]
                          (add-days [:period-end-date :prev] 1))}
          :period-end-date
          {:export true
           :calculator '(add-days (add-months [:period-start-date]
                                              [:length-of-operating-period])
                                  -1)}
          :financial-close-period-flag
          {:export true
           :calculator '(make-flag
                         (and (date>= [:aquisition-date]
                                      [:period-start-date])
                              (date<= [:aquisition-date]
                                      [:period-end-date])))}
          :financial-exit-period-flag
          {:export true
           :calculator '(make-flag
                         (and (date>= [:end-of-operating-period]
                                      [:period-start-date])
                              (date<= [:end-of-operating-period]
                                      [:period-end-date])))}
          :end-of-operating-period
          {:export true
           :calculator '(end-of-month [:aquisition-date]
                                      (* 12 [:operating-years-remaining]))}
          :operating-period-flag
          {:export true
           :calculator '(make-flag
                         (and (date> [:period-start-date]
                                     [:aquisition-date])
                              (date<= [:period-end-date]
                                      [:end-of-operating-period])))}}})