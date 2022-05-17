(ns fmwc.knowledge
  (:require [fmwc.utils :as u]
            [portal.api :as p]))

;; Assumptions
;;;;;;;;;;;;;;;;

(def user-growth [5.0 -3.0 -3.0 -3.0 -3.0 -20.0 0.0 150.0 -20.0 -3.0 -3.0 -3.0])

(def assumptions-au
  {:users {:dot 7539 :line 9726 :triangle 17751 :square 3499 :star 661}
   :prices {:dot 14.99 :line 29.99 :triangle 44.99 :square 74.99 :star 149.99}
   :user-growth user-growth
   :inflation-step 5
   :occurrences 3
   :inflation [4.23 5.45 6.68 2.97 6.85]
   :fx [1.3100	1.3200	1.3300	1.3400	1.3500]})

(def assumptions-us
  {:users {:dot 96430 :line 137435 :triangle 63735 :square 35340 :star 7237}
   :user-growth user-growth
   :prices {:dot 9.99 :line 19.99 :triangle 29.99 :square 49.99 :star 99.99}
   :inflation-step 5
   :occurrences 3
   :inflation [4.27	3.77	5.37	3.68	6.66]})


(defn start-model [assumptions]
  (-> assumptions
      (assoc :period 0)
      (u/add-calendar)
      ((u/include :inflation-adjusted-prices :prices))))

(defn update-user-numbers [state]
  (let [month (get-in state [:calendar :month])
        ug (+ 1 (/ (get-in state [:user-growth month]) 100))]
    (update state :users update-vals #(* % ug))))

(defn monthly-revenue [state] (apply + (vals (merge-with * (:users state) (:prices state)))))
(def add-revenue (u/include :revenue monthly-revenue))

(defn inflation-adjust-prices [state]
  (let [year (get-in state [:calendar :year])
        rate (inc (/ (get-in state [:inflation year]) 1200))]
    (update state :inflation-adjusted-prices update-vals #(* % rate))))

;; price increase stuff 
(defn price-thresholds [prices inflation-step] (update-vals prices #(+ % (/ inflation-step 2))))

(defn past-increase-threshold? [prices infl-adj-prices infl-step]
  (into {} (filter #(neg? (second %)) (merge-with - (price-thresholds prices infl-step) infl-adj-prices))))

(defn new-prices [prices infl-adj-prices infl-step]
  (let [increases (past-increase-threshold? prices infl-adj-prices infl-step)
        np (merge prices (update-vals (select-keys prices (keys increases)) #(+ % infl-step)))
        new-increases (past-increase-threshold? np infl-adj-prices infl-step)]
    (if (empty? new-increases) np
        (recur np infl-adj-prices infl-step))))

(defn update-prices [state]
  (let [increases (past-increase-threshold? (:prices state)
                                            (:inflation-adjusted-prices state)
                                            (:inflation-step state))]
    (if (< (count increases) (:occurrences state)) state
        (update state :prices new-prices
                (:inflation-adjusted-prices state)
                (:inflation-step state)))))

(defn next-period [state]
  (-> state
      (inflation-adjust-prices)
      (update-prices)
      (update-user-numbers)
      (update :period inc)
      u/add-calendar
      add-revenue))

(->> (-> assumptions-us start-model)
     (iterate next-period)
     (take (inc (* 12 5)))
     (map :revenue)
     (rest)
     (map-indexed vector)
     (tap>))


(def p (p/open {:launcher :vs-code}))
(comment
  (add-tap #'p/submit)
  )