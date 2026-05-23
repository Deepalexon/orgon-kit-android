package io.horizontalsystems.orgonkit.models

/**
 * Orgon RPC source configuration.
 *
 * Public nodes (from dev.orgon.space):
 *   Fullnode:  https://tr80.orgon.space  (port 19067 for gRPC, 80 for HTTP)
 *   Solidity:  https://tr81.orgon.space  (port 19068 for gRPC, 80 for HTTP)
 *
 * Alternative ports for non-SSL gRPC:
 *   ApiUrl:       http://tr80.orgon.space
 *   FullPort:     19067
 *   SolidityPort: 19068
 */
sealed class RpcSource {

    abstract val fullNodeUrl: String
    abstract val solidityNodeUrl: String

    /**
     * Orgon mainnet — uses the official public nodes.
     * This is the default for all user wallets.
     */
    class OrgonMainNet : RpcSource() {
        override val fullNodeUrl: String = "https://tr80.orgon.space"
        override val solidityNodeUrl: String = "https://tr81.orgon.space"

        val fullNodeGrpcUrl: String = "http://tr80.orgon.space"
        val fullNodeGrpcPort: Int = 19067
        val solidityGrpcPort: Int = 19068
    }

    /**
     * Custom node — for advanced users who run their own Orgon full node.
     * Exposed in wallet settings UI as "Custom RPC".
     */
    class Custom(
        override val fullNodeUrl: String,
        override val solidityNodeUrl: String
    ) : RpcSource() {
        init {
            require(fullNodeUrl.isNotBlank()) { "fullNodeUrl must not be blank" }
            require(solidityNodeUrl.isNotBlank()) { "solidityNodeUrl must not be blank" }
        }
    }

    val networkName: String
        get() = when (this) {
            is OrgonMainNet -> "Orgon Mainnet"
            is Custom -> "Custom ($fullNodeUrl)"
        }
}
