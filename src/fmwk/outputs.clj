(ns fmwk.outputs)

(defn meta-rows [metadata attribute]
  (set (keep (fn [[rn m]] (when (attribute m) rn)) metadata)))

(defn calculate-outputs [results model]
  (let [output-rows (meta-rows (:meta model) :output)]
    (keep (fn [[rw series]]
            (when (output-rows rw)
              [rw (apply + series)]))
          results)))

(defn dividable? [x] (and x (not (zero? x))))

(defn collate-outputs [new-outputs old-outputs]
  (let [oom (into {} old-outputs)]
    (for [[rw no] new-outputs]
      (let [oo (rw oom)
            delta (- no (or oo 0))
            div (if (dividable? oo) oo 1)]
        [rw [no delta (/ delta div) oo]]))))

(defn filter-for-change [collated-outputs]
  (remove #(zero? (second (second %))) collated-outputs))
