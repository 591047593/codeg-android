package app.codeg.android.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSyncCryptoTest {

    private val plain = """{"format":"codeg-mobile-config","schema_version":1,"servers":[{"server_id":"a","name":"VPS","base_url":"https://x","token":"t"}]}"""

    @Test
    fun `roundtrip with correct password`() {
        val env = ConfigSyncCrypto.encrypt("correct horse battery".toCharArray(), plain.toByteArray())
        assertArrayEquals(plain.toByteArray(), ConfigSyncCrypto.decrypt("correct horse battery".toCharArray(), env))
    }

    @Test
    fun `wrong password fails with auth error`() {
        val env = ConfigSyncCrypto.encrypt("correct horse battery".toCharArray(), plain.toByteArray())
        val e = try {
            ConfigSyncCrypto.decrypt("wrong password 1234".toCharArray(), env); null
        } catch (e: ConfigSyncException) { e }
        assertTrue(e?.message?.contains("密码错误") == true)
    }

    @Test
    fun `tampered ciphertext fails`() {
        val env = ConfigSyncCrypto.encrypt("correct horse battery".toCharArray(), plain.toByteArray())
        val mutated = env.dropLast(4) + "AAAA"
        val e = try {
            ConfigSyncCrypto.decrypt("correct horse battery".toCharArray(), mutated); null
        } catch (e: ConfigSyncException) { e }
        assertTrue(e != null)
    }

    @Test
    fun `fresh salt and nonce per export`() {
        val pw = "correct horse battery".toCharArray()
        assertNotEquals(
            ConfigSyncCrypto.encrypt(pw, plain.toByteArray()),
            ConfigSyncCrypto.encrypt(pw, plain.toByteArray()),
        )
    }

    @Test
    fun `unsupported format version rejected`() {
        val env = ConfigSyncCrypto.encrypt("correct horse battery".toCharArray(), plain.toByteArray())
            .replace("\"version\":1", "\"version\":99")
        try {
            ConfigSyncCrypto.decrypt("correct horse battery".toCharArray(), env)
            throw AssertionError("should fail")
        } catch (e: ConfigSyncException) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    @Test
    fun `invalid envelope text rejected`() {
        try {
            ConfigSyncCrypto.decrypt("correct horse battery".toCharArray(), "not json at all")
            throw AssertionError("should fail")
        } catch (e: ConfigSyncException) { /* expected */ }
    }

    @Test
    fun `hostile kdf parameters rejected`() {
        val env = ConfigSyncCrypto.encrypt("correct horse battery".toCharArray(), plain.toByteArray())
            .replace("\"iterations\":600000", "\"iterations\":99999999")
        try {
            ConfigSyncCrypto.decrypt("correct horse battery".toCharArray(), env)
            throw AssertionError("should fail")
        } catch (e: ConfigSyncException) { /* expected */ }
    }
}
