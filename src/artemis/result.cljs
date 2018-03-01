(ns artemis.result)

(defn with-errors
  [result errors]
  (if (empty? errors)
    result
    (update result :errors concat errors)))

(defn result->message
  [result]
  (-> result
      (select-keys [:data :errors])
      (update :data identity)))
