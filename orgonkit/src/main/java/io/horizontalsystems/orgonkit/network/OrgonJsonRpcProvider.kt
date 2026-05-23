package io.horizontalsystems.orgonkit.network

import io.horizontalsystems.orgonkit.models.Address
import io.horizontalsystems.orgonkit.models.RpcSource
import io.horizontalsystems.orgonkit.models.Signer
import io.reactivex.Single
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.util.concurrent.TimeUnit

class OrgonJsonRpcProvider(private val rpcSource: RpcSource) {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun getBalance(address: Address): Single<BigInteger> = Single.fromCallable {
        val json = post("/wallet/getaccount", """{"address":"${address.base58}","visible":true}""")
        """"balance"\s*:\s*(\d+)""".toRegex().find(json)
            ?.groupValues?.get(1)?.toBigInteger() ?: BigInteger.ZERO
    }

    fun getTokenBalance(owner: Address, contract: Address): Single<BigInteger> = Single.fromCallable {
        val paddedAddress = owner.evmAddress.toHex().padStart(64, '0')
        val body = """{"owner_address":"${owner.base58}","contract_address":"${contract.base58}","function_selector":"balanceOf(address)","parameter":"$paddedAddress","visible":true}"""
        val json = post("/wallet/triggerconstantcontract", body)
        val result = """"constant_result"\s*:\s*\["([^"]+)"\]""".toRegex()
            .find(json)?.groupValues?.get(1) ?: return@fromCallable BigInteger.ZERO
        if (result.all { it == '0' }) BigInteger.ZERO
        else BigInteger(result.trimStart('0').ifEmpty { "0" }, 16)
    }

    fun broadcastTransaction(from: Address, to: Address, amount: Long, signer: Signer): Single<String> = Single.fromCallable {
        val createBody = """{"owner_address":"${from.base58}","to_address":"${to.base58}","amount":$amount,"visible":true}"""
        val unsignedJson = post("/wallet/createtransaction", createBody)
        val txId = """"txID"\s*:\s*"([^"]+)"""".toRegex()
            .find(unsignedJson)?.groupValues?.get(1)
            ?: throw Exception("Failed to create transaction")
        val sigHex = Hex.toHexString(signer.sign(Hex.decode(txId)))
        val idx = unsignedJson.lastIndexOf('}')
        val signedJson = unsignedJson.substring(0, idx) + ""","signature":["$sigHex"]}"""
        val result = post("/wallet/broadcasttransaction", signedJson)
        check(result.contains("\"result\":true")) { "Broadcast failed: $result" }
        txId
    }

    private fun post(path: String, body: String): String {
        val request = Request.Builder()
            .url("${rpcSource.fullNodeUrl}$path")
            .post(body.toRequestBody(JSON))
            .addHeader("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { return it.body?.string() ?: "" }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}