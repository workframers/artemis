(ns artemis.result)

(defn with-errors
  "Decorates a result with a sequence of error maps."
  {:added "0.1.0"}
  [result errors]
  (if (empty? errors)
    result
    (update result :errors concat errors)))

(defn result->message
  "Takes a result and returns a map containing at least a `:data`."
  {:added "0.1.0"}
  [result]
  (-> result (update :data identity)))
