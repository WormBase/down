(ns web.util
  (:require [environ.core :refer (env)]))

(defn allow-anonymous? []
  (let [val  (->> :trace-require-login env)]
    (if (string? val)
      (let [s-val (read-string val)]
        (or (zero? s-val) (empty? (str s-val)))))))
