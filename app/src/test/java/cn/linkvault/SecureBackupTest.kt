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
        val bytes = SecureBackup.encrypt(listOf(item), "correct horse".toCharArray())
        assertFalse(bytes.contentEquals(Backup.encode(listOf(item))))
        val restored = SecureBackup.decrypt(bytes, "correct horse".toCharArray())
        assertEquals(listOf(item.url), restored.map { it.url })
        assertEquals(item.title, restored.single().title)
        assertEquals(item.tags, restored.single().tags)
    }

    @Test fun wrongPasswordIsRejected() {
        val bytes = SecureBackup.encrypt(listOf(item), "correct horse".toCharArray())
        assertThrows(IllegalArgumentException::class.java) {
            SecureBackup.decrypt(bytes, "wrong horse".toCharArray())
        }
    }

    @Test fun malformedEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SecureBackup.decrypt("not a backup".toByteArray(), "correct horse".toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SecureBackup.encrypt(listOf(item), "short".toCharArray())
        }
    }
}
