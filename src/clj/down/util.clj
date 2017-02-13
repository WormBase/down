(ns down.util
  (:require
   [clj-time.core :as tc]
   [clj-time.format :as tf]
   [environ.core :refer [env]]))

(defn format-datetime
  "Returns a string.
   Formats a datetime `dt` as produced by `(tc/now)`"
  ([]
   (format-datetime (tc/now)))
  ([dt]
   (format-datetime dt :basic-date-time-no-ms tc/utc))
  ([dt tf-formatters-key]
   (format-datetime dt tf-formatters-key tc/utc))
  ([dt tf-formatters-key tz]
   (let [fmt (tf/with-zone (tf/formatters tf-formatters-key) tz)]
     (tf/unparse fmt dt))))

(defn allow-anonymous? []
  (let [val (env :wb-require-login)]
    (if (string? val)
      (let [s-val (read-string val)]
        (or (zero? s-val) (empty? (str s-val)))))))


