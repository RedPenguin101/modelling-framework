(ns fmwk.forest-manual
  (:require [fmwk.irr :refer [irr]]
            [fmwk.utils :refer [include]]))

;; PROBLEM DEFINITION
;;;;;;;;;;;;;;;;;;;;;;;;

;; 25Y cashflow model
;; no currency effects
;; volume of forest growth 5% pa
;; all cf on last day of year
;; Valuation : VAL=VOL*(PR-CC-PC) (price is weighted average)
;; Closing
;;   purchase of forest is $2m
;;   closing date is 2020-12-31 (Year 0)
;;   50k m3 of wood at Y0
;; Bank loan
;;   Bank loan at 60% project value
;;   3% coupon
;;   1% origination fee paid at closing
;;   bullet repayment
;; Operations
;;   mgmt fee 1.5% of value at year start
;;   tax is $1250 pa Y0, 2% increase pa
;; harvesting:
;;   cost $4.75/m3 Y0, increasing 2% pa
;;   cut trees are replaced, at a cost of $1.5/m3 Y0, increasing 2% pa
;;   Sale price of wood is $50/m3 Y0, assumption is 2% pa increase
;;   amount cut is Y1-14: enough to break even, net CF=0
;;                 Y15, no cut, only sales proceeds
;; Sale
;;   sell @ value at end Y15 (2035-12-31)
;;   1% disposition fee on date of sale
;; Any cash at YE is distributed to equity holders

;; MODEL
;;;;;;;;;;;;;;;;;;;;;;;;

(def start {:volume 50000
            :price 50
            :cost (+ 4.75 1.5)
            :growth-rate 0.05
            :interest-rate 0.03
            :inflation 1.02
            :tax 1250})

(defn value [{:keys [volume price cost]}] (* volume (- price cost)))
(def add-value (include :value value))

(defn closing [start aq-cost]
  (-> start
      (assoc :year 0)
      (assoc :loan-amount (-> start value (* 0.6)))
      (assoc :value (value start))
      (assoc :cash-flow (- (* 0.99 (-> start value (* 0.6))) aq-cost))))

(defn next-year-pre [year]
  {:year          (inc (:year year))
   :loan-amount   (:loan-amount year)
   :interest-rate (:interest-rate year)
   :interest      (* (:loan-amount year) (:interest-rate year))

   :inflation     (:inflation year)
   :tax           (* (:inflation year) (:tax year))
   :cost          (* (:inflation year) (:cost year))
   :price         (* (:inflation year) (:price year))

   :mgmt-fee      (* 0.015 (:value year))
   :growth-rate   (:growth-rate year)
   :growth        (* (:growth-rate year) (:volume year))
   :volume        (:volume year)})

;; Cashflow calculation
(defn outflows [year] (- (apply + (vals (select-keys year #{:mgmt-fee :tax :interest})))))
(defn sale-procs [year] (- (* 0.99 (:value year)) (:loan-amount year)))
(defn net-cashflow [year] (+ (:net-sale-proceeds year) (outflows year)))
(def add-cf (include :cash-flow net-cashflow))

;; Harvest calculators
(defn breakeven-vol [year]
  (/ (- (outflows year)) (- (:price year) (:cost year))))

(defn no-harvest [_year] 0)

(defn next-year-post [harvest-fn]
  (fn [year]
    (let [harvest (harvest-fn year)]
      (-> year
          (assoc :amount-harvested harvest
                 :net-sale-proceeds (* harvest (- (:price year) (:cost year)))
                 :volume (+ (:volume year) (:growth year) (- harvest)))
          (add-value)
          (add-cf)))))

(def next-year (comp (next-year-post breakeven-vol) next-year-pre))

(defn sale [year]
  (let [run ((next-year-post no-harvest) (next-year-pre year))]
    (-> run (update :cash-flow + (sale-procs run)))))

(defn run-model [start sale-year aq-cost]
  (let [run (vec (->> (closing start aq-cost)
                      (iterate next-year)
                      (take sale-year)))]
    (conj run (sale (last run)))))

;; QUESTIONS
;;;;;;;;;;;;;;;;;;;

(def run-results (run-model start 15 2000000))

;; 1. What is the project’s intrinsic value at acquisition?
(value start)
;; => 2,187,500.0

;; 2. What amount of loan will PGC be able to attract?
(:loan-amount (first run-results))
;; => 1,312,500.0

;; 3. What is the projected market price of wood at the end of Year 15
;; (project disposition date) ?
(:price (last run-results))
;; => 67.2934169162065

;; 4. What is the projected price of wood net of cutting and planting expenses at the end of
;; Year 15 (project disposition date) ?
(- (:price (last run-results)) (:cost (last run-results)))
;; => 58.88173980168068

;; 5. How much will be the real estate tax payment for the Year 15?
(:tax (last run-results))
;; => 1682.335422905162

;; 6. What will be the property management fee for Year 1?
(:mgmt-fee (second run-results))
;; => 32812.5

(:cash-flow (last run-results))


;; 7. What will be the total amount of fixed expenses in Year 1 that need to be covered by tree
;; cutting revenues? These include a property management fee, real estate tax and loan
;; interest.
(outflows (second run-results))
;; => -73462.5

;; 8. How many M 3 of wood will PGC need to cut and sell to finance these costs in Year 1?
(breakeven-vol (second run-results))
;; => 1646.218487394958


;; 9. How many M 3 of wood will PGC need to cut overall during the lifetime of the project?
;; Remember that no wood is cut in Year 15.
(apply + (map :amount-harvested (rest run-results)))
;; => 23007.53142426043

;; 10. What will be the project value at the end of Year 15?
(:value (last run-results))
;; => 4130766.132593218

;; 11. With the base case assumptions, what will be the IRR of the investment?
(irr (map :cash-flow run-results))
;; => 0.09350853

;; 12. “What is the maximum acquisition price that we can pay for the project and still deliver a
;; 9% IRR rate?”

(defn irr-calc [results] (irr (map :cash-flow results)))

(->> [1974813 1991516 2025854 2034080 2056746 2150514]
     (map #(vector % (irr-calc (run-model start 15 %)))))
;; => ([1974813 0.09618078]
;;     [1991516 0.094397046]
;;     [2025854 0.09087003]
;;     [2034080 0.0900515]
;;     [2056746 0.08784571]
;;     [2150514 0.07941351])

;; 13. “What happens if we hold the project for 25 years? What is the project IRR then?”
(irr-calc (run-model start 25 2000000))
;; => 0.08213526

;; 14. “What if the wood growth rate is not as good as we project? If it is 4% per year instead of
;; 5%, what is the project IRR then?
(irr-calc (run-model (assoc start :growth-rate 0.04) 15 2000000))
;; => 0.075277016

;; 15. “Let’s not cut the trees to finance operations but rather make equity injections every
;; year. We will sell the property for more money at the end of Year 15!” While it is true that
;; the property will be more expensive, will it be a better investment? What will be the
;; project IRR in this case?
(defn run-model-no-harvest [start sale-year aq-cost]
  (let [run (vec (->> (closing start aq-cost)
                      (iterate (comp (next-year-post no-harvest) next-year-pre))
                      (take sale-year)))]
    (conj run (sale (last run)))))

(map :cash-flow (run-model-no-harvest start 15 2000000))
;; => (-700625.0
;;     -73462.5
;;     -75817.6875
;;     -78338.7928125
;;     -81037.5700921875
;;     -83926.60751853281
;;     -87019.38650114465
;;     -90330.34508849782
;;     -93874.94587846854
;;     -97669.7487503009
;;     -101732.48876032261
;;     -106082.15956803085
;;     -110739.10278520088
;;     -115725.10366854678
;;     -121063.49360632218
;;     4620060.692379626)

(irr-calc (run-model-no-harvest start 15 2000000))
;; => 0.08106785

(def base-case (run-model start 15 2000000))
(second base-case)
;; => {:interest-rate 0.03,
;;     :net-sale-proceeds 73462.5,
;;     :value 2269350.0,
;;     :mgmt-fee 32812.5,
;;     :tax 1275.0,
;;     :volume 50853.78151260504,
;;     :growth-rate 0.05,
;;     :year 1,
;;     :cash-flow 0.0,
;;     :loan-amount 1312500.0,
;;     :interest 39375.0,
;;     :inflation 1.02,
;;     :amount-harvested 1646.218487394958,
;;     :cost 6.375,
;;     :growth 2500.0,
;;     :price 51.0}

(map :cash-flow base-case)
(nth base-case 15)
;; => {:interest-rate 0.03,
;;     :net-sale-proceeds 0.0,
;;     :value 4130766.132593218, OK
;;     :mgmt-fee 57853.867403266355, OK
;;     :tax 1682.335422905162, OK
;;     :volume 70153.601889245, 
;;     :growth-rate 0.05,
;;     :year 15,
;;     :cash-flow 2678047.268441114,
;;     :loan-amount 1312500.0, 
;;     :interest 39375.0, OK
;;     :inflation 1.02,
;;     :amount-harvested 0,
;;     :cost 8.411677114525812,
;;     :growth 3340.6477090116664,
;;     :price 67.2934169162065}


