(ns fmwk.framework2
  (:require [clojure.walk :refer [postwalk]]
            [clojure.spec.alpha :as spec]
            [clojure.set :as set]
            [ubergraph.core :as uber]
            [ubergraph.alg :as uberalg]
            [clojure.pprint :as pp]
            [fmwk.tables :refer [transpose-records records->series]]))

;; utils
;;;;;;;;;;;;;;

(defmacro fail-catch
  "Wraps a try-catch for ex-info. Useful for debugging."
  [expr]
  (let [e 'e]
    `(try ~expr (catch clojure.lang.ExceptionInfo
                       ~e {:message (ex-message ~e)
                           :data (ex-data ~e)}))))

(defn map-vals [f m] (update-vals m f))

(def unqualified-keyword?
  (every-pred (complement qualified-keyword?) keyword?))

(defn qualify [qualifier kw]
  (if (or (not qualifier) (= :placeholder kw) (qualified-keyword? kw)) kw
      (keyword (name qualifier) (name kw))))

(spec/fdef qualify
  :args (spec/cat :qualifier (spec/or :unqual-kw unqualified-keyword? :string string?)
                  :keyword   unqualified-keyword?)
  :ret qualified-keyword?)

(defn unqualify [kw] (keyword (name kw)))

(defn- expand
  "Where the value in a kv pair is a sequence, this function
   will 'unpack' the value. So 
     [:a [:b :c :d]]
   will become
     [[:a :b] [:a :c] [:a :d]]"
  [[k vs]] (map #(vector k %) vs))

(comment
  (qualify "hello.world" :foo)
  (qualify "hello.world" :other.ns/foo))

(defn select-keys-with-qualifier [qualifier ks]
  ((group-by namespace ks) qualifier))

(comment
  (select-keys-with-qualifier "hello" [:hello/world :foo/bar :baz])
  ;; => [:hello/world]
  )

;; References and calculation expressions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; predicates for types of expression, for conditionals
(def atomic? (complement coll?))
(def expression? list?)
(defn constant-ref? [ref] (and (vector? ref) (#{:placeholder :constant} (first ref))))
(def link? (every-pred vector? (complement constant-ref?)))
(defn current-period-link? [ref] (and (link? ref) (= 1 (count ref))))
(defn previous-period-link? [ref] (and (link? ref) (= :prev (second ref))))

;; idea is to use this for better circularity detection later - i.e if it's circular,
;; but only because this is a link to previous, that's fine. But can't think it through now
(def link-to-prv? (every-pred link? previous-period-link?))

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

(defn qualify-local-references [qualifier expr]
  (postwalk #(if (link? %) (update % 0 (partial qualify qualifier)) %)
            expr))

(defn replace-refs-in-expr [expr replacements]
  (postwalk #(cond (constant-ref? %) (second %)
                   (link? %) (replacements (first %))
                   :else %)
            expr))

;; Row and calculation helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn inputs->rows [inputs]
  (update-vals inputs #(conj [:constant] %)))

;; calculations helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Calc validations
;;;;;;;;;;;;;;;;;;;;;;;;;

;; model helpers
;;;;;;;;;;;;;;;;;;;;;

;; Model dependecies
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rows->graph [rows]
  (->> (map-vals extract-refs rows)
       (map-vals #(filter current-period-link? %))
       (map-vals #(map first %))
       (mapcat expand)
       (uber/add-directed-edges* (uber/digraph))))

(defn rows->graph-with-prevs [rows]
  (->> (map-vals extract-refs rows)
       (map-vals #(filter link? %))
       (map-vals #(map first %))
       (mapcat expand)
       (uber/add-directed-edges* (uber/digraph))))

(defn calculate-order [rows]
  (let [deps (reverse (uberalg/topsort (rows->graph rows)))]
    (into deps (set/difference (set (keys rows)) (set deps)))))

(defn node-ancestors [graph node visited]
  (let [s (set/difference (set (uber/successors graph node)) visited)]
    (set (into s (mapcat #(node-ancestors graph % (set/union visited s)) s)))))

(defn precendents [rows nodes]
  (let [actual-scope (->> nodes
                          (mapcat #(node-ancestors (rows->graph-with-prevs rows) % #{}))
                          (into nodes)
                          set)]
    (keep actual-scope (calculate-order rows))))

(comment
  (def test-graph (uber/digraph [:a :b] [:a :c] [:c :d] [:e :b]))
  (uber/pprint test-graph)
  (node-ancestors test-graph :a #{})

  (def test-graph-circ (uber/digraph [:a :b] [:a :c] [:c :d] [:e :b] [:d :a]))
  (uber/viz-graph test-graph-circ)

  (node-ancestors test-graph-circ :a #{})

  (sort (precendents model [:debt/drawdown])))

;; model validations
;;;;;;;;;;;;;;;;;;;;;

(defn circular? [rows] (not (uberalg/dag? (rows->graph rows))))

(defn all-rows-are-exprs? [rows]
  (every? coll? (vals rows)))

(defn bad-references [rows]
  (let [permitted (set (keys rows))]
    (into {} (remove (comp empty? val)
                     (update-vals rows (comp (partial remove permitted)
                                             (partial map first)
                                             (partial filter link?)
                                             extract-refs))))))

(defn consistent-qualifier? [rows]
  (apply = (map namespace (keys rows))))

(comment
  (consistent-qualifier? {:hello 1 :world 2})
  (consistent-qualifier? #:test{:hello 1 :world 2})
  (consistent-qualifier? {:test1/hello 1 :test2/world 2}))

(defn all-rows-qualified? [rows]
  (every? qualified-keyword? (keys rows)))

(comment
  (all-rows-qualified? {:hello 1 :world 2})
  (all-rows-qualified? #:test{:hello 1 :world 2})
  (all-rows-qualified? {:test1/hello 1 :test2/world 2}))

;; Model building
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn de-localize-rows [rows]
  (into {} (map (fn [[k v]] [k (qualify-local-references (namespace k) v)]) rows)))

(defn build-model [inputs calculations]
  (de-localize-rows (apply merge (inputs->rows inputs) calculations)))

(defn build-and-validate-model [inputs calculations]
  (when (not (all-rows-are-exprs? (apply merge calculations)))
    (throw (ex-info "Not all rows are expressions" {})))
  (let [model (build-model inputs calculations)]
    (when (circular? model)
      (throw (ex-info "Circular dependencies in model" model)))
    (when (not-empty (bad-references model))
      (throw (ex-info "References to non-existant rows" (bad-references model))))
    (when (not (all-rows-qualified? model))
      (throw (ex-info "Some model rows are not qualified" {:unqualified-kw (remove qualified-keyword? (keys model))})))
    model))

;; Model running
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn zero-period
  [rows]
  (update-vals rows #(if (constant-ref? %) (second %) 0)))

(defn resolve-reference [ref this-record [previous-record]]
  (cond (constant-ref? ref)        (second ref)
        (previous-period-link? ref) (get previous-record (first ref))
        (current-period-link? ref)  (get this-record (first ref))))

(defn next-period [prv-recs rows calc-order]
  (reduce (fn [record row-name]
            (let [refs (extract-refs (row-name rows))]
              (try
                (->> (map #(resolve-reference % record prv-recs) refs)
                     (zipmap (map first refs))
                     (replace-refs-in-expr (row-name rows))
                     eval
                     (assoc record row-name))
                (catch Exception _e
                  (throw (ex-info (str "Error calculating " row-name)
                                  {:name row-name
                                   :calc (row-name rows)
                                   :replaced-calc (replace-refs-in-expr (row-name rows) (zipmap (map first refs) (map #(resolve-reference % record prv-recs) refs)))}))))))
          {}
          calc-order))

(defn roll-model [prvs rows order] (conj prvs (next-period prvs rows order)))

(defn run-model [rows periods]
  (reverse
   (let [order (calculate-order rows)]
     (loop [records (list (zero-period rows))
            prd periods]
       (if (zero? prd)
         records
         (recur (roll-model records rows order) (dec prd)))))))

(defn run-model-for-rows [rows periods rows-to-run]
  (reverse
   (let [order (precendents rows rows-to-run)]
     (loop [records (list (select-keys (zero-period rows) order))
            prd periods]
       (if (zero? prd)
         records
         (recur (roll-model records rows order) (dec prd)))))))

(comment
  (run-model-for-rows model 10 [:prices/sale-price]))

;; Model helpers
;;;;;;;;;;;;;;;;;;;;;;

(defn add-total
  ([calculation] (add-total :total calculation))
  ([total-name calculation]
   (if (qualified-keyword? total-name)
     (throw (ex-info "add-total: total name can't be qualified"
                     {:total-name total-name}))
     (assoc calculation (qualify (namespace (ffirst calculation)) total-name)
            (reverse (into '(+) (map vector (map unqualify (keys calculation)))))))))

(defn ref-sum [refs]
  (if (= 1 (count refs))
    (vec refs)
    (cons '+ (map vector refs))))

(ref-sum [:hello :world])

(defn corkscrew [qualifier increases decreases]
  (update-keys (add-total :end
                          {:start [:end :prev]
                           :increase (ref-sum increases)
                           :decrease (list '- (ref-sum decreases))})
               (partial qualify qualifier)))

;; Table printing and model selection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn round [x] (if (int? x) x (Math/round x)))

(defn round-results [results]
  (let [series (fmwk.tables/records->series results)]
    (fmwk.tables/series->records (update-vals series #(if (number? (second %)) (mapv round %) %)))))

(defn slice-period [sheet period]
  (select-keys period (select-keys-with-qualifier sheet (keys period))))

(defn print-results [results [start end]]
  (pp/print-table (into [:name] (range start (inc end)))
                  (transpose-records (round-results results))))

(defn slice-results [results sheet-name period-range]
  (print-results (map #(slice-period sheet-name %)
                      results) period-range))


(comment
  (require '[fmwk-test.test-forest-model :as mtest]
           '[fmwk.utils :refer :all])
  (def model mtest/model)
  (def results (time (run-model model 10)))
  (second results)

  (round-results results)

  (let [series (fmwk.tables/records->series results)]
    (fmwk.tables/series->records (update-vals series #(if (number? (second %)) (map round %) %))))

  (select-keys (first results)
               (select-keys-with-qualifier "flags" (keys model)))

  (doall [(slice-results results "prices" [1 5])
          (slice-results results "time" [1 5])
          (slice-results results "cashflows" [1 5])])

  (slice-results results "capital.closing" [1 5])
  (slice-results results "debt.debt-balance" [1 5])

  (map :cashflows/net-cashflow results)

  (records->series results))
