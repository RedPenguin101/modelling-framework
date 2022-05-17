(ns fmwc.irr)

(defn- discount-factor [d i]
  (Math/pow (+ 1 d) (- i)))

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