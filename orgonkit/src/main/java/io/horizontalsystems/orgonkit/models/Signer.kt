package io.horizontalsystems.orgonkit.models

import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.orgonkit.OrgonKit
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/**
 * Signs Orgon transactions and messages.
 *
 * Key derivation uses:
 *   - BIP44 coin_type = 195  (same as Tron)
 *   - Derivation path: m/44'/195'/0'/0/0
 *   - Address prefix: 0x73   (Orgon mainnet)
 *
 * Message signing uses the same prefix as Tron/Orgon:
 *   "\x19TRON Signed Message:\n" + length + message
 * (As documented on dev.orgon.space — signMessageV2 is identical to Tron)
 */
class Signer(private val privateKey: BigInteger) {

    private val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")

    val publicKey: ByteArray by lazy {
        val point: ECPoint = ecSpec.g.multiply(privateKey).normalize()
        val x = point.xCoord.encoded
        val y = point.yCoord.encoded
        ByteArray(64).apply {
            System.arraycopy(x, 0, this, 0, 32)
            System.arraycopy(y, 0, this, 32, 32)
        }
    }

    val address: Address by lazy {
        val hash = keccak256(publicKey)
        val evmAddr = hash.copyOfRange(12, 32) // last 20 bytes
        Address(evmAddr, addPrefix = true)
    }

    /**
     * Sign a raw transaction hash.
     * Returns 65-byte signature: [r (32)] + [s (32)] + [v (1)]
     */
    fun sign(hash: ByteArray): ByteArray {
        require(hash.size == 32) { "Hash must be 32 bytes, got ${hash.size}" }

        val signer = ECDSASigner(HMacDSAKCalculator(org.bouncycastle.crypto.digests.SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey, ecSpec))

        val components = signer.generateSignature(hash)
        val r = components[0].toByteArrayUnsigned(32)
        val s = components[1].toByteArrayUnsigned(32)

        // Calculate recovery id (v)
        val v = calculateV(hash, r, s, publicKey)

        return ByteArray(65).apply {
            System.arraycopy(r, 0, this, 0, 32)
            System.arraycopy(s, 0, this, 32, 32)
            this[64] = v
        }
    }

    /**
     * Sign a personal message using Orgon/Tron's signMessageV2 prefix.
     * Prefix: "\x19TRON Signed Message:\n{length}"
     */
    fun signMessage(message: String): ByteArray {
        val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
        val prefix = "\u0019TRON Signed Message:\n${msgBytes.size}"
        val prefixBytes = prefix.toByteArray(StandardCharsets.UTF_8)
        val data = prefixBytes + msgBytes
        val hash = keccak256(data)
        return sign(hash)
    }

    private fun calculateV(hash: ByteArray, r: ByteArray, s: ByteArray, expectedPubKey: ByteArray): Byte {
        for (v in 0..1) {
            val recovered = recoverPublicKey(hash, r, s, v) ?: continue
            if (recovered.contentEquals(expectedPubKey)) return v.toByte()
        }
        return 0
    }

    private fun recoverPublicKey(hash: ByteArray, r: ByteArray, s: ByteArray, v: Int): ByteArray? {
        return try {
            val rInt = BigInteger(1, r)
            val sInt = BigInteger(1, s)
            val n = ecSpec.n
            val x = rInt.add(n.multiply(BigInteger.valueOf(v.toLong() / 2)))
            val point = decompressKey(x, v and 1 == 1) ?: return null
            val rInv = rInt.modInverse(n)
            val hashInt = BigInteger(1, hash)
            val u1 = hashInt.negate().multiply(rInv).mod(n)
            val u2 = sInt.multiply(rInv).mod(n)
            val recovered = (ecSpec.g.multiply(u1).add(point.multiply(u2))).normalize()
            val xBytes = recovered.xCoord.encoded
            val yBytes = recovered.yCoord.encoded
            ByteArray(64).apply {
                System.arraycopy(xBytes, 0, this, 0, 32)
                System.arraycopy(yBytes, 0, this, 32, 32)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decompressKey(x: BigInteger, yBit: Boolean): ECPoint? {
        return try {
            val encoded = ByteArray(33)
            encoded[0] = (if (yBit) 3 else 2).toByte()
            val xBytes = x.toByteArrayUnsigned(32)
            System.arraycopy(xBytes, 0, encoded, 1, 32)
            ecSpec.curve.decodePoint(encoded)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        // BIP44: m/44'/195'/0'/0/0
        private const val PURPOSE = 44
        private const val COIN_TYPE = 195  // same as Tron
        private const val ACCOUNT = 0
        private const val CHANGE = 0
        private const val ADDRESS_INDEX = 0

        fun getInstance(words: List<String>, passphrase: String = ""): Signer {
            val seed = Mnemonic().toSeed(words, passphrase)
            val hdWallet = HDWallet(seed, PURPOSE, COIN_TYPE)
            val privateKey = hdWallet.privateKey(ACCOUNT, ADDRESS_INDEX, CHANGE == 1)
            return Signer(privateKey.privKeyBytes.toBigInteger())
        }

        fun address(words: List<String>, passphrase: String = ""): Address {
            return getInstance(words, passphrase).address
        }

        private fun keccak256(data: ByteArray): ByteArray {
            val digest = KeccakDigest(256)
            digest.update(data, 0, data.size)
            val out = ByteArray(32)
            digest.doFinal(out, 0)
            return out
        }
    }
}

private fun keccak256(data: ByteArray): ByteArray {
    val digest = KeccakDigest(256)
    digest.update(data, 0, data.size)
    val out = ByteArray(32)
    digest.doFinal(out, 0)
    return out
}

private fun BigInteger.toByteArrayUnsigned(length: Int): ByteArray {
    val bytes = toByteArray()
    return when {
        bytes.size == length + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
        bytes.size < length -> ByteArray(length - bytes.size) + bytes
        else -> bytes
    }
}

private fun ByteArray.toBigInteger() = BigInteger(1, this)
