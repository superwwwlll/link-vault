package cn.linkvault

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslateTest {
    private val realTransport = Translate.transport
    private val config = Translate.Config(Translate.DEFAULT_ENDPOINT, "sk-test-key", "gpt-4o-mini")

    private fun response(text: String) = JSONObject().put(
        "choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))
    ).toString()

    private fun userContent(body: String) = JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")

    @Test fun theRequestBodyIsOpenAiCompatible() {
        val body = Translate.request(config, "第一段正文")
        val json = JSONObject(body)
        assertEquals("gpt-4o-mini", json.getString("model"))
        assertEquals(0.0, json.getDouble("temperature"), 0.0)
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("第一段正文", messages.getJSONObject(1).getString("content"))
        assertTrue(Translate.SYSTEM.contains("Markdown"))
    }

    @Test fun theModelAnswerIsWhatComesBackOut() {
        assertEquals("你好，世界", Translate.reply(response("你好，世界")))
        assertEquals("带\n换行和 \"引号\"", Translate.reply(response("带\n换行和 \"引号\"")))
    }

    @Test fun theProviderReasonIsCarredIntoTheMessage() {
        val e = assertFails { Translate.reply("""{"error":{"message":"Your account is overdue."}}""") }
        assertTrue(e.message!!, e.message!!.contains("overdue"))
        assertTrue(assertFails { Translate.reply("""{"unexpected":true}""") }.message!!.contains("choices"))
        assertTrue(assertFails { Translate.reply("""{"choices":[]}""") }.message!!.isNotEmpty())
        assertTrue(assertFails { Translate.reply(response("   ")) }.message!!.isNotEmpty())
        assertTrue(assertFails { Translate.reply("这不是 JSON") }.message!!.contains("JSON"))
    }

    @Test fun chunksNeverExceedTheLimitAndNeverLoseText() {
        val paragraphs = (1..40).map { "第 $it 段。" + "字".repeat(300) }
        val source = paragraphs.joinToString("\n\n")
        val parts = Translate.chunks(source)
        parts.forEach { assertTrue("实测 ${it.length}", it.length <= Translate.CHUNK_CHARS) }
        assertTrue(parts.size > 1)
        // 段落能整块放下时不硬切，切出来的块拼回去必须一字不少
        assertEquals(source.replace("\n\n", "").length, parts.joinToString("").replace("\n\n", "").length)

        val single = "长".repeat(Translate.CHUNK_CHARS * 2 + 37)
        val hard = Translate.chunks(single)
        assertTrue(hard.isNotEmpty())
        hard.forEach { assertTrue(it.length <= Translate.CHUNK_CHARS) }
        assertEquals(single.length, hard.joinToString("").length)
        assertTrue(Translate.chunks("").isEmpty())
        assertTrue(Translate.chunks("   \n\n  ").isEmpty())
    }

    @Test fun aLongArticleIsTranslatedChunkByChunkWithProgress() = runBlocking {
        val calls = AtomicInteger()
        val steps = mutableListOf<Pair<Int, Int>>()
        Translate.transport = { _, _, body ->
            calls.incrementAndGet()
            response("译文 ${userContent(body).length}")
        }
        try {
            val source = (1..30).joinToString("\n\n") { "第 $it 段" + "内容".repeat(300) }
            val result = Translate.translate(config, source) { done, total -> steps += done to total }
            assertEquals(calls.get(), steps.size)
            assertEquals(listOf(calls.get()), steps.map { it.second }.distinct())
            assertEquals(calls.get(), result.split("\n\n").size)
            assertTrue(calls.get() > 1)
        } finally { Translate.transport = realTransport }
    }

    @Test fun oneRequestIsEnoughForAShortText() = runBlocking {
        var calls = 0
        Translate.transport = { _, _, _ -> calls++; response("译文") }
        try {
            assertEquals("译文", Translate.translate(config, "只有一小段正文"))
            assertEquals(1, calls)
        } finally { Translate.transport = realTransport }
    }

    @Test fun theOvershootIsAnnouncedInsteadOfQuietlyDropped() = runBlocking {
        var sent = 0
        Translate.transport = { _, _, body -> sent += userContent(body).length; response("译${userContent(body)}") }
        try {
            val source = "字".repeat(Translate.MAX_CHARS + 500)
            val result = Translate.translate(config, source)
            assertTrue(result.contains("只翻译了前 ${Translate.MAX_CHARS} 字"))
            assertTrue("实际发出 $sent 字", sent == Translate.MAX_CHARS)
        } finally { Translate.transport = realTransport }
    }

    @Test fun nothingIsSentBeforeTheKeyAndEndpointAreBothUsable() {
        var calls = 0
        Translate.transport = { _, _, _ -> calls++; response("译文") }
        try {
            listOf(
                Translate.Config("", "sk-x", "m"),
                Translate.Config("http://api.example.com/v1/chat/completions", "sk-x", "m"),
                Translate.Config(Translate.DEFAULT_ENDPOINT, "", "m"),
                Translate.Config(Translate.DEFAULT_ENDPOINT, "  ", "m")
            ).forEach {
                val e = assertFails { runBlocking { Translate.translate(it, "正文") } }
                assertTrue(e.message!!.isNotEmpty())
            }
            assertEquals(0, calls)
        } finally { Translate.transport = realTransport }
    }

    @Test fun transportStopsAtHttpsAndNeverSendsAnEmptyKey() {
        assertTrue(assertFails { Net.post("http://api.example.com/v1/chat/completions", "sk-x", "{}") }.message!!.contains("https"))
        assertTrue(assertFails { Net.post("javascript:alert(1)", "sk-x", "{}") }.message!!.contains("https"))
        assertTrue(assertFails { Net.post("https://api.example.com/v1/chat/completions", "", "{}") }.message!!.contains("密钥"))
        assertTrue(Translate.DEFAULT_ENDPOINT.startsWith("https://"))
        assertEquals(Translate.DEFAULT_ENDPOINT, config.endpoint)
    }

    private inline fun assertFails(block: () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        throw AssertionError("预期失败，实际通过")
    }
}
