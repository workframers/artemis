(ns artemis.network-steps.protocols)

(defprotocol
  ^{:added "0.1.0"}
  GQLNetworkStep
  "Defines a protocol for for modifying the execution chain for a GraphQL
  network request."
  (-exec [this operation context]
    "Establishes a step in the network execution chain.

    - `operation` A map representing the operation to be executed when the end
                  of the chain is reached. Contains a `:graphql` key holding
                  the information to construct a request and an `:unpack` key,
                  which is a function that allows you to unpack the response.
    - `context`   A map that can contain arbitrary context to be passed through
                  the execution chain.

    The final step in the chain should return a core.async channel. Prior-steps
    should return the result of calling `artemis.core/exec` on an enclosed
    step.

    For example, if you wanted to log the operation before making a request,
    you could add a step to the execution chain by doing the following:

    ```
    (defn post-chan [url params]
      ;; do http post here and return a core.async channel when ready
      ;; make sure to unpack the response before putting it onto the channel
      )

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
          (logger/log (:graphql operation))
          (artemis.core/exec next-step operation context))))

    (def network-chain (-> http logger))
    ```

    Any number of additional steps can be added to the chain by enclosing
    the execution of earlier steps."))
