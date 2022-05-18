(ns fmwc.framework2
  (:require [ubergraph.core :as uber]
            [ubergraph.alg :as alg]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [clojure.pprint :as pp]))

(def debug (atom nil))

;; Util fns
;;;;;;;;;;;;;;;;

(defn- add-keys-as-name [m]
  (into {} (map (fn [[k v]] [k (assoc v :name k)]) m)))

(defn- extract-deps [found [fst & rst :as expr]]
  (cond (vector? expr) (conj found expr)
        (nil? fst) found
        (vector? fst) (recur (conj found fst) rst)
        (coll? fst) (recur (into found (extract-deps [] fst)) rst)
        :else (recur found rst)))

(defn- get-edges-no-self-or-prev-ref [row-name row]
  (->> (:calculator row)
       (extract-deps [])
       (remove #(or (= :prev (first %))
                    (= :placeholder (first %))
                    (= :self (second %))))
       (map #(vector row-name (second %) {:relationship (first %)}))))

(defn- dependency-graph [model]
  (uber/add-directed-edges*
   (uber/digraph)
   (mapcat #(apply get-edges-no-self-or-prev-ref %) model)))

(defn check-model-deps [model]
  (set/difference (set (uber/nodes (dependency-graph model)))
                  (set (keys model))))

(defn make-model [rows] (add-keys-as-name (apply merge rows)))

;; runing the model

(defn- create-table [model]
  (update-vals model #(vector (:starter %))))

(defn- replace-reference [[relative target] table period]
  (swap! debug merge {:period period
                      :rel relative
                      :target target
                      :table table})
  (cond
    (= :placeholder relative) target
    (= :const relative) (first (table target))
    :else (nth (target table)
               (if (= relative :prev) (dec period) period))))

(comment
  (replace-reference [:placeholder 10] {} 5))

(defn- replace-self-ref [nm [rel targ]]
  (if (= :self targ) [rel nm] [rel targ]))

(defn- replace-refs-in-calc [calc replacements]
  (walk/postwalk
   #(if (vector? %) (replacements %) %)
   calc))

(defn- run-calc [row table period]
  (swap! debug assoc :row (:name row))
  (let [deps (extract-deps [] (:calculator row))
        deps-with-self-repl (map #(replace-self-ref (:name row) %) deps)
        replacements (zipmap deps (map #(replace-reference % table period) deps-with-self-repl))]
    (try (eval (replace-refs-in-calc (:calculator row) replacements))
         (catch Exception e (throw (ex-info "Error calculating"
                                            {:name (:name row)
                                             :calc (:calculator row)
                                             :replaced-calc (replace-refs-in-calc (:calculator row) replacements)
                                             :table-state table}))))))

(defn- update-table [model row-name table period]
  (update table row-name conj (run-calc (row-name model)
                                        table
                                        period)))

(defn- run [model table period row-names]
  (reduce (fn [table row-name]
            #_(println "running" row-name "for period" period)
            (update-table model row-name table period))
          table
          row-names))

(defn run-order [model]
  (let [top-sort (reverse (alg/topsort (dependency-graph model)))
        not-included (set/difference (set (keys model)) (set top-sort))]
    (concat top-sort not-included)))

(comment
  (sort (run-order fmwc.model.forest/model)))

(defn run2 [model periods]
  (let [graph-order (run-order model)]
    (reduce (fn [table period]
              (run model table period graph-order))
            (create-table model)
            (range 1 (inc periods)))))

;; Graph stuff
;;;;;;;;;;;;;;;;;;;;

(defn dependency-order [model]
  (alg/topsort (dependency-graph model)))

(defn vizi-deps [model]
  (uber/viz-graph (dependency-graph model)))

(defn- precedent-edges [full-graph node]
  (set (let [precs (uber/successors full-graph node)]
         (concat (map #(vector node %) precs)
                 (mapcat #(precedent-edges full-graph %) precs)))))

(defn trace-precedents [model row]
  (->> (precedent-edges (dependency-graph model) row)
       (uber/add-directed-edges* (uber/digraph))
       (uber/viz-graph)))

(defn- dependent-edges [full-graph node]
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

(comment
  (def graph (dependency-graph fmwc.model/model))
  (defn- leaves [graph] (filter #(zero? (uber/in-degree graph %)) (uber/nodes graph)))

  "Optimization idea: to do some diffing on models.
   * Do initial calc to get OUTPUT
   * adjust model
   * Store DIFF of model from previous version
   * get the DIFF-ROWS: those that are decendents of any of the diffs (or the diffs themselves)
   * recalc the OUTPUT, but only if the row is in the DIFF-ROWS"

  "Maybe column / record storage for table would be more efficient too")

;; Table stuff
;;;;;;;;;;;;;;;;;;;;;;

(defn- select-qualified-keys [m qualifiers]
  (let [qualifiers (set qualifiers)]
    (into {} (filter (fn [[k]] (qualifiers (keyword (namespace k)))) m))))

(def col-headers (into ["name" "unit" "open"] (map (partial str "period ") (range 1 500))))

(defn- output->vec-table [model output row-names]
  (for [k row-names]
    (into [(name k) (get-in model [k :units])] (output k))))

(defn- display-adjust-row [row]
  (cond (= "percent" (second row))
        (into (vec (take 3 row)) (map #(str (Math/round (float (* 100 %))) "%") (drop 3 row)))
        (float? (nth row 3))
        (into (vec (take 3 row)) (map #(Math/round %) (drop 3 row)))
        :else row))

(defn output->table [model output row-names]
  (map #(zipmap col-headers %)
       (map display-adjust-row (output->vec-table model output row-names))))

(defn print-table
  ([table] (print-table table [1 10]))
  ([table [start end]]
   (pp/print-table (into (vec (take 3 col-headers)) (take (inc (- end start)) (drop (+ 2 start) col-headers)))
                   table)))
