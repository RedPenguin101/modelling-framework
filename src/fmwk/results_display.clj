(ns fmwk.results-display
  (:require [fmwk.tables :as t]
            [clojure.string :as str]
            [hiccup.core :refer [html]]))

(defn name->title [k]
  (when k (str/join " " (map str/capitalize (str/split (name k) #"-")))))

(defn- calculation-hierarchy [k]
  (if (qualified-keyword? k)
    (vec (str/split (namespace k) #"\."))
    []))

(def sheet (comp first calculation-hierarchy))
(def calc  (comp second calculation-hierarchy))

(def test-data
  '([:time.period/end-date ["TOTAL" "2022-06-30" "2022-09-30" "2022-12-31" "2023-03-31" "2023-06-30"]]
    [:balance-sheet.assets/cash ("- " "4,603" "5,835" "7,344" "9,190" "11,451")]
    [:balance-sheet.assets/receivables ("- " "1,233" "1,510" "1,848" "2,262" "2,768")]
    [:balance-sheet.assets/total-assets ("- " "5,836" "7,345" "9,191" "11,452" "14,219")]
    [:balance-sheet.liabilities/equity ("- " "- " "- " "- " "- " "- ")]
    [:balance-sheet.liabilities/retained-earnings ("- " "5,836" "7,345" "9,191" "11,452" "14,219")]
    [:balance-sheet.liabilities/total-liabilities ("- " "5,836" "7,345" "9,191" "11,452" "14,219")]))

(defn table->grouped-table [table]
  (->> table
       (group-by (comp calc first))
       (mapcat (fn [[grp rows]] (into [[grp]] rows)))))

(defn row-titles [table]
  (map #(update % 0 name->title) table))

;; html 

(defn apply-classes [row]
  (cond (= 1 (count row)) (map #(conj [:td.header] %) row)
        :else             (into [[:td.title (first row)]]
                                (map #(conj [:td.content] %) (rest row)))))

(defn row->table-row [row]
  (into [:tr] (apply-classes row)))

(defn table->html-table [table]
  (into [:table] (map row->table-row table)))

(defn remove-first-header [html-table]
  (into [:table] (drop 2 html-table)))

(defn remove-first-title [html-table]
  (assoc-in html-table [1 1 1] ""))

(defn results->html-table [results]
  (-> results
      t/series->row-wise-table
      table->grouped-table
      row-titles
      table->html-table
      remove-first-header
      remove-first-title))

(defn check-warning [checks]
  [:div.warning
   [:img#warning-icon {:src "./warning.png"}]
   [:p "Some of your checks are not passing. Print 'checks' to see which ones"]
   #_[:p (pr-str checks)]])

(defn html-table! [checks results]
  (spit
   "./results.html"
   (html
    [:html
     [:head [:link {:rel :stylesheet
                    :type "text/css"
                    :href "style.css"}]]
     [:body
      [:h1 (name->title (sheet (first (second results))))]
      (when (not-empty checks) (check-warning checks))
      (results->html-table results)]])))

(html-table! '({:period/end-date "2020-03-31", :checks/balance-sheet-balances false} {:period/end-date "2020-06-30", :checks/balance-sheet-balances false}) test-data)
