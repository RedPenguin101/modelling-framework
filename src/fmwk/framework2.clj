(ns fmwk.framework2
  (:require [clojure.walk :refer [postwalk]]
            [clojure.spec.alpha :as spec]
            [ubergraph.core :as uber]
            [ubergraph.alg :as uberalg]))

;; utils
;;;;;;;;;;;;;;

(defn map-vals [f m] (update-vals m f))

(defn qualify [qualifier-str kw]
  (if (or (= :placeholder kw) (qualified-keyword? kw)) kw
      (keyword qualifier-str (name kw))))

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

(defn select-keys-with-qualifier [qualifier ks] ((group-by namespace ks) qualifier))

;; References and calculation expressions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(spec/def :framework.reference/current (spec/tuple keyword?))
(spec/def :framework.reference/previous (spec/tuple keyword? #(= :prev %)))
(spec/def :framework.reference/placeholder (spec/tuple #(= :placeholder %) any?))

(def atomic? (complement coll?))
(def expression? list?)
(defn constant-ref? [ref] (and (vector? ref) (#{:placeholder :constant} (first ref))))
(def link? (every-pred vector? (complement constant-ref?)))
(defn current-period-link? [ref] (and (link? ref) (= 1 (count ref))))
(defn previous-period-link? [ref] (and (link? ref) (= :prev (second ref))))

;; idea is to use this for better circularity detection later - i.e if it's circular,
;; but only because this is a link to previous, that's fine. But can't think it through now
(def link-to-prv? (every-pred link? previous-period-link?))

(spec/valid? :framework.reference/current [:hello])
(spec/valid? :framework.reference/previous [:hello :prev])
(spec/valid? :framework.reference/placeholder [:placeholder 6])

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
  (update-vals inputs #(conj [:constant] (:value %))))

;; calculations helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Calc validations
;;;;;;;;;;;;;;;;;;;;;;;;;

;; model helpers
;;;;;;;;;;;;;;;;;;;;;


;; Model building
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn de-localize-rows [rows]
  (into {} (map (fn [[k v]] [k (qualify-local-references (namespace k) v)]) rows)))

(defn build-model [inputs calculations]
  (de-localize-rows (apply merge (inputs->rows inputs) calculations)))

;; Model dependecies
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rows->graph [rows]
  (->> (map-vals extract-refs rows)
       (map-vals #(filter current-period-link? %))
       (map-vals #(map first %))
       (mapcat expand)
       (uber/add-directed-edges* (uber/digraph))))

(defn calculate-order [rows]
  (->> (rows->graph rows)
       (uberalg/topsort)
       reverse))

;; model validations
;;;;;;;;;;;;;;;;;;;;;

(defn circular? [rows] (not (uberalg/dag? (rows->graph rows))))

(defn all-rows-are-exprs? [rows] (every? expression? (vals rows)))

(defn bad-references [rows]
  (let [permitted (set (keys rows))]
    (into {} (remove (comp empty? val)
                     (update-vals rows (comp (partial remove permitted)
                                             (partial map first)
                                             (partial filter link?)
                                             extract-refs))))))

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
  (let [order (calculate-order rows)]
    (loop [records (list (zero-period rows))
           prd periods]
      (if (zero? prd)
        records
        (recur (roll-model records rows order) (dec prd))))))

;; Model helpers
;;;;;;;;;;;;;;;;;;;;;;

(defn add-total
  ([calculation] (add-total calculation :total))
  ([calculation total-name]
   (assoc calculation total-name (reverse (into '(+) (map vector (keys calculation)))))))

(defn corkscrew [qualifier increase decrease]
  (update-keys (add-total
                {:start [:end :prev]
                 :increase [increase]
                 :decrease (cons '- (list [decrease]))}
                :end)
               (partial qualify qualifier)))

;; Table printing and model selection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
