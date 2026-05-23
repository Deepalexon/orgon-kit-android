package io.horizontalsystems.orgonkit

import android.app.Application
import android.content.Context
import io.horizontalsystems.orgonkit.models.*
import io.horizontalsystems.orgonkit.network.OrgonJsonRpcProvider
import io.horizontalsystems.orgonkit.network.ConnectionManager
import io.horizontalsystems.orgonkit.storage.OrgonKitDatabase
import io.horizontalsystems.orgonkit.storage.Storage
import io.horizontalsystems.orgonkit.transactions.TransactionManager
import io.horizontalsystems.orgonkit.decorations.DecorationManager
import io.reactivex.Flowable
import io.reactivex.Single
import java.math.BigInteger
import java.util.logging.Logger

class OrgonKit(
    val address: Address,
    private val transactionManager: TransactionManager,
    private val rpcProvider: OrgonJsonRpcProvider,
    private val storage: Storage,
    private val connectionManager: ConnectionManager
) {
    private val logger = Logger.getLogger(this.javaClass.simpleName)

    val lastBlockHeight: Long?
        get() = storage.lastBlockHeight

    val syncState: SyncState
        get() = connectionManager.syncState

    val receiveAddress: String
        get() = address.base58

    // --- Flowables ---

    val lastBlockHeightFlowable: Flowable<Long>
        get() = storage.lastBlockHeightFlowable

    val syncStateFlowable: Flowable<SyncState>
        get() = connectionManager.syncStateFlowable

    val balanceFlowable: Flowable<BigInteger>
        get() = storage.balanceFlowable

    val transactionsFlowable: Flowable<List<FullTransaction>>
        get() = transactionManager.transactionsFlowable

    // --- Balance ---

    fun getBalance(): BigInteger = storage.balance ?: BigInteger.ZERO

    fun refresh() {
        connectionManager.refresh()
    }

    // --- Send ---

    fun send(
        toAddress: Address,
        amount: Long,
        signer: Signer
    ): Single<FullTransaction> {
        return rpcProvider.broadcastTransaction(
            from = address,
            to = toAddress,
            amount = amount,
            signer = signer
        )
    }

    // --- TRC-20 / oRC-20 tokens ---

    fun sendToken(
        contractAddress: Address,
        toAddress: Address,
        amount: BigInteger,
        feeLimit: Long,
        signer: Signer
    ): Single<FullTransaction> {
        return rpcProvider.triggerSmartContract(
            contractAddress = contractAddress,
            from = address,
            to = toAddress,
            amount = amount,
            feeLimit = feeLimit,
            signer = signer
        )
    }

    fun getTokenBalance(contractAddress: Address): Single<BigInteger> {
        return rpcProvider.getTokenBalance(address, contractAddress)
    }

    // --- Transactions ---

    fun transactions(
        token: Token? = null,
        fromHash: ByteArray? = null,
        limit: Int? = null
    ): Single<List<FullTransaction>> {
        return transactionManager.getTransactions(token, fromHash, limit)
    }

    // --- Static factory ---

    companion object {
        fun getInstance(
            context: Context,
            address: Address,
            walletId: String,
            rpcSource: RpcSource = RpcSource.OrgonMainNet(),
            minLogLevel: Logger.Level = Logger.Level.WARNING
        ): OrgonKit {
            val database = OrgonKitDatabase.getInstance(context, walletId)
            val storage = Storage(database)
            val connectionManager = ConnectionManager()
            val rpcProvider = OrgonJsonRpcProvider(rpcSource, address)
            val decorationManager = DecorationManager()
            val transactionManager = TransactionManager(address, storage, rpcProvider, decorationManager)

            return OrgonKit(
                address = address,
                transactionManager = transactionManager,
                rpcProvider = rpcProvider,
                storage = storage,
                connectionManager = connectionManager
            )
        }

        fun address(words: List<String>, passphrase: String = ""): Address {
            return Signer.address(words, passphrase)
        }

        fun signer(words: List<String>, passphrase: String = ""): Signer {
            return Signer.getInstance(words, passphrase)
        }

        fun validateAddress(address: String) {
            Address(address) // throws if invalid
        }

        // Address prefix for Orgon: 0x73 → base58 starts with 'o'
        const val ADDRESS_PREFIX_BYTE: Byte = 0x73.toByte()
        const val COIN_TYPE: Int = 195  // Same BIP44 coin type as Tron
        const val DERIVATION_PATH: String = "m/44'/195'/0'/0/0"
    }
}
