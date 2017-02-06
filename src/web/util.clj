(ns web.util
  (:require [environ.core :refer [env]]))

(defn allow-anonymous? []
  (let [val  (env :wb-require-login)]
    (if (string? val)
      (let [s-val (read-string val)]
        (or (zero? s-val) (empty? (str s-val)))))))
