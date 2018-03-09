(ns artemis.stores.mapgraph.selections
  (:require [artemis.stores.mapgraph.common :refer [get-ref like]]))

(def regular-directives #{"include" "skip"})
(defn aliased? [selection] (:field-alias selection))
(defn has-args? [selection] (:arguments selection))
(defn custom-dirs? [{:keys [directives]}]
  (some #(not (regular-directives (:directive-name %))) directives))

;; context is a map with 3 keys
;; :input-vars - variables given to the operation that field-key is being used in
;; :vars-info - info about the kinds of variables supported by that operation
;; :store - the MapGraphStore

(defn default-val-from-var [var-info]
  (let [default-val (:default-value var-info)]
    (get default-val (:value-type default-val))))

(defn val-from-arg [{:keys [input-vars vars-info] :as context} arg]
  (let [type (-> arg :argument-value :value-type)
        var-name (-> arg :argument-value :variable-name)]
    (if (= type :variable)
      (or (get input-vars (keyword var-name))               ; get value from input vars
          (-> (group-by :variable-name vars-info)           ; or try to get the default value
              (get var-name)
              first
              default-val-from-var))
      (get (:argument-value arg) type))))

(defn arg-snippet [context arg]
  "Given a argument for a selection, returns a string used in generating the field key"
  (let [val (val-from-arg context arg)
        val (if (string? val) (str "\"" val "\"") val)]
    (str "\"" (:argument-name arg) "\":" val)))

(defn attach-args-to-key [key context {:keys [arguments]}]
  "Returns an updated field key with argument string snippets appended"
  (let [snippets (map (partial arg-snippet context) arguments)]
    (str key "({" (clojure.string/join "," snippets) "})")))

(defn directive-snippet [context {:keys [directive-name arguments] :as directive}]
  "Given a directive for a selection, returns a string used in generating the field key"
  (let [arg-snippets (map (partial arg-snippet context) arguments)
        args-string (if-not (empty? arg-snippets)
                      (str "({" (clojure.string/join "," arg-snippets) "})")
                      "")]
    (str "@" directive-name args-string)))

(defn attach-directive-to-key [key context {:keys [directives]}]
  "Returns an updated field key with the directive string snippet appended"
  (let [snippets (map (partial directive-snippet context) directives)]
    (str key (clojure.string/join "" snippets))))

(defn field-key [selection context]
  "Returns a generated string key if selection is not a plain data attribute
   otherwise return keywordized field name"
  (cond-> (:field-name selection)
          (has-args? selection) (attach-args-to-key context selection)
          (custom-dirs? selection) (attach-directive-to-key context selection)
          (not (or (has-args? selection) (custom-dirs? selection)))
          keyword))
