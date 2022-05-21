(ns fmwk-test.fmwk-test
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.spec.alpha :as spec]
            [fmwk.utils :refer :all]
            [fmwk.framework2 :as SUT]))

;;;;;;;;;;;;;;;;;;;;
;; Testing Model
;;;;;;;;;;;;;;;;;;;;

;; Time
;;;;;;;;;;;;;;;;;;;;

(def time-calcs
  #:time
   {:model-column-number '(inc [:model-column-number :prev])
    :first-model-column-flag '(if (= 1 [:model-column-number]) 1 0)
    :period-start-date '(if (= 1 [:inputs/first-model-column-flag])
                          [:inputs/model-start-date]
                          (add-days [:period-end-date :prev] 1))
    :period-end-date '(add-days (add-months [:period-start-date]
                                            [:inputs/length-of-operating-period])
                                -1)
    :financial-close-period-flag '(and (date>= [:inputs/aquisition-date]
                                               [:period-start-date])
                                       (date<= [:inputs/aquisition-date]
                                               [:period-end-date]))
    :financial-exit-period-flag '(and (date>= [:inputs/end-of-operating-period]
                                              [:period-start-date])
                                      (date<= [:inputs/end-of-operating-period]
                                              [:period-end-date]))
    :end-of-operating-period '(end-of-month [:inputs/aquisition-date]
                                            (* 12 [:inputs/operating-years-remaining]))
    :operating-period-flag '(and (date> [:period-start-date]
                                        [:inputs/aquisition-date])
                                 (date<= [:period-end-date]
                                         [:inputs/end-of-operating-period]))})

;; Inputs
;;;;;;;;;;;;;;;;;;;;

(def inputs
  #:inputs
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

;; Model
;;;;;;;;;;;;;;;;;;;;

(def prices
  #:prices{:inflation-period '(dec [:time/model-column-number])
           :compound-inflation '(Math/pow
                                 [:inputs/inflation-rate]
                                 [:inflation-period])
           :sale-price '(* [:compound-inflation]
                           [:inputs/starting-price])
           :costs {:calculator '(* [:compound-inflation]
                                   [:inputs/starting-price])}
           :profit '(- [:sale-price] [:costs])})

(def expenses
  #:expenses{:tax '(if [:time/operating-period-flag]
                     (* [:prices/compound-inflation]
                        [:inputs/starting-tax])
                     0)
             :interest '(* [:inputs/interest-rate]
                           [:debt/starting-debt])
             :management-fee '(if [:inputs/operating-period-flag]
                                (* [:debt/ending-value :prev]
                                   [:inputs/management-fee-rate])
                                0)
             :total '(+ [:management-fee] [:tax] [:interest])})

(def closing
  #:capital.closing
   {:aquisition-cashflow '(if [:financial-close-period-flag]
                            [:purchase-price]
                            0.0)
    :debt-drawdown '(if [:financial-close-period-flag]
                      (* [:ltv] [:ending-value])
                      0)
    :origination-fee '(if [:financial-close-period-flag]
                        (* [:origination-fee-rate] [:debt-drawdown])
                        0)
    :closing-cashflow '(- [:debt-drawdown]
                          [:origination-fee]
                          [:aquisition-cashflow])})


(def debt
  #:debt.debt-balance
   {:starting-debt  [:ending-debt :prev]
    :debt-increases [:debt-drawdown]
    :debt-decreases [:loan-repayment]
    :ending-debt    '(- (+ [:starting-debt]
                           [:debt-increases])
                        [:debt-decreases])})

(def volume
  #:volume.volume
   {:starting-volume [:ending-volume :prev]
    :growth '(* [:starting-volume]
                [:growth-rate])
    :harvest '(if (and [:operating-period-flag]
                       (not [:financial-exit-period-flag]))
                (/ [:expenses] [:profit])
                0)
    :ending-volume '(if [:financial-close-period-flag]
                      [:volume-at-aquisition]
                      (- (+ [:starting-volume]
                            [:growth])
                         [:harvest]))
    :ending-value '(* [:ending-volume]
                      [:profit])})

(def exit
  #:capital.exit
   {:sale-proceeds '(if [:financial-exit-period-flag]
                      [:ending-value]
                      0)
    :disposition-fee '(if [:financial-exit-period-flag]
                        (* [:sale-proceeds] [:disposition-fee-rate])
                        0)
    :loan-repayment '(if [:financial-exit-period-flag]
                       [:starting-debt]
                       0)
    :exit-cashflow '(- [:sale-proceeds] [:disposition-fee] [:loan-repayment])})

(def cashflows
  #:financial-statements.cashflows
   {:aquisition [:closing-cashflow]
    :disposition [:exit-cashflow]
    :gross-profit '(* [:profit] [:harvest])
    :expenses-paid '(- [:expenses])
    :net-cashflow '(+ [:closing-cashflow]
                      [:disposition]
                      [:gross-profit]
                      [:expenses-paid])})

(def calculations (merge time-calcs
                         prices
                         (SUT/inputs->rows inputs)))

(deftest input-test
  (is (= (SUT/inputs->rows inputs)
         #:inputs{:disposition-fee-rate       [:constant 0.01],
                  :inflation-rate             [:constant 1.02],
                  :purchase-price             [:constant 2000000.0],
                  :starting-costs             [:constant 6.25],
                  :aquisition-date            [:constant "2020-12-31"],
                  :sale-date                  [:constant "2035-12-31"],
                  :starting-price             [:constant 50.0],
                  :volume-at-aquisition       [:constant 50000.0],
                  :model-start-date           [:constant "2020-01-01"],
                  :growth-rate                [:constant 0.05],
                  :interest-rate              [:constant 0.03],
                  :management-fee-rate        [:constant 0.015],
                  :operating-years-remaining  [:constant 15],
                  :starting-tax               [:constant 1250],
                  :ltv                        [:constant 0.6],
                  :length-of-operating-period [:constant 12],
                  :origination-fee-rate       [:constant 0.01]})))

(deftest zero-period-test
  (is (= (SUT/zero-period {:a [:constant 5]
                           :b [:placeholder 10]
                           :c '(+ [:a] [:b])})
         {:a 5, :b 10, :c 0}))

  (is (= (SUT/zero-period calculations)
         {:prices/profit 0,
          :time/period-end-date 0,
          :time/financial-close-period-flag 0,
          :time/first-model-column-flag 0,
          :time/operating-period-flag 0,
          :inputs/disposition-fee-rate 0.01,
          :inputs/inflation-rate 1.02,
          :inputs/purchase-price 2000000.0,
          :time/model-column-number 0,
          :prices/inflation-period 0,
          :inputs/starting-costs 6.25,
          :inputs/aquisition-date "2020-12-31",
          :prices/sale-price 0,
          :inputs/sale-date "2035-12-31",
          :time/financial-exit-period-flag 0,
          :inputs/starting-price 50.0,
          :inputs/volume-at-aquisition 50000.0,
          :inputs/model-start-date "2020-01-01",
          :prices/costs 0,
          :inputs/growth-rate 0.05,
          :inputs/interest-rate 0.03,
          :inputs/management-fee-rate 0.015,
          :prices/compound-inflation 0,
          :inputs/operating-years-remaining 15,
          :inputs/starting-tax 1250,
          :inputs/ltv 0.6,
          :inputs/length-of-operating-period 12,
          :time/end-of-operating-period 0,
          :time/period-start-date 0,
          :inputs/origination-fee-rate 0.01})))

(deftest reference-extractions
  (is (= (SUT/extract-refs '(* [:compound-inflation]
                               [:inputs/starting-price]))
         [[:compound-inflation]
          [:inputs/starting-price]]))

  (is (= (SUT/extract-refs '(if [:financial-close-period-flag]
                              (* [:ltv] [:ending-value :prev])
                              0))
         [[:financial-close-period-flag] [:ltv] [:ending-value :prev]]))

  (is (thrown-with-msg? Exception
                        #"extract-refs: expression is a constant"
                        (SUT/extract-refs 123))))

(deftest replacing-locals

  (is (= (SUT/qualify-local-references "hello" [:placeholder 7])
         [:placeholder 7]))
  (is (= (SUT/qualify-local-references "hello" [:test 7])
         [:hello/test 7]))
  (is (= (SUT/qualify-local-references "hello" [:already.qualled/test 7])
         [:already.qualled/test 7]))
  (is (= (SUT/qualify-local-references "hello" 25)
         25))
  (is (= (SUT/qualify-local-references "hello"
                                       '(Math/pow [:inputs/inflation-rate] [:inflation-period] [:placeholder 7]))
         '(Math/pow [:inputs/inflation-rate] [:hello/inflation-period] [:placeholder 7]))))

(deftest replacing-references
  (is (= (SUT/replace-refs-in-expr [:hello] {:hello 5})
         5))
  (is (= (SUT/replace-refs-in-expr [:placeholder 10] {:hello 5})
         10))
  (is (= (SUT/replace-refs-in-expr '(+ [:foo] [:bar]) {:foo 5 :bar 10})
         '(+ 5 10))))

(deftest order-calc-test
  (is (= (SUT/calculate-order {:a '(inc [:c])
                               :b '(+ [:b :prev]
                                      [:a])
                               :c [:placeholder 4]})
         [:c :a :b])))

(deftest circular-test
  (is      (SUT/circular? {:a [:a]}))
  (is (not (SUT/circular? {:a [:b]})))
  (is      (SUT/circular? {:a [:b] :b [:a]}))
  (is (not (SUT/circular? {:a [:b] :b [:a :prev]}))))

(deftest reference-resolution
  (is (= (SUT/resolve-reference [:b] {:b 6} [{}]) 6))
  (is (= (SUT/resolve-reference [:placeholder 7] {} [{}]) 7))
  (is (= (SUT/resolve-reference [:b :prev] {:b 0} [{:b 8}]) 8)))

(deftest next-period-test
  (is (= (SUT/next-period [{:a 1 :b 2 :c 3}]
                          {:a [:placeholder 4]
                           :b '(inc [:a])
                           :c '(+ [:c :prev]
                                  [:b])}
                          [:a :b :c])
         {:a 4, :b 5, :c 8})))

