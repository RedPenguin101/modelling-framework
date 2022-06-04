(ns fmwk.utils)

(defn when-flag [flag expr]
  (if (true? flag) expr 0.0))

(defn when-not-flag [flag expr]
  (if (false? flag) expr 0.0))

(comment
  (when-flag [:hello] '(+ [:a] [:b] [:c])))

(defn round [x]
  (if (int? x) x (Math/round x)))

(defn sum  [xs] (apply + xs))
(defn mean [xs] (/ (sum xs) (count xs)))