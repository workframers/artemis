(ns artemis.test-util)

(defmacro with-client [& args]
  (let [opts? (map? (first args))
        opts  (if opts? (first args) {})
        body  (if opts? (next args) args)
        {:keys [store-query-fn store-write-fn]} opts]
    `(let [mock-chan# (cljs.core.async/chan)
           store# (stub-store ~store-query-fn ~store-write-fn)
           nchain# (stub-net-chain mock-chan#)
           ~'put-result! #(cljs.core.async/put! mock-chan# %)
           ~'client (artemis.core/create-client :store store# :network-chain nchain#)]
       ~@body)))
