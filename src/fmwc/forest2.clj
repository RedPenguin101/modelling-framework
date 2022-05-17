(ns fmwc.forest2
  (:require [fmwc.utils :as u]))

(def assumptions
  {;; general
   :inflation 0.02
   :start-year 2021
   ;; operating assumptions
   :initial-volume 50000
   :price 50
   :growth 0.05
   :mgmt-fee 0.015
   :tax 1250
   :cost (+ 4.75 1.5)
   ;; aquisition and disposition
   :aquisition 2000000
   :sale-period 15
   :disp-fee 0.01
   ;; bank loan
   :ltv 0.6
   :interest-rate 0.03
   :originiation-fee 0.01})

(defn assumption [period a-name] (get-in period [:assumptions a-name]))

(defn inflate [rate] (fn [previous] (* rate previous)))

(defn growth [period]
  (* (assumption period :growth)
     (:starting-volume period)))
(def include-growth (u/include :growth growth))

(defn interest [period]
  (* (assumption period :interest-rate)
     (:loan-balance period)))
(def include-interest (u/include :interest interest))

(defn management-fee [period]
  (* (period :starting-volume) (assumption period :mgmt-fee)))
(def include-management-fee (u/include :mgmt-fee management-fee))

(defn break-even-harvest [period]
  (let [revenue-per-m3 (+ (:costs period) (:price period))
        expenses (+ (:interest period)
                    (:tax period)
                    (:mgmt-fee period))]
    (- (/ expenses revenue-per-m3))))
(def include-harvest (u/include :harvest break-even-harvest))

(defn model [period]
  (-> period
      (update :period inc)
      (update :year inc)
      (update :costs (:inflation period))
      (update :price (:inflation period))
      (update :tax (:inflation period))
      (assoc :starting-volume (:ending-volume period))
      (include-growth)
      (include-interest)
      (include-management-fee)
      (break-even-harvest)))