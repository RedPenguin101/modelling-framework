(ns fmwc.framework3
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is are testing]]
            [portal.api :as p]
            [ubergraph.core :as ug]
            [ubergraph.alg :as alg]
            [clojure.pprint :as pp]
            [fmwc.model.utils :refer :all]))

(comment
  (def p (p/open {:launcher :vs-code}))
  (add-tap #'p/submit))

;; utils
;;;;;;;;;;;;;;

(defn- expand [[k vs]] (map #(vector k %) vs))


;; calculations helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-deps
  ([expr] (extract-deps [] expr))
  ([found [fst & rst :as expr]]
   (cond (vector? expr) (conj found expr)
         (nil? fst) found
         (vector? fst) (recur (conj found fst) rst)
         (coll? fst) (recur (into found (extract-deps [] fst)) rst)
         :else (recur found rst))))

(defn exported-measures [calc]
  (keep #(when (:export (second %)) (first %)) (:rows calc)))

(defn- input-to-row [[nm input]]
  (vector nm
          (dissoc (assoc input
                         :starter (:value input)
                         :export true
                         :calculator [nm :prev])
                  :value)))

;; Calc validations
;;;;;;;;;;;;;;;;;;;;;;;;;
;; todo: only one export per calc?
;;       name must match calc?

(defn- name-matches-export? [calc]
  (if (= (:name calc) (first (exported-measures calc)))
    true
    (throw (ex-info (str "Calc name " (:name calc) " doesn't match exports " (vec (exported-measures calc)))
                    {:name (:name calc)
                     :exports (exported-measures calc)
                     :calc calc}))))

(defn- one-export? [calc]
  (if (= 1 (count (exported-measures calc)))
    true
    (throw (ex-info (str "Calc " (:name calc) " doesn't have 1 exports")
                    {:name (:name calc)
                     :exports (exported-measures calc)
                     :calc calc}))))

(defn- calc-has-keys [calc]
  (if (empty? (set/difference
               #{:name :category :rows}
               (set (keys calc))))
    true
    (throw (ex-info "Calc failed keyspec"
                    {:calc calc
                     :missing-keys (set/difference
                                    #{:name :category}
                                    (set (keys calc)))}))))

(defn- no-calc-refs-externals [calc]
  (let [permitted (set (concat (:import calc) (keys (:rows calc))))
        deps (set (map first (mapcat extract-deps (map :calculator (vals (:rows calc))))))]
    (if (empty? (set/difference deps permitted))
      true
      (throw (ex-info (str "Calc " (:name calc) " references unimported row(s) " (set/difference deps permitted))
                      {:calc calc
                       :unimported-row (set/difference deps permitted)})))))

(defn- every-row-has-calcultor [calc]
  (if (every? :calculator (vals (:rows calc)))
    true
    (throw (ex-info "Row is missing a calculator"
                    {:calc calc}))))

(defn calculation-validation-halting [calcs]
  (if (not= (count calcs) (count (set (map :name calcs))))
    (let [dups ((group-by #(= 1 (val %)) (frequencies (map :name calcs))) false)]
      (throw (ex-info (str "Duplicate names " (mapv first dups))
                      {:dups dups})))
    (map (every-pred calc-has-keys
                     name-matches-export?
                     one-export?
                     no-calc-refs-externals
                     every-row-has-calcultor)
         calcs)))

(defn- calculation-edges [calc]
  (keep
   #(when (= 1 (count (second %)))
      [(first %) (first (second %))])
   (mapcat expand (update-vals (:rows calc)
                               (comp extract-deps :calculator)))))

;; model validations
;;;;;;;;;;;;;;;;;;;;;
; no duplicate names

(defn- records->map [records ky]
  (into {} (map #(vector (ky %) %) records)))

(defn basic-model [calculations inputs]
  (hash-map :calculations (records->map calculations :name)
            :inputs inputs))

(defn exports [model]
  (mapcat exported-measures (vals (:calculations model))))

(defn- imports [model]
  (update-vals (:calculations model) :import))

(defn- import-export-mismatch?
  [model]
  (let [permissable (set (concat (keys (:inputs model)) (exports model)))]
    (for [[nm is] (imports model)]
      (if (empty? (set/difference (set is) permissable))
        true
        (throw (ex-info (str nm " contains import of unexported or undefined row " (set/difference (set is) permissable))
                        {:name nm}))))))

(defn- circular-deps? [model]
  (if (alg/dag? (:full-graph model))
    true
    (throw (ex-info (str "Model contains circular dependencies")
                    (:full-graph model)))))

(defn check-model-halting [model]
  (doall [(import-export-mismatch? model)
          (circular-deps? model)]))

(defn- full-dependency-graph [model]
  (ug/add-directed-edges* (ug/digraph) (mapcat calculation-edges (vals (:calculations model)))))

(defn extract-rows [model]
  (merge (into {} (mapcat :rows (vals (:calculations model))))
         (into {} (map input-to-row (:inputs model)))))

(defn- enrich-model [model]
  (let [fg (full-dependency-graph model)
        rows (extract-rows model)]
    (assoc model
           :full-graph fg
           :rows rows
           :calc-order (reverse (alg/topsort fg)))))

(defn build-model [calculations inputs]
  (let [basic (basic-model calculations inputs)]
    (enrich-model basic)))

(defn zero-period [model]
  (update-vals (:rows model) #(or (:starter %) 0)))

(defn- replace-refs-in-calc [calc replacements]
  (if (vector? calc)
    (replacements (first calc))
    (walk/postwalk
     #(if (vector? %) (replacements (first %)) %)
     calc)))

(defn next-period [[prv-rec] model]
  (reduce (fn [record row-name]
            (assoc record row-name
                   (let [calc (get-in model [:rows row-name :calculator])
                         reps (into {} (for [[t r] (extract-deps calc)]
                                         [t (if (= :prev r)
                                              (get prv-rec t)
                                              (get record t))]))]
                     #_(println "ROW:" row-name
                                "\ncalc:" calc
                                "\nreps:" reps
                                "\nnew-calc:" (replace-refs-in-calc calc reps))
                     (try (eval (replace-refs-in-calc calc reps))
                          (catch Exception _e (throw (ex-info (str "Error calculating " row-name)
                                                              {:name row-name
                                                               :calc calc
                                                               :replaced-calc (replace-refs-in-calc calc reps)})))))))
          {}
          (:calc-order model)))

(defn roll-model [prvs model] (conj prvs (next-period prvs model)))

(defn run-model [model periods]
  (reverse (loop [prv (list (zero-period model))
                  prd periods]
             (if (zero? prd)
               prv
               (recur (roll-model prv model) (dec prd))))))


;; Table printing

(defn transpose-records [records]
  (map #(zipmap (into [:name :starter] (map (fn [x] (str "period " x)) (range 1 (count records)))) %)
       (map flatten
            (reduce (fn [rs r]
                      (reduce #(update %1 (first %2) conj (second %2))
                              rs r))
                    (zipmap (keys (first records)) (repeat []))
                    records))))

(defn row-select [results row-names]
  (map #(select-keys % row-names) results))

(defn print-results [results [start end]]
  (pp/print-table
   (into [:name :starter] (map #(str "period " %) (range start (inc end))))
   (transpose-records results)))
