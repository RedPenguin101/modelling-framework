(ns fmwk-test.fmwk-test
  (:require [clojure.test :refer [deftest is testing are]]
            [clojure.spec.alpha :as spec]
            [fmwk.irr :refer [irr]]
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
    :period-start-date '(if (= 1 [:flags/first-model-column])
                          [:inputs/model-start-date]
                          (add-days [:period-end-date :prev] 1))
    :period-end-date '(add-days (add-months [:period-start-date]
                                            [:inputs/length-of-operating-period])
                                -1)
    :end-of-operating-period '(end-of-month [:inputs/aquisition-date]
                                            (* 12 [:inputs/operating-years-remaining]))})

(def flags
  #:flags{:first-model-column '(if (= 1 [:time/model-column-number]) 1 0)
          :financial-close-period '(and (date>= [:inputs/aquisition-date]
                                                [:time/period-start-date])
                                        (date<= [:inputs/aquisition-date]
                                                [:time/period-end-date]))
          :financial-exit-period '(and (date>= [:time/end-of-operating-period]
                                               [:time/period-start-date])
                                       (date<= [:time/end-of-operating-period]
                                               [:time/period-end-date]))
          :operating-period '(and (date> [:time/period-start-date]
                                         [:inputs/aquisition-date])
                                  (date<= [:time/period-end-date]
                                          [:time/end-of-operating-period]))})

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
           :costs  '(* [:compound-inflation]
                       [:inputs/starting-costs])
           :profit '(- [:sale-price] [:costs])})

(def expenses
  (SUT/add-total
   #:expenses{:tax '(when-flag [:flags/operating-period]
                               (* [:prices/compound-inflation]
                                  [:inputs/starting-tax]))
              :interest '(* [:inputs/interest-rate]
                            [:debt.debt-balance/start])
              :management-fee '(if [:flags/operating-period]
                                 (* [:value/start]
                                    [:inputs/management-fee-rate])
                                 0)}))

(def debt
  (merge
   #:debt{:drawdown '(when-flag [:flags/financial-close-period]
                                (* [:inputs/ltv] [:value/end]))
          :repayment '(when-flag
                       [:flags/financial-exit-period]
                       [:debt.debt-balance/start])}
   (SUT/corkscrew :debt.debt-balance
                  [:debt/drawdown]
                  [:debt/repayment])))

(def volume
  (merge
   #:volume {:growth '(* [:volume.balance/start]
                         [:inputs/growth-rate])
             :harvest '(when-flag (and [:flags/operating-period]
                                       (not [:flags/financial-exit-period]))
                                  (/ [:expenses/total] [:prices/profit]))
             :purchased '(if [:flags/financial-close-period]
                           [:inputs/volume-at-aquisition] 0)}

   (SUT/corkscrew :volume.balance
                  [:volume/growth :volume/purchased]
                  [:volume/harvest])))

(def value
  {:value/start [:end :prev]
   :value/end '(* [:volume.balance/end]
                  [:prices/profit])})

(def closing
  #:capital.closing
   {:aquisition-cashflow '(when-flag
                           [:flags/financial-close-period]
                           [:inputs/purchase-price])
    :origination-fee '(when-flag
                       [:flags/financial-close-period]
                       (* [:inputs/origination-fee-rate]
                          [:debt/drawdown]))})

(def exit
  #:capital.exit
   {:sale-proceeds '(when-flag [:flags/financial-exit-period]
                               [:value/end])
    :disposition-fee '(when-flag
                       [:flags/financial-exit-period]
                       (* [:sale-proceeds] [:inputs/disposition-fee-rate]))})

(def cashflows
  (SUT/add-total
   :net-cashflow
   #:cashflows
    {:aquisition '(- [:debt/drawdown]
                     [:capital.closing/origination-fee]
                     [:capital.closing/aquisition-cashflow])
     :disposition '(- [:capital.exit/sale-proceeds]
                      [:capital.exit/disposition-fee]
                      [:debt/repayment])
     :gross-profit '(* [:prices/profit] [:volume/harvest])
     :expenses-paid '(- [:expenses/total])}))

(def model (SUT/build-and-validate-model
            inputs
            [time-calcs flags
             prices expenses closing
             debt volume value
             exit cashflows]))

(SUT/fail-catch (SUT/build-and-validate-model
                 inputs
                 [time-calcs flags
                  prices expenses closing
                  debt volume value
                  exit cashflows]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
         {:a 5, :b 10, :c 0})))

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
                        #"extract-refs: not an expression"
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
         '(Math/pow [:inputs/inflation-rate] [:hello/inflation-period] [:placeholder 7])))

  (is (= (SUT/de-localize-rows #:test-qual{:a [:b] :b [:other-qual/b :prev] :c [:placeholder 5]})
         #:test-qual{:a [:test-qual/b], :b [:other-qual/b :prev], :c [:placeholder 5]})))

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

(deftest model-validations
  (is (thrown-with-msg? Exception
                        #"Circular dependencies in model"
                        (SUT/build-and-validate-model {} [#:test{:a [:b] :b [:a]}])))
  (is (thrown-with-msg? Exception
                        #"Not all rows are expressions"
                        (SUT/build-and-validate-model {} [#:test{:a 1 :b [:a]}])))

  (is (thrown-with-msg? Exception
                        #"References to non-existant rows"
                        (SUT/build-and-validate-model {} [#:test{:a [:c] :b [:a]}])))
  (is (thrown-with-msg? Exception
                        #"Some model rows are not qualified"
                        (SUT/build-and-validate-model {} [{:a [:constant 1] :b [:a]}]))))

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

(deftest helpers
  (is (= (SUT/add-total {:start [:end :prev]
                         :increase [:other-thing/up]
                         :decrease [:other-thing/down]})
         {:start [:end :prev],
          :increase [:other-thing/up],
          :decrease [:other-thing/down],
          :total '(+ [:start] [:increase] [:decrease])}))

  (is (= (SUT/corkscrew "hello.world" [:other-thing/up] [:other-thing/down])
         #:hello.world{:start [:end :prev],
                       :increase [:other-thing/up],
                       :decrease '(- [:other-thing/down]),
                       :end '(+ [:start] [:increase] [:decrease])}))

  (is (= (SUT/corkscrew "hello.world" [:other-thing/up1 :other-thing/up2] [:other-thing/down1 :other-thing/down2])
         #:hello.world{:start [:end :prev],
                       :increase '(+ [:other-thing/up1] [:other-thing/up2]),
                       :decrease '(- (+ [:other-thing/down1] [:other-thing/down2])),
                       :end '(+ [:start] [:increase] [:decrease])})))

(deftest full-model-run
  (is (= (Math/round (:cashflows/net-cashflow (first (time (SUT/run-model model 16)))))
         2678047)))

(comment
  (def results (reverse (SUT/run-model model 25)))
  (irr (map :cashflows/net-cashflow results))

  1)