package app.codeg.android.core.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Config sync crypto failure — the message is safe to show the user. */
class ConfigSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Phone-to-phone config sync envelope crypto (batch v142). Fully local: no
 * network, no server API. AES-256-GCM with a PBKDF2-HMAC-SHA256 derived key —
 * the password is never the key. Every export uses a fresh salt+nonce from
 * [SecureRandom]. The copied text is a self-describing JSON envelope whose
 * binary fields are Base64-URL-safe; hostile envelopes are bounded (see the
 * caps in [decrypt]) so a crafted blob can't burn CPU/memory.
 */
object ConfigSyncCrypto {

    const val FORMAT = "codeg-mobile-encrypted"
    const val VERSION = 1
    const val KDF = "PBKDF2-HMAC-SHA256"
    const val CIPHER = "AES-256-GCM"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val DEFAULT_ITERATIONS = 600_000
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    const val KEY_BITS = 256

    // Decrypt-time caps against malicious envelopes (go.md §8.2).
    const val MAX_ITERATIONS = 1_000_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_SALT_BYTES = 64
    const val MAX_CIPHERTEXT_BYTES = 1_000_000

    @Serializable
    private data class Envelope(
        val format: String,
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val cipher: String,
        val salt: String,
        val nonce: String,
        val ciphertext: String,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encrypt(password: CharArray, plaintext: ByteArray, iterations: Int = DEFAULT_ITERATIONS): String {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        val url = Base64.getUrlEncoder().withoutPadding()
        return json.encodeToString(
            Envelope.serializer(),
            Envelope(
                format = FORMAT, version = VERSION, kdf = KDF, iterations = iterations,
                cipher = CIPHER,
                salt = url.encodeToString(salt),
                nonce = url.encodeToString(nonce),
                ciphertext = url.encodeToString(ciphertext),
            ),
        )
    }

    /** Decrypts and authenticates; throws [ConfigSyncException] with a user-safe message. */
    fun decrypt(password: CharArray, envelopeText: String): ByteArray {
        // Phone-to-phone transfer often injects soft line breaks / zero-width
        // chars into long pastes; the envelope is single-line JSON, so stripping
        // them is zero-risk and saves a confusing "format" error.
        val cleaned = envelopeText
            .replace("\r", "").replace("\n", "")
            .replace("\u200b", "").replace("\uFEFF", "")
            // Messengers also fold long pastes with PLAIN SPACES; the envelope
            // is compact single-line JSON, so spaces inside it are impossible.
            .replace(" ", "")
            .trim()
        val env = try {
            json.decodeFromString(Envelope.serializer(), cleaned)
        } catch (e: Exception) {
            throw ConfigSyncException("无法识别的加密文本格式", e)
        }
        if (env.format != FORMAT) throw ConfigSyncException("无法识别的加密文本格式")
        if (env.version != VERSION) throw ConfigSyncException("不支持的格式版本 v${env.version}，请升级 APP")
        if (env.kdf != KDF || env.cipher != CIPHER) throw ConfigSyncException("不支持的加密算法")
        if (env.iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw ConfigSyncException("非法的 KDF 参数")
        val url = Base64.getUrlDecoder()
        val salt = try { url.decode(env.salt) } catch (e: Exception) { throw ConfigSyncException("加密文本已损坏", e) }
        val nonce = try { url.decode(env.nonce) } catch (e: Exception) { throw ConfigSyncException("加密文本已损坏", e) }
        val ciphertext = try { url.decode(env.ciphertext) } catch (e: Exception) { throw ConfigSyncException("加密文本已损坏", e) }
        if (salt.size !in SALT_BYTES..MAX_SALT_BYTES) throw ConfigSyncException("非法的 KDF 参数")
        if (nonce.size != NONCE_BYTES) throw ConfigSyncException("非法的 Nonce")
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) throw ConfigSyncException("数据超出大小限制")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, env.iterations), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw ConfigSyncException("密码错误或密文被篡改", e)
        } catch (e: Exception) {
            throw ConfigSyncException("解密失败", e)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(PBEKeySpec(password, salt, iterations, KEY_BITS))
        return SecretKeySpec(key.encoded, "AES")
    }
}
