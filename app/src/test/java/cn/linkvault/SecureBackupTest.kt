package cn.linkvault

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecureBackupTest {
    private val item = Bookmark(
        url = "https://example.com/article?id=7",
        canonical = Links.canonical("https://example.com/article?id=7"),
        title = "加密测试",
        notes = "只在本机解密",
        tags = "安全,备份"
    )

    @Test fun roundTripKeepsBackupData() {
        val bytes = SecureBackup.encrypt(listOf(item), emptyList(), null, "correct horse".toCharArray())
        assertFalse(bytes.contentEquals(Backup.encode(listOf(item))))
        val restored = SecureBackup.decrypt(bytes, "correct horse".toCharArray()).bookmarks
        assertEquals(listOf(item.url), restored.map { it.url })
        assertEquals(item.title, restored.single().title)
        assertEquals(item.tags, restored.single().tags)
    }

    /** 口令统一之后，加密备份必须能把私密笔记原样搬过去，并且带着主密码记录。 */
    @Test fun secretNotesRideTheEncryptedChannelOnly() {
        val salt = NoteCrypto.newSalt()
        val password = "MiMa-123456"
        val master = VaultMaster(salt = NoteCrypto.encode(salt), verifier = NoteCrypto.verifierText(password.toCharArray(), salt))
        val secret = Note(
            cipher = NoteCrypto.sealText(password.toCharArray(), salt, "内网口令 4321"),
            secret = true
        )
        val plain = Note(text = "会议室 4102")

        val bytes = SecureBackup.encrypt(listOf(item), listOf(secret, plain), master, password.toCharArray())
        val restored = SecureBackup.decrypt(bytes, password.toCharArray())
        assertEquals(2, restored.notes.size)
        assertEquals(master.salt, restored.master?.salt)
        assertEquals(master.verifier, restored.master?.verifier)
        val back = restored.notes.single { it.secret }
        assertEquals("内网口令 4321", NoteCrypto.openText(KeySession(password.toCharArray()), back.cipher))
        assertEquals("会议室 4102", restored.notes.single { !it.secret }.text)
    }

    /** 明文那条通道压根不给私密笔记通过 —— 签名上就挡掉，不靠调用方记得过滤。 */
    @Test fun plainBackupCannotCarrySecretNotes() {
        val secret = Note(cipher = "YWJjZGU=", secret = true)
        assertThrows(IllegalArgumentException::class.java) {
            Backup.encode(listOf(item), listOf(secret))
        }
    }

    @Test fun wrongPasswordIsRejected() {
        val bytes = SecureBackup.encrypt(listOf(item), emptyList(), null, "correct horse".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            SecureBackup.decrypt(bytes, "wrong horse".toCharArray())
        }
    }

    @Test fun malformedEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureBackup.decrypt("not a backup".toByteArray(), "correct horse".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureBackup.encrypt(listOf(item), emptyList(), null, "short".toCharArray())
        }
    }
}
