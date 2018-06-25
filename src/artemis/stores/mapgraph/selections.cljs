(ns artemis.stores.mapgraph.selections
  (:require [clojure.string :as string]
            [artemis.stores.mapgraph.common :refer [get-ref like]]))

(def regular-directives #{"include" "skip"})
(defn aliased? ^boolean [selection]
  (and (keyword-identical? :field (:node-type selection))
       (not (nil? (:name selection)))))
(defn has-args? ^boolean [selection]
  (boolean (:arguments selection)))
(defn type-cond? ^boolean [selection]
  (boolean (some-> selection :selection-set first :type-condition)))
(defn custom-dirs? ^boolean [{:keys [directives]}]
  (boolean (some #(not (regular-directives (:name %))) directives)))

;; context is a map with 3 keys
;; :input-vars - variables given to the operation that field-key is being used in
;; :vars-info - info about the kinds of variables supported by that operation
;; :store - the MapGraphStore

(defn val-from-arg [{:keys [input-vars vars-info] :as context} arg]
  (if-let [var-name (:variable-name arg)]
    (or (get input-vars (keyword var-name))               ; get value from input vars
        (-> (group-by :variable-name vars-info)           ; or try to get the default value
            (get var-name)
            first
            :default-value))
    (:value arg)))

(defn arg-snippet [context arg]
  "Given a argument for a selection, returns a string used in generating the field key"
  (let [val (val-from-arg context arg)
        val (if (string? val) (str "\"" val "\"") val)]
    (str "\"" (:argument-name arg) "\":" val)))

(defn attach-args-to-key [key context {:keys [arguments]}]
  "Returns an updated field key with argument string snippets appended"
  (let [snippets (map (partial arg-snippet context) arguments)]
    (str key "({" (string/join "," snippets) "})")))

(defn directive-snippet [context {:keys [name arguments] :as directive}]
  "Given a directive for a selection, returns a string used in generating the field key"
  (let [arg-snippets (map (partial arg-snippet context) arguments)
        args-string (if-not (empty? arg-snippets)
                      (str "({" (string/join "," arg-snippets) "})")
                      "")]
    (str "@" name args-string)))

(defn attach-directive-to-key [key context {:keys [directives]}]
  "Returns an updated field key with the directive string snippet appended"
  (let [snippets (map (partial directive-snippet context) directives)]
    (str key (string/join "" snippets))))

(defn field-key [selection context]
  "Returns a generated string key if selection is not a plain data attribute
   otherwise return keywordized field name"
  (cond-> (:field-name selection)
          (has-args? selection) (attach-args-to-key context selection)
          (custom-dirs? selection) (attach-directive-to-key context selection)
          (not (or (has-args? selection) (custom-dirs? selection)))
          keyword))

(defn resolve-fragments
  "Given a map of fragments, inline the values for a fragment if they appear
  within the selection set."
  [fragments sel-set]
  (reduce (fn [acc sel]
            (if (keyword-identical? (:node-type sel) :fragment-spread)
              (->> (get-in fragments [(:name sel) :selection-set] [])
                   (resolve-fragments fragments)
                   (into acc))
              (conj acc sel)))
          []
          sel-set))

(defn selection-set
  "For a selection, checks for a nested selection-set and returns it. Whenever
  a selection within the selection-set is a type-condition selection (for union
  types) it grabs the correct selection for the the appropriate type."
  [sel v]
  (when-let [sel-set (:selection-set sel)]
    (reduce (fn [acc sel]
              (if (keyword-identical? (:node-type sel) :inline-fragment)
                (if (= (:type-name (:type-condition sel)) (:__typename v))
                  (into acc (:selection-set sel))
                  acc)
                (conj acc sel)))
            []
            sel-set)))

(defn ref-join-expr
  "When selection is a union-type selection, resolves the join-expr by looking
  up the typename. Otherwise, just returns the regular join-expr."
  [entities join-expr lookup-ref selection]
  (if-not (type-cond? selection)
    join-expr
    (reduce (fn [acc [condition selection]]
              (if (= (:type-name (:type-condition condition))
                     (:__typename (get entities lookup-ref)))
                (reduced (into acc selection))
                acc))
            []
            ;; converting map to tuples for easier access of individual key/val
            (mapcat identity join-expr))))
