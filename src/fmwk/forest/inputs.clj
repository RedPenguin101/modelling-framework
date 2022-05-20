(ns fmwk.forest.inputs)

(def inputs
  {:model-start-date           {:value "2020-01-01"}
   :inflation-rate             {:value 1.02}
   :length-of-operating-period {:value 12}
   :aquisition-date            {:value "2020-12-31"}
   :operating-years-remaining  {:value 15}
   :sale-date                  {:value "2035-12-31"}
   :starting-price             {:value 50.0}
   :starting-costs             {:value (+ 4.75 1.5)}
   :starting-tax               {:value 1250}
   :purchase-price             {:value 2000000.0}
   :volume-at-aquisition       {:value 50000.0}
   :management-fee-rate        {:value 0.015}
   :ltv                        {:value 0.6}
   :growth-rate                {:value 0.05}
   :interest-rate              {:value 0.03}
   :disposition-fee-rate       {:value 0.01}
   :origination-fee-rate       {:value 0.01}})