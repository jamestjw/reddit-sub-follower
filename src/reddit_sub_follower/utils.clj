(ns reddit-sub-follower.utils
  (:require [clojure.java.io :as io]))

(import '[java.time Instant ZonedDateTime ZoneId]
        '[java.time LocalDateTime ZoneOffset]
        '[java.time.format DateTimeFormatter])

(defn stringify-epoch
  "Converts a Unix epoch timestamp (seconds) to a formatted string in the local timezone."
  [epoch-seconds]
  (let [formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss z")
        instant (Instant/ofEpochSecond epoch-seconds)
        local-zone (ZoneId/systemDefault)
        zoned-datetime (ZonedDateTime/ofInstant instant local-zone)]
    (.format zoned-datetime formatter)))

(defn read-first-line [file-path]
  (try
    (with-open [rdr (io/reader file-path)]
      (first (line-seq rdr)))
    (catch java.io.FileNotFoundException _
      nil)))

(defn parse-timestamp [ts fmt]
  (let [formatter (DateTimeFormatter/ofPattern fmt)]
    (-> (LocalDateTime/parse ts formatter)
        (.toInstant ZoneOffset/UTC))))
