(ns fmwk.framework
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

(defn map-vals [f m] (update-vals m f))

(defn- expand
  "Where the value in a kv pair is a sequence, this function
   will 'unpack' the value. So 
     [:a [:b :c :d]]
   will become
     [[:a :b] [:a :c] [:a :d]]"
  [[k vs]] (map #(vector k %) vs))

;; Calculation hierarchy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn qualify [qualifier kw]
  (if (or (not qualifier) (= :placeholder kw) (qualified-keyword? kw)) kw
      (keyword (name qualifier) (name kw))))

(defn unqualify [kw] (keyword (name kw)))

(comment
  (qualify "hello.world" :foo)
  (qualify "hello.world" :other.ns/foo))

(defn calculation-hierarchy [k]
  (if (qualified-keyword? k)
    (vec (str/split (namespace k) #"\."))
    []))

(defn rows-in-hierarchy [hierarchy rows]
  (let [ch (str/split hierarchy #"\.")
        depth (count ch)]
    (filter #(= ch (take depth (calculation-hierarchy %))) rows)))

(comment
  (calculation-hierarchy :hello/world)
  (calculation-hierarchy :hello.hi/world)
  (calculation-hierarchy :world)

  (rows-in-hierarchy "hello" [:hello/world :hello.hi/world :foo/bar :baz])
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

(defn deps-graph [model]
  (uber/viz-graph (rows->graph model) {:auto-label true
                                       :save {:filename "graph.png" :format :png}}))

;; Model compiliation
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

(defn build-model2 [inputs calculations]
  (let [rows (build-and-validate-model inputs calculations)
        calc-order (calculate-order rows)]
    {:display-order (mapcat keys calculations)
     :rows rows
     :calculation-order calc-order
     :runner (eval (tr/make-runner calc-order rows))}))

;; Model running
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-model [{:keys [display-order calculation-order runner]} periods]
  (let [array (to-array-2d (vec (repeat (count calculation-order)
                                        (vec (repeat periods 0)))))
        results (runner array calculation-order periods)]
    (map (juxt identity results) display-order)))


;; Result selection and printing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rows-in-hierarchy "debt" (:display-order model))

(defn select-rows [results rows]
  (filter #((set rows) (first %)) results))

(defn select-periods [results from to]
  (map #(vector (first %) (take (- to from) (drop from (second %)))) results))

(defn round-collection [xs]
  (if (every? float? xs)
    (map #(Math/round %) xs)
    xs))

(defn round-results [results]
  (map #(update % 1 round-collection) results))

(defn print-table [results]
  (let [[hdr & rows] (series->row-wise-table results)]
    (pp/print-table hdr (map #(zipmap hdr %) rows))))

(defn print-category [results header category from to]
  (-> results
      (select-rows (conj (rows-in-hierarchy category (map first results)) header))
      (select-periods from to)
      round-results
      print-table))

(comment
  (require '[fmwk-test.test-forest-model :as f]
           '[fmwk.utils :refer :all])
  (def model (build-model2  f/inputs
                            [f/time-calcs f/flags f/prices
                             f/expenses f/debt f/volume
                             f/value f/closing f/exit f/cashflows]))

  (def results (time (run-model model 25)))
  (let [ks (into [:name] (range 0 10))]
    (pp/print-table ks (map #(zipmap ks %) (series->row-wise-table (select-rows results "debt")))))

  (-> results
      (select-rows (conj (rows-in-hierarchy "debt" (map first results)) :time/period-end-date))
      (select-periods 5 10)
      print-table))
