package cn.linkvault

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional password-protected backup format.
 *
 * The existing JSON backup remains unchanged for portability. This envelope is
 * deliberately self-describing so future versions can reject it safely instead
 * of trying to interpret encrypted bytes as JSON.
 */
object SecureBackup {
    private val MAGIC = "LVSECURE".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val ITERATIONS = 120_000
    private const val MIN_PASSWORD = 8
    private const val MAX_ENCRYPTED = Backup.MAX_BYTES + 4096

    fun encrypt(items: List<Bookmark>, password: CharArray): ByteArray {
        require(password.size >= MIN_PASSWORD) { "密码至少需要 $MIN_PASSWORD 个字符" }
        val plain = Backup.encode(items)
        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        return ByteArrayOutputStream(MAGIC.size + 1 + SALT_BYTES + IV_BYTES + encrypted.size).apply {
            write(MAGIC)
            write(VERSION.toInt())
            write(salt)
            write(iv)
            write(encrypted)
        }.toByteArray()
    }

    fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_ENCRYPTED) { "加密备份超过 10 MB 上限" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun decrypt(bytes: ByteArray, password: CharArray): List<Bookmark> {
        require(bytes.size <= MAX_ENCRYPTED) { "加密备份超过 10 MB 上限" }
        require(password.size >= MIN_PASSWORD) { "密码至少需要 $MIN_PASSWORD 个字符" }
        val header = MAGIC.size + 1 + SALT_BYTES + IV_BYTES
        require(bytes.size > header && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "不是链藏加密备份" }
        require(bytes[MAGIC.size] == VERSION) { "不支持此加密备份版本" }
        val buffer = ByteBuffer.wrap(bytes, MAGIC.size + 1, SALT_BYTES + IV_BYTES)
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val encrypted = bytes.copyOfRange(header, bytes.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
            Backup.decode(cipher.doFinal(encrypted))
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("密码错误或备份已损坏", e)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES"
            )
        } finally {
            spec.clearPassword()
        }
    }
}
