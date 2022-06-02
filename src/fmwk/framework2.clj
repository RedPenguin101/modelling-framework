(ns fmwk.framework2
  (:require [clojure.walk :refer [postwalk]]
            [clojure.set :as set]
            [ubergraph.core :as uber]
            [clojure.string :as str]
            [ubergraph.alg :as uberalg]
            [clojure.pprint :as pp]
            [fmwk.tables :refer [series->row-wise-table]]
            [fmwk.table-runner :as tr]))

;; utils
;;;;;;;;;;;;;;

(defmacro fail-catch
  "Wraps a try-catch for ex-info. Useful for debugging."
  [expr]
  (let [e 'e]
    `(try ~expr (catch clojure.lang.ExceptionInfo
                       ~e {:message (ex-message ~e)
                           :data (ex-data ~e)}))))

(defn- map-vals [f m] (update-vals m f))

(defn- expand
  "Where the value in a kv pair is a sequence, this function
   will 'unpack' the value. So 
     [:a [:b :c :d]]
   will become
     [[:a :b] [:a :c] [:a :d]]"
  [[k vs]] (map #(vector k %) vs))

;; Calculation hierarchy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def unqualified-keyword? (complement qualified-keyword?))

(defn- qualify [qualifier kw]
  (if (or (not qualifier) (= :placeholder kw) (qualified-keyword? kw)) kw
      (keyword (name qualifier) (name kw))))

(defn- calculation-hierarchy [k]
  (if (qualified-keyword? k)
    (vec (str/split (namespace k) #"\."))
    []))

(defn- rows-in-hierarchy [hierarchy rows]
  (let [ch (str/split hierarchy #"\.")
        depth (count ch)]
    (filter #(= ch (take depth (calculation-hierarchy %))) rows)))


;; References and calculation expressions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; predicates for types of expression, for conditionals
(def atomic? (complement coll?))
(def expression? list?)
(defn- constant-ref? [ref]
  (and (vector? ref) (#{:placeholder :constant :row-literal} (first ref))))
(def link? (every-pred vector? (complement constant-ref?)))
(def local-link? (every-pred link? (comp unqualified-keyword? first)))
(defn- current-period-link? [ref] (and (link? ref) (= 1 (count ref))))

(comment
  (atomic? [:this :prev]) ;; => false 
  (atomic? 10) ;; => true 
  (atomic? :this) ;; => true
  (constant-ref? [:placeholder 10]) ;; => :placeholder
  (constant-ref? [:this :prev]) ;; => nil
  (link? [:this :prev]) ;; => true
  (local-link? [:this :prev])  ;; => true
  (local-link? [:something/else :prev]) ;; => false
  )

(defn- extract-refs
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

(defn- qualify-local-references
  "Every link in an expression must be qualified. However when writing, users
   can choose to leave a link 'local' (i.e. unqualified). This function locates
   any local-links and qualifies them with the name of the calcluation they are
   in"
  [qualifier expr]
  (postwalk #(if (local-link? %) (update % 0 (partial qualify qualifier)) %)
            expr))

(comment
  (qualify-local-references "foo" [:bar])
  ;; => [:foo/bar]
  (qualify-local-references "foo" [:bar/baz])
  ;; => [:bar/baz]
  (qualify-local-references "foo" '(if [:bar/baz] [:hello :prev] [:world]))
  ;; => (if [:bar/baz] [:foo/hello :prev] [:foo/world])
  )

;; Graphing and dependecies
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rows->graph [rows]
  (->> (map-vals extract-refs rows)
       (map-vals #(filter current-period-link? %))
       (map-vals #(map first %))
       (mapcat expand)
       (uber/add-directed-edges* (uber/digraph))))

(defn- calculate-order [rows]
  (let [deps (reverse (uberalg/topsort (rows->graph rows)))]
    (into deps (set/difference (set (keys rows)) (set deps)))))

;; model validations
;;;;;;;;;;;;;;;;;;;;;

(defn- circular? [rows] (not (uberalg/dag? (rows->graph rows))))

(defn- bad-references [rows]
  (let [permitted (set (keys rows))]
    (into {} (remove (comp empty? val)
                     (update-vals rows (comp (partial remove permitted)
                                             (partial map first)
                                             (partial filter link?)
                                             extract-refs))))))

;; Model helpers
;;;;;;;;;;;;;;;;;;;;;;

(defn- sum-expression [rows]
  (reverse (into '(+) (map vector rows))))

(defn- negative-sum-expression [rows] (list '- (sum-expression rows)))

;; Builders
;;;;;;;;;;;;;;;;;;;;;;

(defn- calculation [calc-name & row-pairs]
  [calc-name (apply array-map row-pairs)])

(defn- totalled-calculation [calc-name total-name & row-pairs]
  (let [row-pairs (map vec (partition 2 row-pairs))
        total-expression (sum-expression (map first row-pairs))]
    [calc-name (into (array-map) (conj (vec row-pairs) [total-name total-expression]))]))

(defn- corkscrew [calc-name & row-pairs]
  (let [{:keys [increases decreases starter start-condition]} (apply hash-map row-pairs)]
    (totalled-calculation
     calc-name :end
     :start (if start-condition
              (list 'if start-condition starter [:end :prev]) [:end :prev])
     :increase (sum-expression increases)
     :decrease (negative-sum-expression decreases))))

(defn- base-case [case-name & row-pairs]
  [case-name (apply array-map row-pairs)])

(defn- check [& row-pairs]
  (apply calculation "checks" row-pairs))

(defn metadata [calc-name & row-pairs]
  [calc-name (apply array-map row-pairs)])

;; Calculation and input preparation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Up to this points all inputs to the model have had the format
;; [calc-name {:row-name1 expr1 :row-name2 expr2}]
;; Here we prepare them for compiliation

(defn- input-val [val]
  (if (atomic? val)
    (conj [:constant] val)
    val))

(defn- input->calculation
  "Conforms the pre-compiliation inputs (e.g. output from 'base-case') to
   to the standard spec for a pre-compiliation calcuation."
  [[_case-name rows]]
  ["inputs" (mapv #(update % 1 input-val) rows)])

(defn- qualify-row-names [[calc-name rows]]
  (map (fn [[row-name row-val]]
         (vector (qualify calc-name row-name)
                 row-val))
       rows))

(defn- qualify-all-references [[calc-name rows]]
  (map (fn [[row-name expr]]
         (vector (qualify calc-name row-name)
                 (qualify-local-references calc-name expr)))
       rows))

(defn- compile-model [inputs calculations metadata]
  (let [rows (mapcat qualify-all-references (into [(input->calculation inputs)] calculations))
        row-map (into {} rows)
        order (calculate-order row-map)]
    (cond (circular? row-map) (throw (ex-info "Circular dependencies in rows" row-map))
          (not-empty (bad-references row-map)) (throw (ex-info (str "References to non-existant rows" (bad-references row-map)) (bad-references row-map)))
          :else {:display-order (map first rows)
                 :rows row-map
                 :calculation-order order
                 :runner (eval (tr/make-runner order row-map))
                 :meta (into {} (mapcat qualify-row-names metadata))})))

;; Model running
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-model [{:keys [display-order calculation-order runner rows]} periods]
  (let [array (tr/make-init-table calculation-order rows periods)
        results (runner array calculation-order periods)]
    (map (juxt identity results) display-order)))


;; Stateful wrappers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce calculation-store (atom []))
(defonce case-store (atom []))
(defonce meta-store (atom []))

(defn reset-model! []
  (reset! calculation-store [])
  (reset! case-store []))

(defn- add-calc! [calc] (swap! calculation-store conj calc))
(defn- add-case! [cas] (swap! case-store conj cas))
(defn- add-meta! [md] (swap! meta-store conj md))

(defn calculation! [calc-name & row-pairs]
  (add-calc! (apply calculation calc-name row-pairs)))

(defn totalled-calculation! [calc-name total-name & row-pairs]
  (add-calc! (apply totalled-calculation calc-name total-name row-pairs)))

(defn corkscrew! [calc-name & row-pairs]
  (add-calc! (apply corkscrew calc-name row-pairs)))

(defn base-case! [case-name & row-pairs]
  (add-case! (apply base-case case-name row-pairs)))

(defn check! [calc-name & row-pairs]
  (add-calc! (apply check calc-name row-pairs)))

(defn metadata! [calc-name & row-pairs]
  (add-meta! (apply metadata calc-name row-pairs)))

(defn compile-model! []
  (compile-model (first @case-store) @calculation-store @meta-store))

;; Results Formatting
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(def counter-format (java.text.DecimalFormat. "0"))
(def ccy-format (java.text.DecimalFormat. "###,##0"))
(def ccy-cent-format (java.text.DecimalFormat. "###,##0.00"))

(defn- format-counter [x] (.format counter-format x))

(defn- format-ccy [x]
  (if (= (int (* 100 x)) 0)
    "- "
    (.format ccy-format x)))

(defn- format-ccy-thousands [x]
  (format-ccy (float (/ x 1000))))

(defn- format-ccy-cents [x]
  (if (= (int (* 100 x)) 0)
    "- "
    (.format ccy-cent-format x)))

(defn format-boolean [x]  (if x "✓" "⨯"))

(defn- format-percent [x] (format "%.2f%%" (* 100.0 x)))

(defn- round-collection [xs]
  (if (every? number? xs)
    (map format-ccy xs)
    xs))

(defn- display-format-series [xs unit]
  (case unit
    :counter            (map format-counter xs)
    :currency           (map format-ccy xs)
    :currency-thousands (map format-ccy-thousands xs)
    :currency-cents     (map format-ccy-cents xs)
    :percent            (map format-percent xs)
    :boolean            (map format-boolean xs)
    (round-collection xs)))

(defn- format-results [results metadata]
  (map #(update % 1
                display-format-series
                (get-in metadata [(first %) :units]))
       results))

;; Result selection and printing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- select-rows [results rows]
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

(defn- print-table [table]
  (let [[hdr & rows] table
        hdr (assoc hdr 1 "total")]
    (pp/print-table hdr (map #(zipmap hdr %) rows))))

(defn print-category
  ([results header category from to]
   (print-category results nil header category from to))
  ([results metadata header category from to]
   (let [tot (totals results (get-total-rows metadata))]
     (-> results
         (select-rows (conj (rows-in-hierarchy category (map first results)) header))
         (select-periods from to)
         (add-totals tot)
         (format-results metadata)
         (series->row-wise-table)
         print-table))))

(defn vizi [model]
  (uber/viz-graph (rows->graph (:rows model)) {:auto-label true
                                               :save {:filename "graph.png" :format :png}}))