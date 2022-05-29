(ns models.power-up
  (:require [fmwk.framework :as fw]
            [fmwk.tables :refer [series->records]]
            [fmwk.utils :refer :all]))

(def contract-activity
  [:moonshine :titan :evergreen :sky
   :one-foot :sputnik :evergreen2 :olympic :wildlife :planetarium])

(def contracts
  {:moonshine  {:materials  2890976
                :salaries   3395956
                :ovh-profit 1304539
                :total      7591471
                :completion [0 0 0 0 0 0 0.1 0.2 0.25 0.25 0.2 0]}
   :titan      {:materials 8436341
                :salaries 8916972
                :ovh-profit 3600813
                :total 20954126
                :completion [0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0	0	0	0]}
   :evergreen  {:materials 7078921
                :salaries 6821887
                :ovh-profit 2884417
                :total 16785225
                :completion [0	0	0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0	0]}
   :sky        {:materials  9543584
                :salaries   12290519
                :ovh-profit 4530576
                :total      26364679
                :completion [0	0	0	0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0]}
   :one-foot   {:materials 10637867
                :salaries 11845677
                :ovh-profit 4665336
                :total 27148880
                :completion [0	0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0	0	0]}
   :sputnik    {:materials 16935098
                :salaries 19125335
                :ovh-profit 7482540
                :total 43542973
                :completion [0	0.05	0.08	0.1	0.15	0.15	0.15	0.15	0.15	0.02	0	0]}
   :evergreen2 {:materials 4193313
                :salaries 5262673
                :ovh-profit 1962117
                :total 11418103
                :completion [0	0	0	0	0	0	0.1	0.2	0.25	0.25	0.2	0]}
   :olympic    {:materials 9373739
                :salaries 5521378
                :ovh-profit 3090737
                :total 17985854
                :completion [0	0	0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0	0]}
   :wildlife   {:materials 6011073
                :salaries 7906117
                :ovh-profit 2887817
                :total 16805007
                :completion [0	0	0	0	0.05	0.1	0.15	0.2	0.2	0.2	0.1	0]}
   :planetarium {:materials 3928424
                 :salaries 3068321
                 :ovh-profit 1451825
                 :total 8448570
                 :completion [0	0.1	0.2	0.25	0.25	0.2	0	0	0	0	0	0]}})

(defn contract-input [[contract-name contract]]
  (into (array-map)
        (let [qualifier (str "inputs.contracts." (name contract-name))]
          [[(keyword qualifier "completion") (into [:row-literal 0] (:completion contract))]
           [(keyword qualifier "materials") (:materials contract)]
           [(keyword qualifier "salaries") (:salaries contract)]
           [(keyword qualifier "revenue") (:total contract)]
           [(keyword qualifier "advance-month") (- 12 (count (drop-while zero? (:completion contract))))]])))

(def contract-inputs
  (let [inputs (apply merge (map contract-input (select-keys contracts contract-activity)))]
    (assoc inputs :inputs/total-contract-volume
           (reduce #(+ %1 (second %2))
                   0
                   (filter #(= "revenue" (name (first %))) inputs)))))

(def inputs
  (merge
   contract-inputs
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

     :capex-vol-1 50000000
     :capex-1     0
     :capex-vol-2 100000000
     :capex-2     5000000
     :capex-vol-3 150000000
     :capex-3     10000000
     :capex-4     20000000
     :capex-date  "2021-02-28"

     :capex-facility-ltv   0.8
     :capex-facility-rate  0.025
     :capex-repayment-term 60.0 ;months

     :existing-ppe-depreciation 3.0 ;years
     :new-ppe-depreciation      5.0 ;years

     :rcf-cap 12500000
     :rcf-rate 0.03
     :starting-rcf 2500000

     :starting-ppe       3600000
     :starting-share-cap 1000000
     :starting-holdback  5000000
     :starting-re        5100000}))

;; TIME
;;;;;;;;;;;;;;;;;;;;;;

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

;; Capex and PP&E
;;;;;;;;;;;;;;;;;;;;;

(def capex
  #:ppe.capex
   {:contract-vol            [:inputs/total-contract-volume]
    :for-year                '(cond (<= [:contract-vol] [:inputs/capex-vol-1])
                                    [:inputs/capex-1]
                                    (<= [:contract-vol] [:inputs/capex-vol-2])
                                    [:inputs/capex-2]
                                    (<= [:contract-vol] [:inputs/capex-vol-3])
                                    [:inputs/capex-3]
                                    :else
                                    [:inputs/capex-4])
    :capex-spend-period-flag '(date= [:period/end-date] [:inputs/capex-date])
    :spend                   '(when-flag [:capex-spend-period-flag] [:for-year])})

(def new-ppe-depreciation
  #:ppe.new
   {:depreciation-term-months '(* 12 [:inputs/new-ppe-depreciation])
    :in-depreciation-flag     '(date> [:period/start-date] [:inputs/capex-date])
    :new-capex                [:ppe.capex/for-year]
    :charge                   '(when-flag
                                [:in-depreciation-flag]
                                (/ [:new-capex]
                                   [:depreciation-term-months]))})

(def old-ppe-depreciation
  #:ppe.old
   {:depreciation-term-months '(* 12 [:inputs/existing-ppe-depreciation])
    :old-ppe-start            [:inputs/starting-ppe]
    :ppe-bf                   '(when-flag [:period/first-model-column]
                                          [:inputs/starting-ppe])
    :charge                   '(/ [:old-ppe-start]
                                  [:depreciation-term-months])})

;; hack - multiple decreases not working?
(def total-depr
  #:ppe.total-depr{:charge '(+ [:ppe.old/charge] [:ppe.new/charge])})

(def ppe-balance
  (fw/corkscrew
   "ppe.balance"
   [:ppe.capex/spend :ppe.old/ppe-bf]
   [:ppe.total-depr/charge]))

;; PPE Lease
;;;;;;;;;;;;;;;;;;;;;;

(def ppe-lease
  #:debt.lease
   {:amount                  '(* [:inputs/capex-facility-ltv]
                                 [:ppe.capex/for-year])
    :drawdown                '(* [:ppe.capex/spend]
                                 [:inputs/capex-facility-ltv])
    :interest                '(/ (* [:inputs/capex-facility-rate]
                                    [:debt.lease.balance/start])
                                 12) ;; TODO: Proper Act/365
    :repayment-term           [:inputs/capex-repayment-term]
    :in-repayment-period-flag '(date> [:period/start-date] [:inputs/capex-date])
    :repayment-amount         '(when-flag [:in-repayment-period-flag]
                                          (/ [:amount] [:repayment-term]))})

(def lease-corkscrew
  (fw/corkscrew "debt.lease.balance"
                [:debt.lease/drawdown]
                [:debt.lease/repayment-amount]))

;; RCF
;;;;;;;;;;;;;;;;;;;;;;

(def rcf
  #:debt.rcf
   {:interest       '(/ (* [:inputs/rcf-rate] [:debt.rcf.balance/start])
                        12) ;; TODO: Proper Act/365
    :rcf-balance-bf [:debt.rcf.balance/start]
    :cap            [:inputs/rcf-cap]
    :draw-capacity  '(- [:cap] [:rcf-balance-bf])
    :sweep          '(min [:draw-capacity] (- [:cashflows/before-rcf-sweep]))})

(def rcf-balance
  (fw/corkscrew-with-start
   "debt.rcf.balance"
   [:inputs/starting-rcf]
   [:period/first-model-column]
   [:debt.rcf/sweep]
   []))

;; Contracts
;;;;;;;;;;;;;;;;;;;;;;

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

;; Financial Statements
;;;;;;;;;;;;;;;;;;;;;;

(def ebitda
  (fw/add-total
   :EBITDA
   #:income.EBITDA
    {:revenues  [:contracts.accounting/revenue]
     :materials '(- [:contracts.material-expense/total])
     :labor     '(- [:contracts.salary-expense/total])
     :overhead  '(- [:inputs/overhead-per-month])}))

(def net-profit
  (fw/add-total
   :net-profit
   #:income
    {:EBITDA       [:income.EBITDA/EBITDA]
     :depreciation [:ppe.balance/decrease]
     :interest     '(- (+ [:debt.lease/interest]
                          [:debt.rcf/interest]))}))

(def cashflow-ops
  (fw/add-total
   :total
   #:cashflows.operations
    {:advances          [:contracts.advances/total]
     :contract-revenue  [:contracts.accounting/cash-from-invoices]
     :contract-expenses '(- (+ [:contracts.salary-expense/total] [:contracts.material-expense/total]))
     :overhead          '(- [:inputs/overhead-per-month])
     :holdbacks         '(if (date= [:inputs/holdback-release-date]
                                    [:period/end-date])
                           [:inputs/holdback-release-amount]
                           0)}))

(def cashflow-capital
  (fw/add-total
   :total
   #:cashflows.capital
    {:capex '(- [:ppe.capex/spend])}))

(def cashflow-finance
  (fw/add-total
   :total
   #:cashflows.finance
    {:interest-paid '(- (+ [:debt.lease/interest]
                           [:debt.rcf/interest]))
     :drawdown      '(+ [:debt.lease/drawdown])
     :repayment     '(- [:debt.lease/repayment-amount])}))

(def cashflow-before-rcf-sweep
  {:cashflows/before-rcf-sweep '(+ [:cashflows.operations/total]
                                   [:cashflows.finance/total]
                                   [:cashflows.capital/total])})

(def cashflow-total
  {:cashflows/rcf-sweep [:debt.rcf/sweep]
   :cashflows/total     '(+ [:cashflows/before-rcf-sweep]
                            [:rcf-sweep])})

(def bs-assets
  (fw/add-total
   :total-assets
   #:balance-sheet.assets
    {:cash                '(+ [:cash :prev] [:cashflows/total])
     :accounts-receivable '(+ [:accounts-receivable :prev]
                              [:contracts.accounting/accounts-receivable]
                              (- [:contracts.accounting/cash-from-invoices]))
     :holdbacks           '(if [:period/first-model-column]
                             [:inputs/starting-holdback]
                             (+ [:holdbacks :prev]
                                [:contracts.accounting/holdback-accrual]
                                (- [:cashflows.operations/holdbacks])))
     :ppe                 [:ppe.balance/end]}))

(def bs-liabs
  (fw/add-total
   :total-liabilities
   #:balance-sheet.liabilities
    {:advances          '(+ [:advances :prev]
                            [:contracts.advances/total]
                            (- [:contracts.accounting/advance-release]))
     :rcf               [:debt.rcf.balance/end]
     :leasing           [:debt.lease.balance/end]
     :share-capital     [:inputs/starting-share-cap]
     :retained-earnings '(if [:period/first-model-column]
                           (+ [:inputs/starting-re]
                              [:income/net-profit])
                           (+ [:retained-earnings :prev]
                              [:income/net-profit]))}))

(def bs-check #:balance-sheet.checks
               {:balance
                '(- [:balance-sheet.assets/total-assets]
                    [:balance-sheet.liabilities/total-liabilities])
                :cash-on-hand [:balance-sheet.assets/cash]
                :solvent '(>= [:cash-on-hand] 0)})

(def bs-meta (fw/add-meta (merge bs-assets bs-liabs) {:units :currency-thousands}))

(def calcs [periods bs-assets bs-liabs bs-check
            ebitda net-profit
            cashflow-ops cashflow-capital cashflow-finance cashflow-before-rcf-sweep cashflow-total
            contract-revenue contract-advances contract-accounting
            contract-expenses-salary contract-expenses-materials
            capex new-ppe-depreciation old-ppe-depreciation
            ppe-lease lease-corkscrew
            total-depr ppe-balance
            rcf rcf-balance])
(fw/fail-catch (fw/build-model2 inputs calcs))

(def model (fw/build-model2 inputs calcs [bs-meta]))

(def header :period/end-date)
(def results (time (fw/run-model model 13)))

(fw/print-category results (:meta model) header "income" 1 13)

(comment
  "Questions"
  "What are the PUBs projected revenues in July 2021?"
  (let [results (into {} results)]
    (nth (series->records (select-keys results [:income.EBITDA/revenues :period/end-date]))
         7))
  ;; => {:income.EBITDA/revenues 31482673.25, 
  ;;     :period/end-date "2021-07-31"}

  "2. What are the PUBs total overhead expenses in 2021?"
  (let [results (into {} results)]
    (apply + (:income.EBITDA/overhead results)))

  "3. What is the PUBs projected total EBITDA for 2021?"
  (let [results (into {} results)]
    (int (apply + (:income.EBITDA/EBITDA results))))
  ;; => 27,860,717

  "4. What is the PUBs total depreciation expense for 2021?"
  (let [results (into {} results)]
    (int (apply + (:income/depreciation results))))
  ;; => -4,533,333

  "5. How many advance payments will PUB receive in January 2021?"
  (let [results (into {} results)]
    (int (second (:contracts.advances/total results))))
  ;; => 7,294,566

  "6. What will be the PUBs outstanding balance of the Advances 
   Received at the end of February 2021? Remember that they will 
   receive some new advances in February. They will also cover a 
   part of the previously received advances with the work completed 
   in February"

  (let [results (into {} results)]
    (nth (series->records (select-keys results [:balance-sheet.liabilities/advances :period/end-date]))
         2))
  ;; => {:balance-sheet.liabilities/advances 9602483.705, 
  ;;     :period/end-date "2021-02-28"}

  "What will be PUBs accounts receivable balance on June 30, 2021?"
  (let [results (into {} results)]
    (nth (series->records (select-keys results [:balance-sheet.assets/accounts-receivable :period/end-date]))
         6))
  ;; => {:balance-sheet.assets/accounts-receivable 21,899,513.28 
  ;;     :period/end-date "2021-06-30"}

  "8. How much interest will PUB pay for the PP&E leasing in 2021?"
  (let [results (into {} results)]
    (int (apply + (:debt.lease/interest results))))
  ;; => 308,333
  )