(ns fmwc.utils)

(defn include
  "Given a key and a function which operates on a map, will return a function
   that, given a map, will apply the function to the map and add the result
   to the original map at the provided key."
  [k f]
  (fn [m] (assoc m k (f m))))

(defn month-year-from-period [n]
  {:month (rem n 12)
   :year (quot n 12)})

(def add-calendar (include :calendar #(month-year-from-period (:period %))))
