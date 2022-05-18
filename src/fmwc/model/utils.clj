(ns fmwc.model.utils)

(defn make-flag [x] (if x 1 0))
(defn equal-to? [x] (fn [val] (if (= x val) 1 0)))
(defn flagged? [x] (not (zero? x)))

(defn str-to-date [date-str] (java.time.LocalDate/parse date-str))
(defn date-to-str [date] (.toString date))

(defn date-comp [a b] (.compareTo (str-to-date a) (str-to-date b)))
(defn date< [a b] (neg? (date-comp a b)))
(defn date= [a b] (zero? (date-comp a b)))
(defn date> [a b] (pos? (date-comp a b)))
(defn date<= [a b] (or (date< a b) (date= a b)))
(defn date>= [a b] (or (date> a b) (date= a b)))
(defn month-of [d] (.getMonthValue (str-to-date d)))
(defn year-of [d] (.getYear (str-to-date d)))

(defn add-months [date months]
  (when date
    (date-to-str (.plusMonths (str-to-date date) months))))

(defn add-days [date days]
  (when date
    (date-to-str (.plusDays (str-to-date date) days))))

(defn end-of-month [date months]
  (let [month-adj (.plusMonths (str-to-date date) months)]
    (date-to-str (.plusDays month-adj (- (.lengthOfMonth month-adj)
                                         (.getDayOfMonth month-adj))))))

(comment
  (end-of-month "2021-03-30" (* 12 25))
  (add-months "2021-03-31" 2)
  (add-days "2021-03-01" -1)
  (end-of-month "2021-03-31" 2)
  (.getDayOfMonth (str-to-date "2021-03-31")))