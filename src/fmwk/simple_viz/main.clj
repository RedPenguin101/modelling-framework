(ns fmwk.simple-viz.main
  (:require [clojure2d.core :as c2d]))

(defn- draw-axis-lines [canvas h-size v-size]

  (c2d/with-canvas-> canvas
    (c2d/set-color 0 0 0)
    (c2d/set-stroke 2.0)
    (c2d/line (int (* 0.1 h-size)) (int (* 0.9 v-size))
              (int (* 0.9 h-size)) (int (* 0.9 v-size)))
    (c2d/line (int (* 0.1 h-size)) (int (* 0.1 v-size))
              (int (* 0.1 h-size)) (int (* 0.9 v-size)))))

(defn- draw-axis-lines2
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

(defn- draw-lines [canvas lines color]
  (c2d/with-canvas [c canvas]
    (c2d/set-color c color)
    (doseq [[v1 v2] lines]
      (c2d/line c v1 v2))))

(defn scale-factors
  "Given a series of values, will return a tuple of [constant factor].
   These number are those, when applied, will translate the values to
   new values of between 0 and the provided scale."
  ([values] (scale-factors values 1))
  ([values scale-adjust]
   (let [mn (max (- (apply min values)) 0)]
     [mn (* scale-adjust (/ 1 (+ mn (apply max values))))])))

(defn scale [values [cst fct]]
  (map #(float (* fct (+ cst %))) values))

(defn calc-and-scale [values scale-adjust]
  (scale values (scale-factors values scale-adjust)))

(defn flip-vals [values]
  (map #(* -1 (- % 1000)) values))

(defn lines-from-points [points]
  (map vector points (rest points)))

(def colors [:red :blue :green :orange])

(defn series-lines-save [series filename]
  (let [canvas   (c2d/canvas 1000 1000)
        c-series (apply concat series)
        y-scale  (scale-factors c-series 800)
        y-range  (- (apply max c-series) (apply min c-series))
        z-point  (min 1 (max 0 (/ (- (apply min c-series)) y-range)))
        x-vals   (map #(+ 100 %) (calc-and-scale (range 0 (apply max (map count series))) 800))]
    (draw-axis-lines2 canvas 1000 1000 z-point)
    (doseq [[s c] (map vector series colors)]
      (draw-lines canvas (lines-from-points (map vector x-vals (flip-vals (map #(+ 100 %) (scale s y-scale)))))
                  c))
    (c2d/save canvas filename)))

(defn series-lines [series]
  (let [canvas   (c2d/canvas 1000 1000)
        c-series (apply concat series)
        y-scale  (scale-factors c-series 800)
        y-range  (- (apply max c-series) (apply min c-series))
        z-point  (min 1 (max 0 (/ (- (apply min c-series)) y-range)))
        x-vals   (map #(+ 100 %) (calc-and-scale (range 0 (apply max (map count series))) 800))]
    (draw-axis-lines2 canvas 1000 1000 z-point)
    (doseq [[s c] (map vector series colors)]
      (draw-lines canvas (lines-from-points (map vector x-vals (flip-vals (map #(+ 100 %) (scale s y-scale)))))
                  c))
    (c2d/show-window canvas "Graph")
    nil))

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
