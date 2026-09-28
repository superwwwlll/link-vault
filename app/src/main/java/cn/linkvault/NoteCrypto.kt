package cn.linkvault

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 笔记正文的按行加密。写法沿用 [SecureBackup]：PBKDF2 派生 + AES/GCM，只用 JDK 自带能力。
 *
 * 封套自描述（含 KDF 轮数），理由和加密备份一致：将来调高迭代次数时老密文仍要能解开；
 * 真遇到解不开的格式，必须明确拒绝，而不是把字节当成正文读出来。
 *
 * 刻意**不按条派生**：12 万次 PBKDF2 乘以几十条私密笔记会让解锁卡好几秒，
 * 所以派生结果按 salt 缓存在一次解锁会话里 —— 正常全库只有一个 salt，只派生一次。
 */
object NoteCrypto {
    private const val MAGIC_BYTES = 6
    private val MAGIC = "LVNOTE".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    /** 导出成 Base64 后的长度上限（[MAX_ENVELOPE] 的密文编码后约 34 万字符）。 */
    const val MAX_CIPHER = 350_000

    /** 备份文件要照原样搬运密文，所以这个上限得让读的一侧也看得见。 */
    const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val ROUNDS_OFFSET = 7
    private const val HEADER_BYTES = MAGIC_BYTES + 1 + 4 + SALT_BYTES + IV_BYTES
    private const val MAX_ENVELOPE = 256 * 1024

    const val MIN_PASSWORD = 8
    /** 与 [Capture.MAX_TEXT] 同一个数：正文上限只有一处口径，输入和导入不会算出两个值。 */
    const val MAX_TEXT = 16_000

    /** 测试接缝（同 `Images.fetcher`、`Translate.transport`）：真机跑 12 万次，测试没必要陪跑。 */
    @Volatile var iterations = 120_000
        internal set

    private const val VERIFIER_PLAIN = "链藏主密码校验串"

    fun validPassword(password: CharArray): Boolean = password.size >= MIN_PASSWORD

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /** verifier 是一段用主密码加密的固定文本，靠它判断「这次输的密码对不对」，不必先存笔记。 */
    fun verifier(password: CharArray, salt: ByteArray): ByteArray = seal(password, salt, VERIFIER_PLAIN)

    /** 库里存 Base64，所以给调用方一个已经是字符串的版本。 */
    fun verifierText(password: CharArray, salt: ByteArray): String = encode(verifier(password, salt))

    fun check(session: KeySession, envelope: ByteArray): Boolean =
        runCatching { open(session, envelope) == VERIFIER_PLAIN }.getOrDefault(false)

    fun seal(password: CharArray, salt: ByteArray, plaintext: String): ByteArray {
        require(validPassword(password)) { "主密码至少需要 $MIN_PASSWORD 个字符" }
        return envelope(derive(password, salt, iterations), salt, plaintext)
    }

    /**
     * 已经解锁时用会话封箱：密钥从缓存拿，不再重跑派生。
     *
     * 这条路径不是优化而是必需 —— 否则每保存一条笔记都要跑 12 万次 PBKDF2，
     * 用户点一下「保存」就要等上半秒以上。
     */
    fun sealWith(session: KeySession, salt: ByteArray, plaintext: String): ByteArray =
        envelope(session.keyFor(salt, iterations), salt, plaintext)

    private fun envelope(key: SecretKey, salt: ByteArray, plaintext: String): ByteArray {
        require(plaintext.length <= MAX_TEXT) { "笔记正文最多 $MAX_TEXT 个字" }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val encrypted = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ByteArrayOutputStream(HEADER_BYTES + encrypted.size).apply {
            write(MAGIC)
            write(VERSION.toInt())
            write(iterations shr 24)
            write(iterations shr 16)
            write(iterations shr 8)
            write(iterations)
            write(salt)
            write(iv)
            write(encrypted)
        }.toByteArray()
    }

    fun open(session: KeySession, envelope: ByteArray): String {
        require(envelope.size in HEADER_BYTES..MAX_ENVELOPE) { "笔记密文长度异常" }
        require(envelope.copyOfRange(0, MAGIC_BYTES).contentEquals(MAGIC)) { "不是链藏笔记密文" }
        require(envelope[MAGIC_BYTES] == VERSION) { "不支持此笔记密文版本" }
        val rounds = readRounds(envelope)
        // 轮数字段是给格式自描述用的，不是给来路不明的字节随便定的：
        // 不设上限，一条伪造密文就能让解锁线程跑上亿次迭代。
        require(rounds in 1_000..2_000_000) { "笔记密文的迭代次数不合理" }
        val saltStart = MAGIC_BYTES + 5
        val salt = envelope.copyOfRange(saltStart, saltStart + SALT_BYTES)
        val iv = envelope.copyOfRange(saltStart + SALT_BYTES, HEADER_BYTES)
        val encrypted = envelope.copyOfRange(HEADER_BYTES, envelope.size)
        return try {
            String(cipher(Cipher.DECRYPT_MODE, session.keyFor(salt, rounds), iv).doFinal(encrypted), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("主密码不对，或这条内容已损坏", e)
        }
    }

    /** 用当前会话（旧口令）解开、用新口令重封。失败时由调用方保留原密文。 */
    fun reseal(session: KeySession, envelope: ByteArray, password: CharArray, salt: ByteArray): ByteArray =
        seal(password, salt, open(session, envelope))

    /** 库里存的是 Base64 字符串（见 [Note.cipher]），这两个封装让调用方不必各自处理编码。 */
    fun sealText(password: CharArray, salt: ByteArray, plaintext: String): String = encode(seal(password, salt, plaintext))

    fun sealTextWith(session: KeySession, salt: ByteArray, plaintext: String): String = encode(sealWith(session, salt, plaintext))

    fun openText(session: KeySession, stored: String): String = open(session, decode(stored))

    fun encode(envelope: ByteArray): String = java.util.Base64.getEncoder().encodeToString(envelope)

    /** 来路不明的字符串（导进来的备份）先过这一关：解不开就是解不开，不猜。 */
    fun decode(stored: String): ByteArray = runCatching { java.util.Base64.getDecoder().decode(stored) }
        .getOrElse { throw IllegalArgumentException("不是链藏笔记密文", it) }

    private fun readRounds(envelope: ByteArray): Int {
        var value = 0
        for (offset in ROUNDS_OFFSET until ROUNDS_OFFSET + 4) value = (value shl 8) or (envelope[offset].toInt() and 0xFF)
        return value
    }

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(TAG_BITS, iv)) }

    internal fun derive(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, KEY_BITS)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES"
            )
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * 一次解锁会话。口令原文只在这里存活，[lock] 会把它清零，所以本类不能改存 String。
 *
 * 留原文而不是只留派生好的密钥，是因为导入进来的笔记带着**自己的** salt，
 * 那些 salt 要到真正打开那条笔记时才第一次出现，届时还得能派生。
 */
class KeySession(password: CharArray) {
    private val secret = password.copyOf()
    private val cache = HashMap<String, SecretKeySpec>()

    fun keyFor(salt: ByteArray, rounds: Int): SecretKeySpec =
        cache.getOrPut("$rounds:${salt.toKeyTag()}") { NoteCrypto.derive(secret, salt, rounds) }

    /** 本次会话已经派生过几个 salt：正常是 1，多出来的都来自别人家的主密码。 */
    val derivedSalts: Int get() = cache.size

    fun lock() {
        cache.clear()
        secret.fill('\u0000')
    }

    val locked: Boolean get() = secret.all { it == '\u0000' }

    private fun ByteArray.toKeyTag(): String = joinToString("") { "%02x".format(it) }
}
