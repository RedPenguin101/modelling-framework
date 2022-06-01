(ns fmwk.ui
  (:require [seesaw.core :refer [invoke-later frame pack! show! display] :as ss]))

(def b (ss/button :text "Click Me"))

(def window (frame :title "Hello",
                   :content "Hello, Seesaw",
                   :on-close :exit))

