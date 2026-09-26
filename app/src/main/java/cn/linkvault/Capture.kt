package cn.linkvault

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/** 一条刚进来的待收藏内容：正文、系统已经附带的标题、以及可能带标题的 HTML 片段。 */
data class Shared(val text: String, val title: String = "", val html: String = "")

/**
 * 采集入口。
 *
 * 关键点：操作系统分享链接时往往**已经**把页面标题一起递过来了（EXTRA_SUBJECT / EXTRA_TITLE /
 * EXTRA_HTML_TEXT），复制超链接时剪贴板里也常常带一份 HTML。这些都不用联网就能拿到标题，
 * 以前只读了 EXTRA_TEXT，等于把系统送上门的信息丢掉了。
 */
object Capture {
    /** API 32 才引入常量，直接取字符串值即可在所有版本上读取。 */
    private const val EXTRA_TITLE = "android.intent.extra.TITLE"
    private const val EXTRA_HTML_TEXT = "android.intent.extra.HTML_TEXT"
    private const val MAX_TEXT = 16000
    private const val MAX_HTML = 100_000

    fun fromShare(intent: Intent?): Shared? {
        if (intent == null) return null
        val type = intent.type.orEmpty()
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (type.isNotEmpty() && type != "text/plain" && type != "text/html") null
                else build(charSequence(intent, Intent.EXTRA_TEXT), intent)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (type.isNotEmpty() && type != "text/plain" && type != "text/html") return null
                val list = textList(intent) ?: return null
                if (list.isEmpty()) null else build(list.joinToString("\n"), intent)
            }
            Intent.ACTION_PROCESS_TEXT -> build(charSequence(intent, Intent.EXTRA_PROCESS_TEXT), intent)
            else -> null
        }
    }

    private fun build(body: CharSequence?, intent: Intent): Shared? {
        val text = body?.toString().orEmpty().take(MAX_TEXT)
        val html = charSequence(intent, EXTRA_HTML_TEXT)?.toString().orEmpty().take(MAX_HTML)
        if (text.isBlank() && html.isBlank()) return null
        val title = Links.titleFromShared(
            charSequence(intent, Intent.EXTRA_SUBJECT)?.toString() ?: charSequence(intent, EXTRA_TITLE)?.toString()
        )
        return Shared(text, title, html)
    }

    /** 分享方可能塞了类型不对的值，取值一律容错。 */
    private fun charSequence(intent: Intent, name: String): CharSequence? =
        runCatching { intent.getCharSequenceExtra(name) }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun textList(intent: Intent): List<String>? = runCatching {
        val raw = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT) ?: return@runCatching null
        raw.map { it.toString().take(MAX_TEXT) }
    }.getOrNull()

    /** 一次读取剪贴板里的纯文本与 HTML 两种表示。 */
    fun clipboard(context: Context): Shared {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return Shared("")
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return Shared("")
        val texts = ArrayList<String>(clip.itemCount)
        val html = StringBuilder()
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index) ?: continue
            item.text?.toString()?.let { texts += it }
            item.htmlText?.let { if (html.isNotEmpty()) html.append('\n'); html.append(it) }
        }
        return Shared(
            texts.joinToString("\n").take(MAX_TEXT),
            "",
            html.toString().take(MAX_HTML)
        )
    }
}
