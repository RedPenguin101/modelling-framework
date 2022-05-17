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

(defn end-of-month [date months]
  (let [date (str-to-date date)]
    (date-to-str (.plusMonths (.plusDays date (dec (.lengthOfMonth date)))
                              months))))

(end-of-month "2021-03-31" (* 12 25))
