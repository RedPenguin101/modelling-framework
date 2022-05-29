(ns models.power-up
  (:require [fmwk.framework :as fw]
            [fmwk.utils :refer :all]))

(def contract-activity
  [:moonshine :titan :evergreen])

(def contracts
  {:moonshine {:materials  2890976
               :salaries   3395956
               :ovh-profit 1304539
               :total      7591471
               :completion [0 0 0 0 0 0 0 0.1 0.2 0.25 0.25 0.2 0]}
   :titan     {:materials 8436341
               :salaries 8916972
               :ovh-profit 3600813
               :total 20954126
               :completion [0 0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0	0	0	0]}
   :evergreen {:materials 7078921
               :salaries 6821887
               :ovh-profit 2884417
               :total 16785225
               :completion [0 0	0	0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0	0]}})

(defn contract-input [[contract-name contract]]
  (into (array-map)
        (let [qualifier (str "inputs.contracts." (name contract-name))]
          [[(keyword qualifier "completion") (into [:row-literal] (:completion contract))]
           [(keyword qualifier "materials") (:materials contract)]
           [(keyword qualifier "salaries") (:salaries contract)]
           [(keyword qualifier "revenue") (:total contract)]
           [(keyword qualifier "advance-month") (- 12 (count (drop-while zero? (:completion contract))))]])))

(def inputs
  (merge
   (apply merge (map contract-input contracts))
   #:inputs
    {:model-start-date "2021-01-01"
     :aquisition-date "2020-12-31"
     :sale-date "2035-12-31"
     :length-of-operating-period 1
     :overhead-per-month 500000
     :advance-payment 0.1
     :holdback 0.1
     :invoice-percent 0.8
     :holdback-release-amount 2500000
     :holdback-release-date "2021-06-30"
     :existing-ppe-depreciation 3 ;years
     :new-ppe-depreciation      5 ;years

     :starting-holdback 5000000
     :starting-re       5100000}))

(def periods
  #:period
   {:start-date               '(if (true? [:first-model-column])
                                 [:inputs/model-start-date]
                                 (add-days [:end-date :prev] 1))
    :end-date                 '(add-days
                                (add-months [:start-date]
                                            [:inputs/length-of-operating-period])
                                -1)
    :number                   '(inc [:number :prev])
    :first-model-column       '(= 1 [:number])})

(def contract-revenue
  (fw/add-total
   :total
   (into (array-map)
         (for [c contract-activity]
           (let [input-qualifier (str "inputs.contracts." (name c))]
             [(keyword "contracts.revenue" (name c))
              (list '* [(keyword input-qualifier "revenue")]
                    [(keyword input-qualifier "completion")])])))))

(def contract-expenses-salary
  (fw/add-total
   :total
   (into (array-map)
         (for [c contract-activity]
           (let [input-qualifier (str "inputs.contracts." (name c))]
             [(keyword "contracts.salary-expense" (name c))
              (list '* [(keyword input-qualifier "salaries")]
                    [(keyword input-qualifier "completion")])])))))

(def contract-expenses-materials
  (fw/add-total
   :total
   (into (array-map)
         (for [c contract-activity]
           (let [input-qualifier (str "inputs.contracts." (name c))]
             [(keyword "contracts.material-expense" (name c))
              (list '* [(keyword input-qualifier "materials")]
                    [(keyword input-qualifier "completion")])])))))

(def contract-advances
  (fw/add-total
   :total
   (into (array-map)
         (for [c contract-activity]
           (let [input-qualifier (str "inputs.contracts." (name c))]
             [(keyword "contracts.advances" (name c))
              (list 'if (list '= [(keyword input-qualifier "advance-month")]
                              '(month-of [:period/end-date]))
                    (list '* [:inputs/advance-payment]
                          [(keyword input-qualifier "revenue")])
                    0)])))))

(def contract-accounting
  #:contracts.accounting
   {:revenue             [:contracts.revenue/total]
    :advance-release     '(* [:revenue]
                             [:inputs/advance-payment])
    :holdback-accrual    '(* [:revenue]
                             [:inputs/holdback])
    :accounts-receivable '(* [:revenue]
                             [:inputs/invoice-percent])
    :acc-bal-check       '(- [:revenue] [:advance-release] [:holdback-accrual] [:accounts-receivable])
    :cash-from-invoices  [:accounts-receivable :prev]})

(def ebitda
  (fw/add-total :EBITDA
                #:income.EBITDA
                 {:revenues  [:contracts.accounting/revenue]
                  :materials '(- [:contracts.material-expense/total])
                  :labor     '(- [:contracts.salary-expense/total])
                  :overhead  '(- [:inputs/overhead-per-month])}))

(def net-profit
  (fw/add-total :net-profit
                #:income
                 {:EBITDA       [:income.EBITDA/EBITDA]
                  :depreciation [:placeholder 0]
                  :interest     [:placeholder 0]}))

(def cashflow
  (fw/add-total
   :from-operations
   #:cashflows
    {:advances          [:contracts.advances/total]
     :contract-revenue  [:contracts.accounting/cash-from-invoices]
     :contract-expenses '(- (+ [:contracts.salary-expense/total] [:contracts.material-expense/total]))
     :overhead          '(- [:inputs/overhead-per-month])
     :holdbacks         '(if (date= [:inputs/holdback-release-date]
                                    [:period/end-date])
                           [:inputs/holdback-release-amount]
                           0)}))

(def bs-assets
  (fw/add-total
   :total-assets
   #:balance-sheet.assets
    {:cash                '(+ [:cash :prev] [:cashflows/from-operations])
     :inventory           [:placeholder 0]
     :accounts-receivable '(+ [:accounts-receivable :prev]
                              [:contracts.accounting/accounts-receivable]
                              (- [:contracts.accounting/cash-from-invoices]))
     :holdbacks           '(if [:period/first-model-column]
                             [:inputs/starting-holdback]
                             (+ [:holdbacks :prev]
                                [:contracts.accounting/holdback-accrual]
                                (- [:cashflows/holdbacks])))
     :ppe                 [:placeholder 3600000]}))

(def bs-liabs
  (fw/add-total
   :total-liabilities
   #:balance-sheet.liabilities
    {:accounts-payable  [:placeholder 0]
     :advances          '(+ [:advances :prev]
                            [:contracts.advances/total]
                            (- [:contracts.accounting/advance-release]))
     :rcf               [:placeholder 2500000]
     :leasing           [:placeholder 0]
     :share-capital     [:placeholder 1000000]
     :retained-earnings '(if [:period/first-model-column]
                           (+ [:inputs/starting-re]
                              [:income/net-profit])
                           (+ [:retained-earnings :prev]
                              [:income/net-profit]))}))

(def bs-check {:balance-sheet/check '(- [:balance-sheet.assets/total-assets]
                                        [:balance-sheet.liabilities/total-liabilities])})

(def bs-meta (fw/add-meta (merge bs-assets bs-liabs) {:units :currency-thousands}))

(def calcs [periods bs-assets bs-liabs bs-check
            ebitda net-profit cashflow
            contract-revenue contract-advances contract-accounting
            contract-expenses-salary contract-expenses-materials])
(fw/fail-catch (fw/build-model2 inputs calcs))

(def model (fw/build-model2 inputs calcs [bs-meta]))

(def header :period/end-date)
(def results (fw/run-model model 20))

(fw/print-category results (:meta model) header "balance-sheet" 1 13)
(fw/print-category results (:meta model) header "cashflows" 1 13)
(fw/print-category results (:meta model) header "income" 1 13)
