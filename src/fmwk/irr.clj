(ns fmwk.irr
  (:require [fmwk.dates :as d]))

(defn- discount-factor [rate years]
  (Math/pow (+ 1 rate) (- years)))

(defn- npv [cashflows d]
  (reduce + (map #(apply * %) (map-indexed #(vector (discount-factor d %1) %2) cashflows))))

(defn irr
  ([cashflows] (irr cashflows [0 1] 0.001 0))
  ([cashflows [guess1 guess2] threshold iteration]
   (let [n1 (npv cashflows guess1)
         n2 (npv cashflows guess2)
         bisection (/ (+ guess1 guess2) 2)
         n3 (npv cashflows bisection)]
     (cond (> iteration 100)            :iteration-limit
           (= (pos? n1) (pos? n2))      :no-interval
           (<= (Math/abs n3) threshold) (float bisection)
           (= (pos? n1) (pos? n3))      (recur cashflows [bisection guess2] threshold (inc iteration))
           :else                        (recur cashflows [guess1 bisection] threshold (inc iteration))))))

(comment
  (def cashflows [-100 30 30 30 30 30 30 30 30 30])

  (npv cashflows 0.2634)
;; => 0.007282812127450189
  (irr cashflows)
  ;; => 0.26342773
  )

(defn days-from [dates]
  (map #(d/days-between (first dates) %) dates))

(days-from ["2020-03-31" "2020-06-30" "2020-09-30" "2020-12-31" "2021-03-31" "2021-06-30" "2021-09-30" "2021-12-31" "2022-03-31" "2022-06-30" "2022-09-30" "2022-12-31" "2023-03-31" "2023-06-30" "2023-09-30" "2023-12-31" "2024-03-31" "2024-06-30" "2024-09-30"])

(defn- npv-days [year-fracs cashflows annual-rate]
  (apply + (map * (map #(discount-factor annual-rate %) year-fracs)
                cashflows)))

(defn irr-days
  ([dates cashflows] (irr-days dates cashflows [0 1] 0.001 0))
  ([dates cashflows [guess1 guess2] threshold iteration]
   (let [yfs (map #(/ % 365) (days-from dates))
         n1 (npv-days yfs cashflows guess1)
         n2 (npv-days yfs cashflows guess2)
         bisection (/ (+ guess1 guess2) 2)
         n3 (npv-days yfs cashflows bisection)]
     (cond (> iteration 100)            :iteration-limit
           (= (pos? n1) (pos? n2))      :no-interval
           (<= (Math/abs n3) threshold) (float bisection)
           (= (pos? n1) (pos? n3))      (recur dates cashflows [bisection guess2] threshold (inc iteration))
           :else                        (recur dates cashflows [guess1 bisection] threshold (inc iteration))))))

(irr-days ["2020-03-31" "2020-06-30" "2020-09-30" "2020-12-31" "2021-03-31" "2021-06-30" "2021-09-30" "2021-12-31" "2022-03-31" "2022-06-30" "2022-09-30" "2022-12-31" "2023-03-31" "2023-06-30" "2023-09-30" "2023-12-31" "2024-03-31" "2024-06-30" "2024-09-30"]
          (into [-144618.26] (repeat 18 10000)))
