(ns fmwc.solar)

(defn sum [inputs] (apply + inputs))

(defn block-run [[block-name & calcs] inputs]
  (for [calc cals]))

(defn calc-run [[calc-name & rows] inputs]
  (sum (for [row rows]
         ((first row) inputs))))

(calc-run [:ebidta [:electricity-generation-revenue]
           [:o-and-m]]
          {:electricity-generation-revenue 100
           :o-and-m 0})

[:income-statement
 [:ebidta
  [:electricity-generation-revenue]
  [:o-and-m]]

 [:ebit
  [:ebidta {:type :local-link}]
  [:depreciation-of-non-current-assets]]

 [:pbt
  [:ebit {:type :local-link}]
  [:snr-debt-interest]
  [:refi-interest]
  [:rcf-interest]]

 [:pat
  [:pbt {:type :local-link}]
  [:income-tax]]

 [:net-income
  [:pat {:type :local-link}]
  [:income-tax]]

 [:dividends
  [:net-income {:type :local-link}]
  [:dividends-paid]]]