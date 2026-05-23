package io.horizontalsystems.orgonkit.models

import java.math.BigInteger

// ─── Sync State ───────────────────────────────────────────────────────────────

sealed class SyncState {
    object Synced : SyncState()
    object Syncing : SyncState()
    class NotSynced(val error: Throwable? = null) : SyncState()
}

// ─── Block ────────────────────────────────────────────────────────────────────

data class BlockInfo(val height: Long)

// ─── Account ──────────────────────────────────────────────────────────────────

data class AccountInfo(
    val address: Address,
    val balance: BigInteger
)

// ─── Token ────────────────────────────────────────────────────────────────────

/**
 * Represents a token on Orgon.
 * oRC-10: native token (like TRC-10), identified by tokenId
 * oRC-20: smart contract token (like TRC-20), identified by contractAddress
 */
sealed class Token {
    data class ORC10(val tokenId: String) : Token()
    data class ORC20(val contractAddress: Address) : Token()
}

// ─── Transaction ─────────────────────────────────────────────────────────────

data class Transaction(
    val hash: ByteArray,
    val from: Address?,
    val to: Address?,
    val value: BigInteger,
    val blockNumber: Long?,
    val timestamp: Long,
    val fee: Long = 0,
    val confirmed: Boolean = false
) {
    val hashHex: String get() = hash.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?) = other is Transaction && hash.contentEquals(other.hash)
    override fun hashCode() = hash.contentHashCode()
}

data class FullTransaction(
    val transaction: Transaction,
    val decoration: TransactionDecoration
)

// ─── Transaction Decorations ──────────────────────────────────────────────────

sealed class TransactionDecoration {

    /** Native ORGON transfer */
    data class NativeTransfer(
        val from: Address,
        val to: Address,
        val value: Long
    ) : TransactionDecoration()

    /** oRC-20 token transfer */
    data class Eip20Transfer(
        val from: Address,
        val to: Address,
        val value: BigInteger,
        val contractAddress: Address
    ) : TransactionDecoration()

    /** oRC-10 token transfer */
    data class Asset10Transfer(
        val from: Address,
        val to: Address,
        val value: Long,
        val tokenId: String,
        val tokenName: String?
    ) : TransactionDecoration()

    /** Unknown / unrecognized contract call */
    object UnknownTransaction : TransactionDecoration()
}

// ─── Transaction Info (from API) ──────────────────────────────────────────────

data class TransactionInfo(
    val txId: String,
    val blockNumber: Long?,
    val timestamp: Long,
    val confirmed: Boolean,
    val contractType: Int,
    val fee: Long
)
