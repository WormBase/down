(ns web.util
  (:require [environ.core :refer (env)]))

(defn allow-anonymous? []
  (let [val  (->> :trace-require-login env read-string)]
    (or (zero? val) (empty? (str val)))))
