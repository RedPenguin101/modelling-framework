(ns fmwk.framework
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [portal.api :as p]
            [ubergraph.core :as uber]
            [ubergraph.alg :as uber-alg]
            [clojure.pprint :as pp]
            [fmwk.utils :as u]))

(comment
  (def p (p/open {:launcher :vs-code}))
  (add-tap #'p/submit))

;; utils
;;;;;;;;;;;;;;

(defn- expand
  "Where the value in a kv pair is a sequence, this function
   will 'unpack' the value. So 
     [:a [:b :c :d]]
   will become
     [[:a :b] [:a :c] [:a :d]]"
  [[k vs]] (map #(vector k %) vs))


;; Row and calculation helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- refs-previous-period? [row-ref] (= :prev (second row-ref)))
(defn- placeholder? [row-ref] (= :placeholder (first row-ref)))
(def refs-current-period? (complement (some-fn placeholder? refs-previous-period?)))

(defn- current-period-refs [refs]
  (keep #(when (refs-current-period? %) (first %)) refs))

(defn- input-to-row
  "Inputs are are defined by the user using a different spec from calculation
   rows. However internally to the model they are simply rows where every period
   value is the constant provided. This function transforms an input spec to
   a row spec for storing in the model"
  [[nm input]]
  (vector nm
          (dissoc (assoc input
                         :starter (:value input)
                         :export true
                         :calculator [nm :prev])
                  :value)))

;; calculations helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-refs
  "Given an expression containing references (defined as a vector), 
   will extract all of the references and return them"
  ([expr] (extract-refs [] expr))
  ([found [fst & rst :as expr]]
   (cond (vector? expr) (conj found expr)
         (nil? fst) found
         (vector? fst) (recur (conj found fst) rst)
         (coll? fst) (recur (into found (extract-refs [] fst)) rst)
         :else (recur found rst))))

(defn- exported-measures [calc]
  (keep #(when (:export (second %)) (first %)) (:rows calc)))

(defn- calc-imports
  "Given a calculation, will determine which of the references to other rows
   are references to things outside the calculation."
  [calc]
  (let [internal-calcs (set (keys (:rows calc)))
        deps (->> (vals (:rows calc))
                  (mapcat (comp extract-refs :calculator))
                  (map first)
                  set)]
    (disj (set/difference deps internal-calcs) :placeholder)))

(defn- calculation-edges
  "Given a calculation, will return the edges of the graph of dependencies
   between the rows, including imports. Excludes reference to previous periods
   and placeholders"
  [calc]
  (->> (:rows calc)
       (u/map-vals (comp current-period-refs extract-refs :calculator))
       (mapcat expand)))

(comment
  (= (calculation-edges fmwk.forest/expenses)
     '([:tax :operating-period-flag]
       [:tax :compound-inflation]
       [:tax :starting-tax]
       [:interest :interest-rate]
       [:interest :starting-debt]
       [:management-fee :operating-period-flag]
       [:management-fee :management-fee-rate]
       [:expenses :management-fee]
       [:expenses :tax]
       [:expenses :interest])))

;; Calc validations
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- calc-has-required-keys? [calc]
  (if (empty? (set/difference
               #{:name :category :rows}
               (set (keys calc))))
    true
    (throw (ex-info "Calc failed keyspec"
                    {:calc calc
                     :missing-keys (set/difference
                                    #{:name :category}
                                    (set (keys calc)))}))))

(defn- every-row-has-calculator? [calc]
  (if (every? :calculator (vals (:rows calc)))
    true
    (throw (ex-info (str "Calc " (:name calc) "is missing a calculator")
                    {:calc calc}))))

;; TODO: Once rows are calc-qualified, this won't be necessary

(defn calculation-validation-halting [calcs]
  (if (not= (count calcs) (count (set (map :name calcs))))
    (let [dups ((group-by #(= 1 (val %)) (frequencies (map :name calcs))) false)]
      (throw (ex-info (str "Duplicate names " (mapv first dups))
                      {:dups dups})))
    (map (every-pred calc-has-required-keys?
                     every-row-has-calculator?)
         calcs)))


;; model helpers
;;;;;;;;;;;;;;;;;;;;;

(defn- records->map
  "Given a sequence of records (maps) and a key which is in those records,
   (and should have a unique value, like a name) will return a map 
   of the keys to the records"
  [records ky]
  (into {} (map #(vector (ky %) %) records)))

(comment
  (records->map [{:name :name1 :a 1 :b 2 :c 3}
                 {:name :name2 :a 4 :b 5 :c 6}]
                :name)
  {:name1 {:name :name1, :a 1, :b 2, :c 3},
   :name2 {:name :name2, :a 4, :b 5, :c 6}})

(defn- exports [model]
  (mapcat exported-measures (vals (:calculations model))))

(defn- imports [model]
  (update-vals (:calculations model) calc-imports))

;; maybe change this to work on rows?
(defn- full-dependency-graph [model]
  (->> (vals (:calculations model))
       (mapcat calculation-edges)
       (uber/add-directed-edges* (uber/digraph))))

;; model validations
;;;;;;;;;;;;;;;;;;;;;

(defn- import-export-mismatch?
  [model]
  (let [permissable (set (concat (keys (:inputs model)) (exports model)))]
    (for [[nm is] (imports model)]
      (if (empty? (set/difference (set is) permissable))
        true
        (throw (ex-info (str nm " contains import of unexported or undefined row " (set/difference (set is) permissable))
                        {:name nm}))))))

(defn- circular-deps? [model]
  (if (uber-alg/dag? (:full-graph model))
    true
    (throw (ex-info (str "Model contains circular dependencies")
                    (:full-graph model)))))

(defn check-model-halting [model]
  (doall [(import-export-mismatch? model)
          (circular-deps? model)]))


;; Model building
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- basic-model [calculations inputs]
  (hash-map :calculations (records->map calculations :name)
            :inputs inputs))

(defn model->rows
  "Turns a model definition (calculations and inputs)
   into a map of rows"
  [model]
  (merge (into {} (mapcat :rows (vals (:calculations model))))
         (into {} (map input-to-row (:inputs model)))))

(defn- enrich-model [model]
  (let [fg (full-dependency-graph model)]
    (assoc model
           :full-graph fg
           :rows (model->rows model)
           :calc-order (reverse (uber-alg/topsort fg)))))

(defn build-model [calculations inputs]
  (enrich-model (basic-model calculations inputs)))

;; Model running
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn zero-period
  "Creates the 'zeroeth period', aka the 'starter'
   period. Uses the row starter value (or the input value),
   or zero if there is none."
  [model]
  (update-vals (:rows model) #(or (:starter %) 0)))

(defn- replace-refs-in-calc [calc replacements]
  (if (vector? calc)
    (if (= :placeholder (first calc)) (second calc)
        (replacements (first calc)))
    (walk/postwalk
     #(if (vector? %) (replacements (first %)) %)
     calc)))

;; Yikes! Todo: clean this up
(defn next-period [[prv-rec] model]
  (reduce (fn [record row-name]
            (assoc record row-name
                   (let [calc (get-in model [:rows row-name :calculator])
                         reps (into {} (for [[t r] (extract-refs calc)]
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

(defn- roll-model [prvs model] (conj prvs (next-period prvs model)))

(defn run-model [model periods]
  (reverse (loop [prv (list (zero-period model))
                  prd periods]
             (if (zero? prd)
               prv
               (recur (roll-model prv model) (dec prd))))))

;; Table printing and model selection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rows-in
  "Given a model, and a category or calcluation name, will return the name of all the rows
   in that category or calculation"
  [model typ nm]
  (case typ
    :category (mapcat (comp keys :rows) (filter #(= (:category %) nm) (vals (:calculations model))))
    :calculation (mapcat (comp keys :rows) (filter #(= (:name %) nm) (vals (:calculations model))))
    :special (if (= :exports nm)
               (exports model)
               (throw (ex-info (str "Rows in Not implemented for " typ " " nm)
                               model)))
    (throw (ex-info (str "Rows in Not implemented for " typ " " nm)
                    model))))

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
