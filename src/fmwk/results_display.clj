(ns fmwk.results-display
  (:require [fmwk.tables :as t]
            [fmwk.simple-viz.main :refer [series-lines-save]]
            [clojure.string :as str]
            [hiccup.core :refer [html]]))

;; bleh, stealing from framework TODO find better way to do this
;; predicates for types of expression, for conditionals
(def expression? list?)
(defn- constant-ref? [ref]
  (and (vector? ref) (#{:placeholder :constant :row-literal} (first ref))))
(def link? (every-pred vector? (complement constant-ref?)))
(defn current-period-link? [ref] (and (link? ref) (= 1 (count ref))))
(defn input-link? [ref] (and (link? ref) (= (namespace (first ref)) "inputs")))
(defn extract-refs
  "Given an expression containing references (defined as a vector), 
   will extract all of the references and return them as a vector"
  ([expr] (if (coll? expr)
            (extract-refs [] expr)
            (throw (ex-info "extract-refs: not an expression" {:expr expr}))))
  ([found [fst & rst :as expr]]
   (cond (or (constant-ref? expr) (link? expr)) (conj found expr)
         (nil? fst) found
         (link? fst) (recur (conj found fst) rst)
         (expression? fst) (recur (into found (extract-refs [] fst)) rst)
         :else (recur found rst))))


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



(def counter-format  (java.text.DecimalFormat. "0 "))
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

(defn- format-boolean [x]  (when (true? x) "✓        "))
(defn- format-percent [x] (if (zero? x) "-  " (format "%.2f%%" (* 100.0 x))))

(defn- format-date [d]
  (when (string? d)
    (.format (java.time.format.DateTimeFormatter/ofPattern "dd MMM yy") (java.time.LocalDate/parse d))))

(defn- default-rounding [xs]
  (cond (every? number? xs) (mapv format-ccy xs)
        (every? boolean? (rest xs)) (mapv format-boolean xs)
        :else xs))

(def unit-key->display
  {:currency-thousands "$'000"
   :currency           "$"
   :currency-cents     "$"
   :counter            "Counter"
   :percent            "Percent"
   :flag               "Flag"
   :date               "Date"
   :factor             "Factor"})

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
  "Order is determined by order of rows, not results"
  [results rows]
  (let [results-map (into {} results)]
    (for [row rows]
      (vector row (get results-map row)))))

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

(defn- add-units-label [table]
  (assoc-in (vec table) [0 1 0] "UNITS"))

(defn add-units [results metadata]
  (vec (for [[r vs] results]
         [r (vec (concat [(or (get-in metadata [r :units-display]) (unit-key->display (get-in metadata [r :units])))] vs))])))

(defn- import-rows [categories model-rows]
  (let [main-rows (mapcat #(rows-in-hierarchy % (keys model-rows)) categories)]
    (remove (set main-rows)
            (set (flatten (remove input-link? (filter current-period-link? (mapcat extract-refs (vals (select-keys model-rows main-rows))))))))))

(defn display-rows [category results]
  (rows-in-hierarchy category (map first results)))

(defn remove-hidden-rows [results metadata]
  (select-rows results (remove (set (hidden-rows metadata))
                               (map first results))))

(defn- prep-results [results metadata display-rows from to]
  (let [tot (totals results (get-total-rows metadata))]
    (-> results
        (select-rows display-rows)
        (remove-hidden-rows metadata)
        (select-periods from to)
        (add-totals tot)
        (format-results metadata)
        (add-total-label)
        (add-units metadata)
        (add-units-label))))

;; Table prep
;;;;;;;;;;;;;;;;;;;;

(defn name->title [k]
  (when k (str/join " " (map str/capitalize (str/split (str/replace-first (name k) "." ":-") #"-")))))

(def sheet (comp first calculation-hierarchy))
(def calc  (comp second calculation-hierarchy))

(defn table->calc-grouped-table [table]
  (->> table
       (group-by (comp namespace first))
       (mapcat (fn [[grp rows]] (into [[grp]] rows)))))

;; html 

(defn apply-classes [row]
  (cond (= 1 (count row)) (map #(conj [:td.header] %) row)
        :else             (into [[:td.title (first row)]]
                                (map #(conj [:td.content] %) (rest row)))))

(defn row->table-row [row metadata]
  (let [phs (set (keep (fn [[rn m]] (when (:placeholder m) rn)) metadata))
        totals (set (keep (fn [[rn m]] (when (:total-row m) rn)) metadata))]
    (into (cond (phs (first row)) [:tr.placeholder]
                (totals (first row)) [:tr.total-row]
                :else [:tr])
          (apply-classes (update row 0 name->title)))))

(defn table->html-table [table metadata]
  (into [:table] (map #(row->table-row % metadata) table)))

(defn remove-first-header [html-table]
  (into [:table] (drop 2 html-table)))

(defn remove-first-title [html-table]
  (assoc-in html-table [1 1 1] ""))

(defn results->html-table [results metadata]
  (-> results
      t/series->row-wise-table
      table->calc-grouped-table
      (table->html-table metadata)
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

(defn results-table [results header metadata]
  [:div
   [:h3 header]
   (results->html-table results metadata)])

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
        metadata         (get-in options [:model :meta])
        display-rows     (map #(conj (display-rows % results) header) sheets)
        filtered-results (map #(prep-results results metadata % start end) display-rows)
        import-rows      (conj (import-rows sheets (get-in options [:model :rows])) header)
        import-results   (prep-results results metadata import-rows start end)
        checks           (check-results results header)]
    (spit
     "./results.html"
     (html [:html
            head
            [:body
             (when (not-empty checks) (check-warning checks))
             (when show-imports (results-table import-results "Imports" metadata))
             (for [r filtered-results]
               (results-table r (name->title (sheet (first (second r)))) metadata))
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