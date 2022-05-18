(ns fmwc.framework2
  (:require [ubergraph.core :as uber]
            [ubergraph.alg :as alg]
            [clojure.set :as set]
            [clojure.walk :as walk]))
;; Util fns
;;;;;;;;;;;;;;;;

(defn add-keys-as-name [m]
  (into {} (map (fn [[k v]] [k (assoc v :name k)]) m)))

(defn extract-deps [found [fst & rst]]
  (cond (nil? fst) found
        (vector? fst) (recur (conj found fst) rst)
        (coll? fst) (recur (into found (extract-deps [] fst)) rst)
        :else (recur found rst)))

(defn get-edges-no-self-ref [row-name row]
  (->> (:calculator row)
       (extract-deps [])
       (remove #(= :self (second %)))
       (map #(vector row-name (second %) {:relationship (first %)}))))

(defn get-edges-no-self-or-prev-ref [row-name row]
  (->> (:calculator row)
       (extract-deps [])
       (remove #(or (= :prev (first %))
                    (= :placeholder (first %))
                    (= :self (second %))))
       (map #(vector row-name (second %) {:relationship (first %)}))))

(defn dependency-graph [model]
  (uber/add-directed-edges*
   (uber/digraph)
   (mapcat #(apply get-edges-no-self-or-prev-ref %) model)))

(defn check-model-deps [model]
  (set/difference (set (uber/nodes (dependency-graph model)))
                  (set (keys model))))

(defn make-model [rows] (add-keys-as-name (apply merge rows)))

;; runing the model

(defn create-table [model]
  (update-vals model #(vector (:starter %))))

(defn replace-reference [[relative target] table period]
  (cond
    (= :placeholder relative) target
    (= :const relative) (first (table target))
    :else (nth (target table)
               (if (= relative :prev) (dec period) period))))

(comment
  (replace-reference [:placeholder 10] {} 5))

(defn replace-self-ref [nm [rel targ]]
  (if (= :self targ) [rel nm] [rel targ]))

(defn replace-refs-in-calc [calc replacements]
  (walk/postwalk
   #(if (vector? %) (replacements %) %)
   calc))

(defn run-calc [row table period]
  (let [deps (extract-deps [] (:calculator row))
        deps-with-self-repl (map #(replace-self-ref (:name row) %) deps)
        replacements (zipmap deps (map #(replace-reference % table period) deps-with-self-repl))]
    (try (eval (replace-refs-in-calc (:calculator row) replacements))
         (catch Exception e (throw (ex-info "Error calculating"
                                            {:name (:name row)
                                             :calc (:calculator row)
                                             :replaced-calc (replace-refs-in-calc (:calculator row) replacements)}))))))

(defn update-table [model row-name table period]
  (update table row-name conj (run-calc (row-name model)
                                        table
                                        period)))

(defn run [model table period row-names]
  (reduce (fn [table row-name]
            (update-table model row-name table period))
          table
          row-names))

(defn run2 [model periods]
  (let [graph-order (reverse (alg/topsort (dependency-graph model)))]
    (reduce (fn [table period]
              (run model table period graph-order))
            (create-table model)
            (range 1 (inc periods)))))

(defn dependency-order [model]
  (alg/topsort (dependency-graph model)))

(defn vizi-deps [model]
  (uber/viz-graph (dependency-graph model)))

(defn precedent-edges [full-graph node]
  (set (let [precs (uber/successors full-graph node)]
         (concat (map #(vector node %) precs)
                 (mapcat #(precedent-edges full-graph %) precs)))))

(defn trace-precedents [model row]
  (->> (precedent-edges (dependency-graph model) row)
       (uber/add-directed-edges* (uber/digraph))
       (uber/viz-graph)))

(defn dependent-edges [full-graph node]
  (set (let [precs (uber/predecessors full-graph node)]
         (concat (map #(vector node %) precs)
                 (mapcat #(dependent-edges full-graph %) precs)))))

(defn trace-dependents [model row]
  (->> (dependent-edges (dependency-graph model) row)
       (uber/add-directed-edges* (uber/digraph))
       (uber/viz-graph)))

(comment
  (trace-precedents fmwc.model/model :revenue/compound-degradation)
  (trace-dependents fmwc.model/model :inputs/aquisition-date))

(defn select-qualified-keys [m qualifiers]
  (let [qualifiers (set qualifiers)]
    (into {} (filter (fn [[k]] (qualifiers (keyword (namespace k)))) m))))

(defn output->table [model output table-name row-names]
  {table-name
   (for [k row-names]
     (into [(name k) (get-in model [k :units])] (output k)))})