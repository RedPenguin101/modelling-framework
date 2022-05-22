(ns fmwk-test.fmwk-test
  (:require [clojure.test :refer [deftest is]]
            [fmwk.irr :refer [irr]]
            [fmwk.utils :refer :all]
            [fmwk-test.test-forest-model :as mtest]
            [fmwk.framework2 :as SUT]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest input-test
  (is (= (SUT/inputs->rows mtest/inputs)
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
  (is (= (Math/round (:cashflows/net-cashflow (last (time (SUT/run-model mtest/model 16)))))
         2678047)))

(comment
  (def results (SUT/run-model mtest/model 25))
  (irr (map :cashflows/net-cashflow results))

  1)