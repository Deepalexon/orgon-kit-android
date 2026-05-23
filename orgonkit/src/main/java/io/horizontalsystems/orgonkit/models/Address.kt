package io.horizontalsystems.orgonkit.models

import org.bouncycastle.crypto.digests.SHA256Digest

private const val ADDRESS_PREFIX_BYTE: Byte = 0x73.toByte()

class Address {

    val raw: ByteArray
    val base58: String

    constructor(base58: String) {
        require(base58.startsWith("o")) {
            "Invalid Orgon address: must start with 'o', got '${base58.firstOrNull()}'"
        }
        val decoded = Base58Check.decode(base58)
        require(decoded.size == 21) { "Invalid address length: ${decoded.size}" }
        require(decoded[0] == ADDRESS_PREFIX_BYTE) { "Invalid address prefix" }
        this.raw = decoded
        this.base58 = base58
    }

    constructor(evmAddress: ByteArray, addPrefix: Boolean) {
        require(evmAddress.size == 20) { "EVM address must be 20 bytes" }
        val full = ByteArray(21)
        full[0] = ADDRESS_PREFIX_BYTE
        System.arraycopy(evmAddress, 0, full, 1, 20)
        this.raw = full
        this.base58 = Base58Check.encode(full)
    }

    val evmAddress: ByteArray
        get() = raw.copyOfRange(1, 21)

    val hex: String
        get() = raw.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?) = other is Address && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
    override fun toString() = base58

    companion object {
        fun isValid(address: String): Boolean = try { Address(address); true } catch (e: Exception) { false }
    }
}

object Base58Check {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { idx ->
        ALPHABET.forEachIndexed { i, c -> idx[c.code] = i }
    }

    fun encode(payload: ByteArray): String {
        val checksum = checksum(payload)
        return encodeRaw(payload + checksum)
    }

    fun decode(input: String): ByteArray {
        val decoded = decodeRaw(input)
        require(decoded.size >= 4) { "Input too short" }
        val payload = decoded.copyOf(decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        require(checksum.contentEquals(checksum(payload))) { "Invalid checksum for: $input" }
        return payload
    }

    private fun checksum(data: ByteArray): ByteArray {
        val h2 = sha256(sha256(data))
        return h2.copyOf(4)
    }

    private fun sha256(input: ByteArray): ByteArray {
        val d = SHA256Digest()
        d.update(input, 0, input.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    private fun encodeRaw(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val digits = IntArray(input.size * 2)
        var outputStart = digits.size
        var i = 0
        while (i < input.size) {
            var carry = input[i].toInt() and 0xFF
            var j = digits.size
            while (j > outputStart || carry != 0) {
                --j; carry += 256 * digits[j]; digits[j] = carry % 58; carry /= 58
            }
            outputStart = j; ++i
        }
        var i2 = outputStart
        while (i2 < digits.size && digits[i2] == 0) ++i2
        val sb = StringBuilder()
        repeat(input.takeWhile { it.toInt() == 0 }.size) { sb.append('1') }
        while (i2 < digits.size) sb.append(ALPHABET[digits[i2++]])
        return sb.toString()
    }

    private fun decodeRaw(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val input58 = IntArray(input.length) { i ->
            val c = input[i].code
            if (c >= 128) -1 else INDEXES[c]
        }
        require(input58.none { it == -1 }) { "Invalid base58 character in: $input" }
        var zeroCount = 0
        while (zeroCount < input58.size && input58[zeroCount] == 0) ++zeroCount
        val temp = ByteArray(input.length)
        var j = temp.size
        var startAt = zeroCount
        while (startAt < input58.size) {
            var mod = 0; var k = startAt
            while (k < input58.size) {
                val tmp = input58[k].toLong() + 58L * mod
                input58[k] = (tmp % 256).toInt(); mod = (tmp / 256).toInt()
                if (input58[k] == 0 && k == startAt) ++startAt
                ++k
            }
            temp[--j] = mod.toByte()
        }
        while (j < temp.size && temp[j].toInt() == 0) ++j
        return temp.copyOfRange(j - zeroCount, temp.size)
    }
}