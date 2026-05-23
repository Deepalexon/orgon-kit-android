package io.horizontalsystems.orgonkit.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.horizontalsystems.orgonkit.models.*
import io.reactivex.Single
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.util.encoders.Hex
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * JSON-RPC client for Orgon fullnode HTTP API.
 *
 * Orgon is a Tron fork and exposes the same HTTP API endpoints.
 * Base URL: https://tr80.orgon.space  (or custom node)
 *
 * Key endpoints used:
 *   POST /wallet/createtransaction       — build TRX transfer
 *   POST /wallet/broadcasttransaction    — broadcast signed tx
 *   POST /wallet/triggerconstantcontract — call oRC-20 view functions
 *   POST /wallet/triggersmartcontract    — call oRC-20 state-changing functions
 *   GET  /wallet/getaccount              — get account info (balance)
 *   GET  /wallet/getnowblock             — latest block
 *   GET  /wallet/gettransactionbyid      — fetch tx by hash
 */
class OrgonJsonRpcProvider(
    private val rpcSource: RpcSource,
    private val address: Address
) {
    private val logger = Logger.getLogger(this.javaClass.simpleName)
    private val gson: Gson = GsonBuilder().create()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String get() = rpcSource.fullNodeUrl

    // ─── Account ───────────────────────────────────────────────────────────────

    fun getAccount(address: Address): Single<AccountInfo> = Single.fromCallable {
        val body = """{"address":"${address.base58}","visible":true}"""
        val json = post("/wallet/getaccount", body)
        parseAccountInfo(json, address)
    }

    fun getBalance(address: Address): Single<BigInteger> =
        getAccount(address).map { it.balance }

    fun getLatestBlock(): Single<BlockInfo> = Single.fromCallable {
        val json = post("/wallet/getnowblock", "{}")
        parseBlockInfo(json)
    }

    // ─── Transactions ──────────────────────────────────────────────────────────

    fun getTransactions(
        address: Address,
        limit: Int = 20,
        fingerprint: String? = null
    ): Single<List<TransactionInfo>> = Single.fromCallable {
        val params = buildString {
            append("""{"address":"${address.base58}","limit":$limit,"order_by":"block_timestamp,desc","only_confirmed":true,"visible":true""")
            if (fingerprint != null) append(""","fingerprint":"$fingerprint"""")
            append("}")
        }
        val json = post("/v1/accounts/${address.base58}/transactions", params)
        parseTransactions(json)
    }

    fun getTransaction(txHash: String): Single<TransactionInfo?> = Single.fromCallable {
        val body = """{"value":"$txHash","visible":true}"""
        val json = post("/wallet/gettransactionbyid", body)
        if (json.contains("\"txID\"")) parseTransaction(json) else null
    }

    // ─── Transfer (native ORGON) ───────────────────────────────────────────────

    fun broadcastTransaction(
        from: Address,
        to: Address,
        amount: Long,  // in SUN (1 ORGON = 1_000_000 SUN)
        signer: Signer
    ): Single<FullTransaction> = Single.fromCallable {
        // Step 1: Create unsigned transaction
        val createBody = """
            {
              "owner_address": "${from.base58}",
              "to_address": "${to.base58}",
              "amount": $amount,
              "visible": true
            }
        """.trimIndent()
        val unsignedJson = post("/wallet/createtransaction", createBody)
        val txId = extractString(unsignedJson, "txID")
        val rawDataHex = extractString(unsignedJson, "raw_data_hex")

        // Step 2: Sign
        val txHash = Hex.decode(txId)
        val signature = signer.sign(txHash)

        // Step 3: Broadcast
        val broadcastBody = buildSignedTxJson(unsignedJson, signature)
        val result = post("/wallet/broadcasttransaction", broadcastBody)
        check(result.contains("\"result\":true")) {
            "Broadcast failed: $result"
        }

        FullTransaction(
            transaction = Transaction(
                hash = Hex.decode(txId),
                from = from,
                to = to,
                value = amount.toBigInteger(),
                blockNumber = null,
                timestamp = System.currentTimeMillis()
            ),
            decoration = TransactionDecoration.NativeTransfer(from, to, amount)
        )
    }

    // ─── oRC-20 Smart Contract ─────────────────────────────────────────────────

    fun getTokenBalance(
        ownerAddress: Address,
        contractAddress: Address
    ): Single<BigInteger> = Single.fromCallable {
        // ERC-20 / oRC-20 balanceOf(address) selector: 0x70a08231
        val paddedAddress = ownerAddress.evmAddress.toHex().padStart(64, '0')
        val data = "70a08231$paddedAddress"

        val body = """
            {
              "owner_address": "${ownerAddress.base58}",
              "contract_address": "${contractAddress.base58}",
              "function_selector": "balanceOf(address)",
              "parameter": "$paddedAddress",
              "visible": true
            }
        """.trimIndent()

        val json = post("/wallet/triggerconstantcontract", body)
        val constantResult = extractString(json, "constant_result")
        if (constantResult.isBlank() || constantResult == "00") {
            BigInteger.ZERO
        } else {
            BigInteger(constantResult.trimStart('0').ifEmpty { "0" }, 16)
        }
    }

    fun triggerSmartContract(
        contractAddress: Address,
        from: Address,
        to: Address,
        amount: BigInteger,
        feeLimit: Long,
        signer: Signer
    ): Single<FullTransaction> = Single.fromCallable {
        // oRC-20 transfer(address,uint256) selector: 0xa9059cbb
        val paddedTo = to.evmAddress.toHex().padStart(64, '0')
        val paddedAmount = amount.toString(16).padStart(64, '0')
        val parameter = paddedTo + paddedAmount

        val body = """
            {
              "owner_address": "${from.base58}",
              "contract_address": "${contractAddress.base58}",
              "function_selector": "transfer(address,uint256)",
              "parameter": "$parameter",
              "fee_limit": $feeLimit,
              "visible": true
            }
        """.trimIndent()

        val txJson = post("/wallet/triggersmartcontract", body)
        val txData = extractNestedObject(txJson, "transaction")
        val txId = extractString(txData, "txID")

        val signature = signer.sign(Hex.decode(txId))
        val broadcastBody = buildSignedTxJson(txData, signature)
        val result = post("/wallet/broadcasttransaction", broadcastBody)
        check(result.contains("\"result\":true")) { "Token transfer broadcast failed: $result" }

        FullTransaction(
            transaction = Transaction(
                hash = Hex.decode(txId),
                from = from,
                to = contractAddress,
                value = BigInteger.ZERO,
                blockNumber = null,
                timestamp = System.currentTimeMillis()
            ),
            decoration = TransactionDecoration.Eip20Transfer(from, to, amount, contractAddress)
        )
    }

    // ─── HTTP helpers ──────────────────────────────────────────────────────────

    private fun post(path: String, body: String): String {
        val url = "$baseUrl$path"
        logger.fine("POST $url  body=$body")
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .addHeader("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw OrgonRpcException("HTTP ${response.code} for $path: $responseBody")
            }
            logger.fine("Response: $responseBody")
            return responseBody
        }
    }

    private fun buildSignedTxJson(unsignedTxJson: String, signature: ByteArray): String {
        val sigHex = Hex.toHexString(signature)
        // Insert signature array into the transaction JSON
        val closingBrace = unsignedTxJson.lastIndexOf('}')
        return unsignedTxJson.substring(0, closingBrace) +
            ""","signature":["$sigHex"]}"""
    }

    private fun extractString(json: String, key: String): String {
        val pattern = """"$key"\s*:\s*"([^"]+)"""".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun extractNestedObject(json: String, key: String): String {
        val start = json.indexOf(""""$key"""")
        if (start == -1) return "{}"
        val braceStart = json.indexOf('{', start)
        if (braceStart == -1) return "{}"
        var depth = 0
        var i = braceStart
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return json.substring(braceStart, i + 1) }
            }
            i++
        }
        return "{}"
    }

    private fun parseAccountInfo(json: String, address: Address): AccountInfo {
        val balancePattern = """"balance"\s*:\s*(\d+)""".toRegex()
        val balance = balancePattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return AccountInfo(address = address, balance = balance.toBigInteger())
    }

    private fun parseBlockInfo(json: String): BlockInfo {
        val heightPattern = """"number"\s*:\s*(\d+)""".toRegex()
        val height = heightPattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return BlockInfo(height = height)
    }

    private fun parseTransactions(json: String): List<TransactionInfo> {
        // Simplified; in production use Gson models
        return emptyList()
    }

    private fun parseTransaction(json: String): TransactionInfo? = null

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

class OrgonRpcException(message: String) : Exception(message)
