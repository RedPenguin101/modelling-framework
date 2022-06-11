(ns fmwk.simple-viz.main
  (:require [clojure2d.core :as c2d]))

(defn- draw-axis-lines
  "v-zero is a number between 0 and 1 which specified how far up
   the y axis, as a proportion, the x-axis should cross. A v-zero
   of 0 would mean the cross is right at the bottom of the plot
   (implying you're showing no negative values.)"
  [canvas h-size v-size v-zero]
  (let [margin 0.1
        v-span (- v-size (* 2 margin v-size))
        x-cross (int (+ (* (- 1 v-zero) v-span) (* margin v-size)))]
    (c2d/with-canvas-> canvas
      (c2d/set-color 0 0 0)
      (c2d/set-stroke 2.0)

    ;; x-axis line
      (c2d/line (int (* 0.1 h-size)) x-cross  ;;left point
                (int (* 0.9 h-size)) x-cross) ;; right point

    ;; y-axis line
      (c2d/line (int (* 0.1 h-size)) (int (* 0.1 v-size))
                (int (* 0.1 h-size)) (int (* 0.9 v-size))))))

(defn- draw-lines [canvas color lines]
  (c2d/with-canvas [c canvas]
    (c2d/set-color c color)
    (doseq [[v1 v2] lines]
      (c2d/line c v1 v2))))

(defn scale-factors
  "Given a series of values, will return a tuple of [constant factor].
   These number are those, when applied, will translate the values to
   new values of between 0 and the provided scale.
   Zero is preserved, i.e. only if there are negative values will a
   non-zero input value correspond to 0"
  ([values] (scale-factors values 1))
  ([values scale-adjust]
   (let [mn (max (- (apply min values)) 0)]
     [mn (* scale-adjust (/ 1 (+ mn (apply max values))))])))

(defn scale [values [cst fct]]
  (map #(float (* fct (+ cst %))) values))

(defn calc-and-scale [scale-adjust values]
  (scale values (scale-factors values scale-adjust)))

(defn lines-from-points [points]
  (map vector points (rest points)))

(def colors [:red :blue :green :orange])

(defn series-lines-save [series filename]
  (let [h-size   1000
        v-size   500
        margin   0.1
        canvas   (c2d/canvas h-size v-size)
        c-series (apply concat series)
        y-scale  (scale-factors c-series (* v-size (- 1 (* 2 margin))))
        y-range  (- (apply max c-series) (apply min c-series))
        z-point  (min 1 (max 0 (/ (- (apply min c-series)) y-range)))
        x-vals   (->> (map count series)
                      (apply max)
                      (range 0)
                      (calc-and-scale (* h-size (- 1 (* 2 margin))))
                      (map #(+ (* h-size margin) %)))]
    (draw-axis-lines canvas h-size v-size z-point)
    (doseq [[s c] (map vector series colors)]
      (draw-lines canvas c (->> (scale s y-scale)
                                (map #(+ (* margin v-size) %))
                                (map #(- v-size %))
                                (map vector x-vals)
                                lines-from-points)))
    (c2d/save canvas filename)))

(defn series-lines [series]
  (let [h-size 1000
        v-size 500
        canvas   (c2d/canvas h-size v-size)
        margin 0.1
        c-series (apply concat series)
        y-scale  (scale-factors c-series (* v-size (- 1 (* 2 margin))))
        y-range  (- (apply max c-series) (apply min c-series))
        z-point  (min 1 (max 0 (/ (- (apply min c-series)) y-range)))
        x-vals   (->> (map count series)
                      (apply max)
                      (range 0)
                      (calc-and-scale (* h-size (- 1 (* 2 margin))))
                      (map #(+ (* h-size margin) %)))]
    (draw-axis-lines canvas h-size v-size z-point)
    (doseq [[values c] (map vector series colors)]
      (draw-lines canvas c (->> (scale values y-scale)
                                (map #(+ (* margin v-size) %))
                                (map #(- v-size %))
                                (map vector x-vals)
                                lines-from-points)))
    (c2d/show-window canvas "Graph")))

(comment
  (series-lines ['(0 0 0 0 1000 2000 3000 4000 5000 6000 7000 8000 9000 10000 11000 12000 13000 14000 15000 16000)
                 '(0 0 0 0 2000 4000 6000 8000 10000 12000 14000 16000 18000 20000 22000 24000 26000 28000 30000 32000)])

  (series-lines-save ['(0 0 0 0 1000 2000 3000 4000 5000 6000 7000 8000 9000 10000 11000 12000 13000 14000 15000 16000)
                      '(0 0 0 0 2000 4000 6000 8000 10000 12000 14000 16000 18000 20000 22000 24000 26000 28000 30000 32000)]
                     "graph.png")

  (series-lines [(range 1 10)])

  (series-lines [(range 0 10)])

  (series-lines [(range -1 10)])

  (series-lines [(range -10 10)])

  (series-lines [(range -10 0)]))
