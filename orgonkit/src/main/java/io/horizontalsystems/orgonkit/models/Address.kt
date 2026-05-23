package io.horizontalsystems.orgonkit.models

import io.horizontalsystems.orgonkit.OrgonKit
import org.bouncycastle.crypto.digests.SHA256Digest
import java.util.Arrays

/**
 * Orgon address.
 *
 * Key difference from Tron: prefix byte is 0x73 instead of 0x41.
 * This causes all base58-encoded addresses to start with lowercase 'o'.
 *
 * Address format:
 *   1. Take the last 20 bytes of keccak256(publicKey)
 *   2. Prepend 0x73 (Orgon mainnet prefix)
 *   3. Base58Check encode → result starts with 'o'
 */
class Address {

    val raw: ByteArray        // 21 bytes: [0x73] + [20 bytes EVM-style address]
    val base58: String        // base58check encoded, starts with 'o'
    val hex: String           // hex without 0x prefix

    constructor(base58: String) {
        require(base58.startsWith("o")) {
            "Invalid Orgon address: must start with 'o', got '${base58.firstOrNull()}'"
        }
        val decoded = Base58.decodeChecked(base58)
        require(decoded.size == 21) {
            "Invalid Orgon address length: expected 21 bytes, got ${decoded.size}"
        }
        require(decoded[0] == OrgonKit.ADDRESS_PREFIX_BYTE) {
            "Invalid Orgon address prefix: expected 0x${OrgonKit.ADDRESS_PREFIX_BYTE.toInt().and(0xFF).toString(16)}, " +
            "got 0x${decoded[0].toInt().and(0xFF).toString(16)}"
        }
        this.raw = decoded
        this.base58 = base58
        this.hex = raw.toHexString()
    }

    constructor(rawBytes: ByteArray) {
        require(rawBytes.size == 21) {
            "Invalid raw address length: expected 21 bytes, got ${rawBytes.size}"
        }
        require(rawBytes[0] == OrgonKit.ADDRESS_PREFIX_BYTE) {
            "Invalid address prefix byte"
        }
        this.raw = rawBytes
        this.base58 = Base58.encodeChecked(rawBytes)
        this.hex = rawBytes.toHexString()
    }

    /**
     * Construct from a raw 20-byte EVM address (without prefix).
     */
    constructor(evmAddress: ByteArray, addPrefix: Boolean) {
        require(addPrefix) { "Use the rawBytes constructor if you already have the prefix" }
        require(evmAddress.size == 20) {
            "EVM address must be 20 bytes, got ${evmAddress.size}"
        }
        val full = ByteArray(21)
        full[0] = OrgonKit.ADDRESS_PREFIX_BYTE
        System.arraycopy(evmAddress, 0, full, 1, 20)
        this.raw = full
        this.base58 = Base58.encodeChecked(full)
        this.hex = full.toHexString()
    }

    /** The 20-byte EVM portion (without prefix) */
    val evmAddress: ByteArray
        get() = Arrays.copyOfRange(raw, 1, 21)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Address) return false
        return raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = base58

    companion object {
        fun fromHex(hex: String): Address {
            val cleaned = hex.removePrefix("0x")
            val bytes = cleaned.hexToByteArray()
            return Address(bytes)
        }

        fun isValidBase58(address: String): Boolean {
            return try {
                Address(address)
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

// --- Base58Check ---

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val ENCODED_ZERO = '1'
    private val INDEXES = IntArray(128) { -1 }.also { idx ->
        ALPHABET.forEachIndexed { i, c -> idx[c.code] = i }
    }

    fun encodeChecked(payload: ByteArray): String {
        val checksum = checksum(payload)
        val data = payload + checksum
        return encode(data)
    }

    fun decodeChecked(input: String): ByteArray {
        val decoded = decode(input)
        require(decoded.size >= 4) { "Input too short" }
        val payload = decoded.copyOf(decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val expectedChecksum = checksum(payload)
        require(checksum.contentEquals(expectedChecksum)) { "Invalid checksum for address: $input" }
        return payload
    }

    private fun checksum(data: ByteArray): ByteArray {
        val h1 = sha256(data)
        val h2 = sha256(h1)
        return h2.copyOf(4)
    }

    private fun sha256(input: ByteArray): ByteArray {
        val digest = SHA256Digest()
        digest.update(input, 0, input.size)
        val out = ByteArray(32)
        digest.doFinal(out, 0)
        return out
    }

    private fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val digits58 = IntArray(input.size * 2)
        var outputStart = digits58.size
        var i = 0
        while (i < input.size) {
            var carry = input[i].toInt() and 0xFF
            var j = digits58.size
            while (j > outputStart || carry != 0) {
                --j
                carry += 256 * digits58[j]
                digits58[j] = carry % 58
                carry /= 58
            }
            outputStart = j
            ++i
        }
        var i2 = outputStart
        while (i2 < digits58.size && digits58[i2] == 0) ++i2
        val sb = StringBuilder()
        var j = 0
        while (j < input.size && input[j].toInt() == 0) {
            sb.append(ENCODED_ZERO)
            j++
        }
        while (i2 < digits58.size) sb.append(ALPHABET[digits58[i2++]])
        return sb.toString()
    }

    private fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 = IntArray(input.length) { i ->
            val c = input[i].code
            if (c >= 128) -1 else INDEXES[c]
        }
        require(input58.none { it == -1 }) { "Invalid character in base58: $input" }
        var zeroCount = 0
        while (zeroCount < input58.size && input58[zeroCount] == 0) ++zeroCount
        val temp = ByteArray(input.length)
        var j = temp.size
        var startAt = zeroCount
        while (startAt < input58.size) {
            var mod = 0
            var k = startAt
            while (k < input58.size) {
                val digit58 = input58[k].toLong()
                val temp2 = digit58 + 58L * mod
                input58[k] = (temp2 % 256).toInt()
                mod = (temp2 / 256).toInt()
                if (input58[k] == 0 && k == startAt) ++startAt
                ++k
            }
            temp[--j] = mod.toByte()
        }
        while (j < temp.size && temp[j].toInt() == 0) ++j
        return temp.copyOfRange(j - zeroCount, temp.size)
    }
}

// --- Extensions ---

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToByteArray(): ByteArray {
    val len = length
    require(len % 2 == 0) { "Hex string must have even length" }
    return ByteArray(len / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
