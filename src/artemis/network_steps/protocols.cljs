(ns artemis.network-steps.protocols)

(defprotocol GQLNetworkStep
  "Defines a protocol for for modifying the execution chain for a GraphQL
  network request."
  (-exec [_ operation context]
    "Establishes a step in the network execution chain.

    - `operation` a map that represents the operation to be executed when the
       end of the chain is reached. Should contain `:document`, `:variables`,
       and `:name` keys.
    - `context` a map that contain contain arbitrary context to be passed
      through the execution chain.

    The final step in the chain should return a core.async channel. Prior-steps
    should return the result of calling artemis.core/exec on an enclosed step.

    For example, if you wanted to log the operation before making the request,
    you can add a step to the execution chain by doing the following:

    (def http
      (reify
        GQLNetworkStep
        (-exec [_ operation context]
          (post-chan \"/api/graphql\"
                     (operation->http-params operation)))))

    (defn logger [next-step]
      (reify
        GQLNetworkStep
        (-exec [_ operation context]
          (logger/log operation)
          (artemis.core/exec next-step operation context))))

    (def network-chain (-> http logger))"))
