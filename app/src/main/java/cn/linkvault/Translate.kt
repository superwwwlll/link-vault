package cn.linkvault

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一键翻译：调用用户自己配置的 OpenAI 兼容接口。
 *
 * 与抓取页面信息不同，这里是另一个数据出口——正文会原样发给用户填的那个地址。
 * 所以三道闸门缺一不可：设置里的开关默认关闭、密钥必须由用户自己填、只有点「译成中文」才发请求。
 * 不引第三方 SDK，请求体和响应都用 org.json（Android 自带）手写。
 */
internal object Translate {
    const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    /** 单次请求的正文字数。太长的上下文会显著推高费用，也会撞到小模型的输出上限。 */
    const val CHUNK_CHARS = 1_200

    /** 一篇最多译这么多字，超出部分明确告知而不是静默截断。 */
    const val MAX_CHARS = 24_000

    val SYSTEM = "把用户给出的 Markdown 翻译成简体中文。保留原有结构：标题层级、列表、引用、表格、代码块" +
        "与 ![图片](链接) 的写法，代码块内的内容、链接地址和专有名词原样保留。只输出译文，不要解释。"

    data class Config(val endpoint: String, val apiKey: String, val model: String) {
        val ready: Boolean get() = apiKey.isNotBlank() && endpoint.startsWith("https://", true)
    }

    /** 单测与截图用的接缝：换掉它就能完全不碰网络跑完整流程。 */
    @Volatile
    var transport: (endpoint: String, apiKey: String, body: String) -> String =
        { endpoint, key, body -> Net.post(endpoint, key, body) }

    fun request(config: Config, chunk: String): String = AiProviders.adapt(config, JSONObject().apply {
        put("model", config.model.trim())
        put("temperature", 0)
        put(
            "messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM))
                .put(JSONObject().put("role", "user").put("content", chunk))
        )
    }).toString()

    /** 从响应里取译文。服务商的错误信息要原样带出来，否则用户只知道「失败了」。 */
    fun reply(json: String): String {
        val root = runCatching { JSONObject(json) }.getOrElse { error("翻译服务返回的不是 JSON，请检查接口地址") }
        root.optJSONObject("error")?.let {
            val message = it.optString("message").trim().ifBlank { "未知错误" }
            error("翻译服务报错：${message.take(200)}")
        }
        val choices = root.optJSONArray("choices") ?: error("返回里没有 choices 字段，这个接口可能不兼容 OpenAI 格式")
        require(choices.length() > 0) { "翻译服务返回了空结果" }
        val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty().trim()
        require(content.isNotEmpty()) { "翻译服务返回了空译文" }
        return content
    }

    /**
     * 按段落攒成小块，攒不下就硬切。
     *
     * 段落边界优先：Markdown 的标题、列表和代码块都在行首，从中间切开会让模型分不清结构。
     */
    fun chunks(source: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) out.add(current.toString().trim())
            current.setLength(0)
        }
        source.split("\n\n").forEach { block ->
            var rest = block
            // 单个段落就超过一块的长度：先补满当前块，剩下的继续切，保证每轮都有进展
            while (rest.length > CHUNK_CHARS) {
                val room = CHUNK_CHARS - current.length - 2
                if (room > 0) {
                    current.append(rest.take(room))
                    rest = rest.drop(room)
                }
                flush()
            }
            if (current.isNotEmpty() && current.length + rest.length + 2 > CHUNK_CHARS) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(rest)
        }
        flush()
        return out.filter { it.isNotBlank() }
    }

    /**
     * 逐段翻译。[onProgress] 收到（已完成段数, 总段数），用于把「等很久」显示成进度。
     */
    suspend fun translate(config: Config, source: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): String {
        require(config.ready) { "先填好接口地址和密钥" }
        val body = source.take(MAX_CHARS)
        val parts = chunks(body)
        require(parts.isNotEmpty()) { "没有可翻译的正文" }
        val out = StringBuilder()
        parts.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val raw = withContext(kotlinx.coroutines.Dispatchers.IO) {
                transport(config.endpoint, config.apiKey, request(config, chunk))
            }
            currentCoroutineContext().ensureActive()
            if (index > 0) out.append("\n\n")
            out.append(reply(raw))
            onProgress(index + 1, parts.size)
        }
        if (source.length > MAX_CHARS) {
            out.append("\n\n").append("（原文 ${source.length} 字，只翻译了前 $MAX_CHARS 字）")
        }
        return out.toString()
    }
}
