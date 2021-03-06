(ns fmwk.framework
  (:require [clojure.walk :refer [postwalk]]
            [clojure.set :as set]
            [ubergraph.core :as uber]
            [ubergraph.alg :as uberalg]
            [fmwk.table-runner :as tr]
            [fmwk.results-display :as display]
            [fmwk.outputs :as out]))

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

;; References and calculation expressions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; predicates for types of expression, for conditionals
(def atomic? (complement coll?))
(def expression? list?)
(defn placeholder? [ref] (and (vector? ref) (= :placeholder (first ref))))
(defn- constant-ref? [ref]
  (and (vector? ref) (#{:placeholder :constant :row-literal} (first ref))))
(def link? (every-pred vector? (complement constant-ref?)))
(def local-link? (every-pred link? (comp unqualified-keyword? first)))
(defn current-period-link? [ref] (and (link? ref) (= 1 (count ref))))
(defn input-link? [ref] (and (link? ref) (= (namespace (first ref)) "inputs")))

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

(defn placeholder-row-names [row-pairs]
  (set (keep (fn [[row-name expr]] (when (placeholder? expr) row-name)) row-pairs)))

(defn import-row-names [row-pairs]
  (set (keep (fn [[row-name expr]] (when (link? expr) row-name)) row-pairs)))

(defn- sum-expression [row-names]
  (reverse (into '(+) (map vector row-names))))

(defn- negative-sum-expression [row-names] (list '- (sum-expression row-names)))

;; Output rewriter
;;;;;;;;;;;;;;;;;;;;;

(defn rewrite-metric-ref [ref]
  (list 'rest (list ref '(into {} r))))

(defn rewrite-metric-expr [expr]
  (reverse
   (conj
    '([r] fn)
    (clojure.walk/postwalk
     #(if (link? %) (rewrite-metric-ref (first %)) %)
     expr))))

;; Builders
;;;;;;;;;;;;;;;;;;;;;;

(defn- calculation [calc-name & row-pairs]
  [calc-name (apply array-map row-pairs)])

(defn- implied-metadata [calc-name & row-pairs]
  (let [ph      (placeholder-row-names (apply array-map row-pairs))
        imports (import-row-names (apply array-map row-pairs))]
    (when (or (not-empty imports) (not-empty ph))
      [calc-name (merge-with merge
                             (zipmap ph (repeat {:placeholder true}))
                             (zipmap imports (repeat {:import true})))])))

(defn- totalled-calculation [calc-name total-name & row-pairs]
  ;; TODO: Implied metadata for totals
  (let [row-pairs (map vec (partition 2 row-pairs))
        total-expression (sum-expression (map first row-pairs))]
    [calc-name (into (array-map) (conj (vec row-pairs) [total-name total-expression]))]))

(defn- corkscrew [calc-name & row-pairs]
  (let [{:keys [increases decreases starter start-condition]} (apply hash-map row-pairs)]
    (calculation
     calc-name
     :start    [:end :prev]
     :increase (sum-expression increases)
     :decrease (negative-sum-expression decreases)
     :end      (if starter
                 (list 'if start-condition starter '(+ [:start] [:increase] [:decrease]))
                 '(+ [:start] [:increase] [:decrease])))))

(defn- base-case [case-name & row-pairs]
  [case-name (apply array-map row-pairs)])

(defn- check [& row-pairs]
  (apply calculation "checks" row-pairs))

(defn- metadata [calc-name & row-pairs]
  [calc-name (apply array-map row-pairs)])

(defn- bulk-metadata [calc-name mp calc]
  [calc-name (zipmap (keys calc) (repeat mp))])

(defn metric [nm expr]
  ["METRICS" [[nm expr]]])

(defn metric-meta [& row-pairs]
  ["METRICS" (apply hash-map row-pairs)])

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

(defn prep-metadata [md]
  (reduce (fn [m [k v]]
            (update m k merge v))
          {}
          (mapcat qualify-row-names md)))

(defn- compile-model [inputs calculations metadata metrics]
  (let [rows (mapcat qualify-all-references (into [(input->calculation inputs)] calculations))
        row-map (into {} rows)
        order (calculate-order row-map)]
    (cond (circular? row-map) (throw (ex-info "Circular dependencies in rows" row-map))
          (not-empty (bad-references row-map)) (throw (ex-info (str "References to non-existant rows" (bad-references row-map)) (bad-references row-map)))
          :else {:display-order (map first rows)
                 :rows row-map
                 :calculation-order order
                 :runner (eval (tr/make-runner order row-map))
                 :metrics (map #(update % 1 rewrite-metric-expr) (mapcat qualify-row-names metrics))
                 :meta (prep-metadata metadata)})))

;; Stateful wrappers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce calculation-store (atom []))
(defonce case-store (atom []))
(defonce meta-store (atom []))
(defonce metric-store (atom []))

(defn- get-calc! [calc-name]
  (get (into {} @calculation-store) calc-name))

(defn reset-model! []
  (reset! calculation-store [])
  (reset! case-store [])
  (reset! meta-store [])
  (reset! metric-store []))

(defn- add-calc! [calc] (swap! calculation-store conj calc))
(defn- add-case! [cas] (swap! case-store conj cas))
(defn- add-meta! [md] (swap! meta-store conj md))
(defn- add-metric! [metric] (swap! metric-store conj metric))

(defn calculation! [calc-name & row-pairs]
  (let [md (apply implied-metadata calc-name row-pairs)]
    (when md (add-meta! md))
    (add-calc! (apply calculation calc-name row-pairs))))

(defn totalled-calculation! [calc-name total-name & row-pairs]
  (add-meta! (apply implied-metadata calc-name row-pairs))
  (add-meta! [calc-name {total-name {:total-row true}}])
  (add-calc! (apply totalled-calculation calc-name total-name row-pairs)))

(defn corkscrew! [calc-name & row-pairs]
  (add-meta! [calc-name {:increase {:total true}
                         :decrease {:total true}
                         :end      {:total-row true}}])
  (add-calc! (apply corkscrew calc-name row-pairs)))

(defn base-case! [case-name & row-pairs]
  (add-case! (apply base-case case-name row-pairs)))

(defn metric! [& row-pairs]
  (add-metric! (apply metric row-pairs)))

(defn check! [calc-name & row-pairs]
  (add-calc! (apply check calc-name row-pairs)))

(defn metadata! [calc-name & row-pairs]
  (add-meta! (apply metadata calc-name row-pairs)))

(defn metric-meta! [& row-pairs]
  (add-meta! (apply metric-meta row-pairs)))

(defn bulk-metadata! [calc-name mp]
  (let [calc (get-calc! calc-name)]
    (add-meta! (bulk-metadata calc-name mp calc))))

;; Model running
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce model-store (atom {}))
(defonce results-store (atom []))
(defonce period-number-store (atom 0))
(defonce old-outputs (atom []))

(defn compile-model! []
  (compile-model (first @case-store) @calculation-store @meta-store @metric-store))

(defn run-model [{:keys [display-order calculation-order runner rows]} periods]
  (let [array (tr/make-init-table calculation-order rows periods)
        results (runner array calculation-order periods)]
    (map (juxt identity results) display-order)))

(defn model-changed? [m1 m2]
  (not= (dissoc m1 :runner :meta)
        (dissoc m2 :runner :meta)))

(defn rerun? [model periods options]
  (or (:force-rerun options) (not= periods @period-number-store) (model-changed? model @model-store)))

(defn compile-run-display! [periods options]
  (let [m (compile-model (first @case-store) @calculation-store @meta-store @metric-store)
        rr? (rerun? m periods options)]

    (if rr?
      (do (reset! model-store m)
          (reset! period-number-store periods)
          (println "Model changed, rerunning"))
      (println "Model unchanged, not rerunning"))

    (let [results (if rr? (reset! results-store (time (run-model m periods))) @results-store)
          new-outputs (out/calculate-outputs results m)
          outputs (out/collate-outputs new-outputs @old-outputs)
          outputs (if (:force-outputs options) outputs (out/filter-for-change outputs))]
      (when rr? (reset! old-outputs new-outputs))
      (display/print-result-summary! results outputs (assoc options :model m)))))
