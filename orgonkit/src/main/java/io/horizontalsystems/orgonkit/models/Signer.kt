package io.horizontalsystems.orgonkit

import io.horizontalsystems.orgonkit.models.Address
import io.horizontalsystems.orgonkit.models.RpcSource
import io.horizontalsystems.orgonkit.models.Signer
import io.horizontalsystems.orgonkit.network.OrgonJsonRpcProvider
import io.reactivex.Single
import java.math.BigInteger

class OrgonKit(
    val address: Address,
    private val rpcProvider: OrgonJsonRpcProvider
) {
    val receiveAddress: String get() = address.base58

    fun getBalance(): Single<BigInteger> = rpcProvider.getBalance(address)

    fun send(toAddress: Address, amount: Long, signer: Signer): Single<String> =
        rpcProvider.broadcastTransaction(from = address, to = toAddress, amount = amount, signer = signer)

    fun getTokenBalance(contractAddress: Address): Single<BigInteger> =
        rpcProvider.getTokenBalance(address, contractAddress)

    companion object {
        const val ADDRESS_PREFIX_BYTE: Byte = 0x73.toByte()
        const val COIN_TYPE: Int = 195
        const val DERIVATION_PATH: String = "m/44'/195'/0'/0/0"

        fun getInstance(address: Address, rpcSource: RpcSource = RpcSource.OrgonMainNet()): OrgonKit =
            OrgonKit(address = address, rpcProvider = OrgonJsonRpcProvider(rpcSource))

        fun address(words: List<String>, passphrase: String = ""): Address =
            Signer.address(words, passphrase)

        fun signer(words: List<String>, passphrase: String = ""): Signer =
            Signer.getInstance(words, passphrase)

        fun validateAddress(address: String) { Address(address) }
    }
}