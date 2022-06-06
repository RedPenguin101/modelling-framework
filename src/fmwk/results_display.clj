(ns fmwk.results-display
  (:require [fmwk.tables :as t]
            [fmwk.simple-viz.main :refer [series-lines-save]]
            [clojure.set :as set]
            [clojure.string :as str]
            [hiccup.core :refer [html]]))

;; calc-outputs - probably shouldn't be here

(defn- outputs [results outputs]
  (for [[_r f] outputs]
    (assoc f :result ((eval (:function f)) results))))

(defn- calculation-hierarchy [k]
  (if (qualified-keyword? k)
    (vec (str/split (namespace k) #"\."))
    []))

(defn- rows-in-hierarchy [hierarchy rows]
  (let [ch (str/split hierarchy #"\.")
        depth (count ch)]
    (filter #(= ch (take depth (calculation-hierarchy %))) rows)))

;; results check
;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn checks-failed? [record header]
  (let [failed-checks (filter #(false? (second %)) (dissoc record header))]
    (when (not-empty failed-checks)
      (into (select-keys record [header]) failed-checks))))

(defn check-results [results header]
  (let [check-rows (rows-in-hierarchy "checks" (map first results))]
    (keep #(checks-failed? % header)
          (t/series->records (select-keys (into {} results) (conj check-rows header))))))

;; Results Formatting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def counter-format  (java.text.DecimalFormat. "0"))
(def ccy-format      (java.text.DecimalFormat. "###,##0 ;(###,##0)"))
(def ccy-cent-format (java.text.DecimalFormat. "###,##0.00"))
(def factor-format   (java.text.DecimalFormat. "###,##0.0000"))

(defn- format-counter [x] (.format counter-format x))

(defn- format-ccy [x]
  (if (zero? (Math/round (* 1.0 x)))
    "-  "
    (.format ccy-format x)))

(defn- format-ccy-thousands [x]
  (format-ccy (float (/ x 1000))))

(defn- format-ccy-cents [x]
  (if (= (int (* 100 x)) 0)
    "- "
    (.format ccy-cent-format x)))

(defn format-factor [x]
  (.format factor-format x))

(defn- format-boolean [x]  (when (true? x) "✓"))
(defn- format-percent [x] (format "%.2f%%" (* 100.0 x)))

(defn- format-date [d]
  (when (string? d)
    (.format (java.time.format.DateTimeFormatter/ofPattern "dd MMM yy") (java.time.LocalDate/parse d))))

(defn- default-rounding [xs]
  (cond (every? number? xs) (mapv format-ccy xs)
        (every? boolean? (rest xs)) (mapv format-boolean xs)
        :else xs))

(defn- display-format [x unit]
  (case unit
    :counter            (format-counter x)
    :currency           (format-ccy x)
    :currency-thousands (format-ccy-thousands x)
    :currency-cents     (format-ccy-cents x)
    :percent            (format-percent x)
    :flag               (format-boolean x)
    :date               (format-date x)
    :factor             (format-factor x)
    x))

(defn- display-format-series [xs unit]
  (case unit
    :counter            (mapv format-counter xs)
    :currency           (mapv format-ccy xs)
    :currency-thousands (mapv format-ccy-thousands xs)
    :currency-cents     (mapv format-ccy-cents xs)
    :percent            (mapv format-percent xs)
    :flag               (mapv format-boolean xs)
    :date               (mapv format-date xs)
    :factor             (mapv format-factor xs)
    (default-rounding xs)))

(defn- format-results [results metadata]
  (mapv #(update % 1
                 display-format-series
                 (get-in metadata [(first %) :units]))
        results))

;; Results prep
;;;;;;;;;;;;;;;;;;;;;

(defn- hidden-rows [metadata]
  (keep (fn [[k v]] (when (:hidden v) k)) metadata))

(defn- select-rows
  "Order is determined by order of _results_, not rows"
  [results rows]
  (filter #((set rows) (first %)) results))

(defn- select-periods [results from to]
  (map #(vector (first %) (take (- to from) (drop from (second %)))) results))

(defn- get-total-rows [metadata]
  (set (filter #(get-in metadata [% :total]) (keys metadata))))

(defn- totals [results total-rows]
  (into {}
        (for [[r xs] results]
          [r (if (total-rows r) (apply + xs) 0)])))

(defn- add-totals [results totals]
  (for [[nm xs] results]
    [nm (into [(get totals nm 0)] xs)]))

(defn- add-total-label [table]
  (assoc-in (vec table) [0 1 0] "TOTAL"))

(defn- import-display [categories model-rows]
  (let [main-rows (mapcat #(rows-in-hierarchy % (keys model-rows)) categories)]
    (remove (set main-rows)
            (set (flatten (remove fw/input-link? (filter fw/current-period-link? (mapcat fw/extract-refs (vals (select-keys model-rows main-rows))))))))))

(defn display-rows-temp  [category metadata results header]
  (remove
   (set (hidden-rows metadata))
   (conj (rows-in-hierarchy category (map first results)) header)))

(defn- prep-results [results metadata display-rows from to]
  (let [tot (totals results (get-total-rows metadata))]
    (-> results
        (select-rows display-rows)
        (select-periods from to)
        (add-totals tot)
        (format-results metadata)
        (add-total-label))))

;; Table prep
;;;;;;;;;;;;;;;;;;;;

(defn name->title [k]
  (when k (str/join " " (map str/capitalize (str/split (name k) #"-")))))

(def sheet (comp first calculation-hierarchy))
(def calc  (comp second calculation-hierarchy))

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

(defn- graph-series [series]
  (let [filename "graph.png"]
    (series-lines-save series filename)
    filename))

(defn results-table [results]
  [:div
   [:h3 (name->title (sheet (first (second results))))]
   (results->html-table results)])

(defn outputs-block [results model]
  [:div
   [:h3 "Outputs"]
   (for [{:keys [result name units]} (outputs results (:outputs model))]
     [:p (str name ": " (display-format result units))])])

(def head
  [:head [:link {:rel :stylesheet
                 :type "text/css"
                 :href "style.css"}]])

(defn print-result-summary! [results {:keys [model sheets start periods header charts show-imports] :as options}]
  (let [start            (or start 1)
        end              (+ start (or periods 10))
        display-rows     (map #(display-rows-temp % (get-in options [:model :meta]) results header) sheets)
        import-rows      (conj (import-display sheets (get-in options [:model :rows]))
                               header)
        import-results   (prep-results results (get-in options [:model :meta]) import-rows start end)
        filtered-results (map #(prep-results results (get-in options [:model :meta]) % start end) display-rows)
        checks           (check-results results header)]
    (print import-rows)
    (spit
     "./results.html"
     (html [:html
            head
            [:body
             (when (not-empty checks) (check-warning checks))
             (when show-imports (results-table import-results))
             (for [r filtered-results]
               (results-table r))
             (when (and (:outputs options) (not-empty (:outputs model)))
               (outputs-block results (:model options)))
             (when (not-empty charts) [:img.graph {:src (graph-series (map (into {} results) charts))}])]]))))

(comment
  (def test-results (clojure.edn/read-string (slurp "test-results.edn")))
  (def test-results-check (clojure.edn/read-string (slurp "test-results-check-fail.edn")))

  (print-result-summary!
   test-results
   {:model nil
    :header :period/end-date
    :start 5
    :periods 12
    :sheets ["balance-sheet" "period"]
    :charts [:balance-sheet.assets/cash]}))