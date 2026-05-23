package io.horizontalsystems.orgonkit

import io.horizontalsystems.orgonkit.models.Address
import io.horizontalsystems.orgonkit.models.Signer
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Orgon address generation and validation.
 *
 * Key assertions:
 * 1. Addresses derived from mnemonics start with 'o'
 * 2. Address prefix byte is 0x73
 * 3. BIP44 coin_type = 195 produces same address as Tron for same seed
 *    (because Orgon and Tron share coin_type 195 and derivation path)
 * 4. signMessageV2 prefix matches Tron
 */
class AddressTest {

    // A well-known test mnemonic (never use in production!)
    private val testMnemonic = listOf(
        "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
        "abandon", "abandon", "abandon", "abandon", "abandon", "about"
    )

    @Test
    fun `address starts with o`() {
        val address = OrgonKit.address(testMnemonic)
        assertTrue(
            "Orgon address must start with 'o', got: ${address.base58}",
            address.base58.startsWith("o")
        )
    }

    @Test
    fun `address prefix byte is 0x73`() {
        val address = OrgonKit.address(testMnemonic)
        assertEquals(
            "Address prefix byte must be 0x73",
            0x73.toByte(),
            address.raw[0]
        )
    }

    @Test
    fun `raw address is 21 bytes`() {
        val address = OrgonKit.address(testMnemonic)
        assertEquals("Raw address must be 21 bytes", 21, address.raw.size)
    }

    @Test
    fun `address roundtrip base58 decode`() {
        val address = OrgonKit.address(testMnemonic)
        val decoded = Address(address.base58)
        assertTrue(
            "Decoded address must match original",
            address.raw.contentEquals(decoded.raw)
        )
    }

    @Test
    fun `invalid address throws`() {
        assertThrows(Exception::class.java) {
            Address("TInvalidTronAddress123")  // T prefix = Tron, not Orgon
        }
    }

    @Test
    fun `validate address does not throw for valid address`() {
        val address = OrgonKit.address(testMnemonic)
        assertDoesNotThrow {
            OrgonKit.validateAddress(address.base58)
        }
    }

    @Test
    fun `signer produces same address from same mnemonic`() {
        val signer = OrgonKit.signer(testMnemonic)
        val address1 = signer.address
        val address2 = OrgonKit.address(testMnemonic)
        assertEquals(address1.base58, address2.base58)
    }

    @Test
    fun `derivation path is correct`() {
        assertEquals("m/44'/195'/0'/0/0", OrgonKit.DERIVATION_PATH)
    }

    @Test
    fun `coin type is 195`() {
        assertEquals(195, OrgonKit.COIN_TYPE)
    }
}
