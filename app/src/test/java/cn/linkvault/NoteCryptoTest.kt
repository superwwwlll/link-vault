package cn.linkvault

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteCryptoTest {
    private val password = "ZaiXian-MiMa-88".toCharArray()
    private val other = "Qita-MiMa-99".toCharArray()
    private lateinit var salt: ByteArray
    private lateinit var session: KeySession

    @Before fun setUp() {
        // 迭代次数是安全参数，但测试不该为 12 万次派生陪跑；仍留在 open() 允许的区间内。
        NoteCrypto.iterations = 10_000
        salt = NoteCrypto.newSalt()
        session = KeySession(password)
    }

    @Test fun roundTripKeepsEveryKindOfText() {
        listOf("银行验证码 481920", "多行\n正文\r\n保留\n换行", "含 emoji 🔐 与空格  的字", "").forEach { text ->
            assertEquals(text, NoteCrypto.open(session, NoteCrypto.seal(password, salt, text)))
        }
    }

    @Test fun plaintextNeverAppearsInEnvelope() {
        val secret = "wangliu@corp 密码 Abc12345"
        val envelope = NoteCrypto.seal(password, salt, secret)
        assertFalse(envelope.toString(Charsets.UTF_8).contains("Abc12345"))
        assertFalse(String(envelope, Charsets.UTF_8).contains(secret))
    }

    @Test fun sealingTwiceProducesDifferentBytesButSameText() {
        val a = NoteCrypto.seal(password, salt, "同一个内容")
        val b = NoteCrypto.seal(password, salt, "同一个内容")
        assertFalse(a.contentEquals(b))
        assertEquals(NoteCrypto.open(session, a), NoteCrypto.open(session, b))
    }

    @Test fun wrongPasswordIsRejected() {
        val envelope = NoteCrypto.seal(password, salt, "只有我知道")
        val e = assertThrows(IllegalArgumentException::class.java) {
            NoteCrypto.open(KeySession(other), envelope)
        }
        assertTrue(e.message!!.contains("主密码不对"))
    }

    @Test fun tamperedCiphertextIsRejected() {
        val envelope = NoteCrypto.seal(password, salt, "不能改我")
        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) { NoteCrypto.open(session, envelope) }
    }

    @Test fun foreignSaltStillOpensAndCountsSeparately() {
        val mine = NoteCrypto.seal(password, salt, "本机的")
        val theirs = NoteCrypto.seal(password, NoteCrypto.newSalt(), "从别人备份导进来的")
        assertEquals("本机的", NoteCrypto.open(session, mine))
        assertEquals("从别人备份导进来的", NoteCrypto.open(session, theirs))
        assertEquals(2, session.derivedSalts)
    }

    @Test fun verifierAcceptsOnlyTheRightPassword() {
        val envelope = NoteCrypto.verifier(password, salt)
        assertTrue(NoteCrypto.check(session, envelope))
        assertFalse(NoteCrypto.check(KeySession(other), envelope))
        assertFalse(NoteCrypto.check(session, NoteCrypto.seal(password, salt, "链藏主密码校验串X")))
    }

    @Test fun shortPasswordAndOversizedTextAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NoteCrypto.seal("abc1234".toCharArray(), salt, "太短的口令")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoteCrypto.seal(password, salt, "字".repeat(NoteCrypto.MAX_TEXT + 1))
        }
        assertEquals(NoteCrypto.MAX_TEXT, NoteCrypto.open(session, NoteCrypto.seal(password, salt, "字".repeat(NoteCrypto.MAX_TEXT))).length)
    }

    @Test fun malformedEnvelopeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { NoteCrypto.open(session, "not a note".toByteArray()) }
        val envelope = NoteCrypto.seal(password, salt, "正常密文")
        envelope[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { NoteCrypto.open(session, envelope) }
        val bumpVersion = NoteCrypto.seal(password, salt, "正常密文").also { it[6] = 9 }
        assertThrows(IllegalArgumentException::class.java) { NoteCrypto.open(session, bumpVersion) }
    }

    @Test fun absurdRoundsFieldIsRejectedBeforeAnyDerivation() {
        val envelope = NoteCrypto.seal(password, salt, "伪造迭代次数")
        // 轮数占封套第 7..10 字节，改成 1 亿：必须在派生之前就拦下。
        intArrayOf(0x05, 0xF5, 0xE1, 0x00).forEachIndexed { index, value -> envelope[7 + index] = value.toByte() }
        val e = assertThrows(IllegalArgumentException::class.java) { NoteCrypto.open(session, envelope) }
        assertTrue(e.message!!.contains("迭代次数"))
        assertEquals(0, session.derivedSalts)
    }

    @Test fun lockingDestroysAccessToTheSession() {
        val envelope = NoteCrypto.seal(password, salt, "锁定之后就打不开")
        session.lock()
        assertTrue(session.locked)
        assertThrows(IllegalArgumentException::class.java) { NoteCrypto.open(session, envelope) }
        // 失败的那次派生会留在缓存里，所以再锁一次才证明缓存真的被清掉了。
        session.lock()
        assertEquals(0, session.derivedSalts)
    }

    @Test fun resealMovesContentToANewPasswordAndSalt() {
        val original = NoteCrypto.seal(password, salt, "改密码之前")
        val newSalt = NoteCrypto.newSalt()
        val moved = NoteCrypto.reseal(session, original, other, newSalt)
        val fresh = KeySession(other)
        assertEquals("改密码之前", NoteCrypto.open(fresh, moved))
        assertThrows(IllegalArgumentException::class.java) { NoteCrypto.open(KeySession(password), moved) }
    }

    @Test fun resealKeepsOriginalWhenThePasswordIsWrong() {
        val original = NoteCrypto.seal(password, salt, "这条不属于当前会话")
        assertThrows(IllegalArgumentException::class.java) {
            NoteCrypto.reseal(KeySession(other), original, other, NoteCrypto.newSalt())
        }
        // 失败不能破坏原密文：调用方靠这条保证改密码整体回滚后内容还在。
        assertEquals("这条不属于当前会话", NoteCrypto.open(session, original))
    }
}
