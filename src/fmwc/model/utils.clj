(ns fmwc.model.utils)

(defn make-flag [x] (if x 1 0))
(defn equal-to? [x] (fn [val] (if (= x val) 1 0)))
(defn flagged? [x] (not (or (nil? x) (zero? x))))

(defn str->date [date-str] (java.time.LocalDate/parse date-str))
(defn date->str [date] (.toString date))

(defn date-comp [a b] (.compareTo (str->date a) (str->date b)))
(defn date< [a b] (neg? (date-comp a b)))
(defn date= [a b] (zero? (date-comp a b)))
(defn date> [a b] (pos? (date-comp a b)))
(defn date<= [a b] (or (date< a b) (date= a b)))
(defn date>= [a b] (or (date> a b) (date= a b)))
(defn month-of [d] (.getMonthValue (str->date d)))
(defn year-of [d] (.getYear (str->date d)))

(defn add-months [date months]
  (when date
    (date->str (.plusMonths (str->date date) months))))

(defn add-days [date days]
  (when date
    (date->str (.plusDays (str->date date) days))))

(defn end-of-month [date months]
  (let [month-adj (.plusMonths (str->date date) months)]
    (date->str (.plusDays month-adj (- (.lengthOfMonth month-adj)
                                       (.getDayOfMonth month-adj))))))

(comment
  (end-of-month "2021-03-30" (* 12 25))
  (add-months "2021-03-31" 2)
  (add-days "2021-03-01" -1)
  (end-of-month "2021-03-31" 2)
  (.getDayOfMonth (str-to-date "2021-03-31")))