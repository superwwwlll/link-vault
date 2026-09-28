package cn.linkvault

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import org.json.JSONObject

/**
 * 可选的联网抓取。
 *
 * 设计边界（有意为之，请勿放宽）：
 * - 只在「设置 → 联网抓取」开启时才会发起请求，且抓取页面信息仍是逐条点击触发的，绝不后台轮询；
 * - 只请求 https，明文 http 直接拒绝；
 * - 页面只读开头一段（标题 96KB、正文 384KB），图片单张封顶 4MB 且只进内存缓存、不落盘；
 * - 不发送 Cookie、不带凭据、不发 Referer、不执行脚本、不跟随跨站重定向链（最多 3 跳，且必须仍是 https）；
 * - [post] 是唯一的例外出口：翻译走用户自己填的接口，密钥只在 Authorization 头里，
 *   必须同时在设置里开启「AI 翻译」并由用户点按钮才发，正文会原样发给那个地址；
 * - 其余抓取与解析结果只存在本机数据库里。
 */
object Net {
    const val MAX_BYTES = 96 * 1024
    const val MAX_ARTICLE_BYTES = 384 * 1024
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    /** 一次翻译请求的响应：译文比原文短，512KB 足够，同时挡住失控的返回。 */
    const val MAX_REPLY_BYTES = 512 * 1024
    const val TIMEOUT_MS = 12_000
    const val MAX_REDIRECTS = 3

    data class Head(val title: String, val description: String, val siteName: String, val image: String) {
        val isEmpty: Boolean get() = title.isEmpty() && description.isEmpty()
    }

    fun fetchHead(url: String): Head {
        require(Links.valid(url)) { "链接格式不合法" }
        require(url.startsWith("https://", true)) { "只能抓取 https 链接，明文 http 已禁用" }
        var target = url
        var hops = 0
        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-${MAX_BYTES - 1}")
                setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("User-Agent", "LinkVault/1.2 (local bookmark manager)")
                setRequestProperty("Connection", "close")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) { "服务器返回了空的重定向地址" }
                    require(hops++ < MAX_REDIRECTS) { "重定向次数过多，已停止" }
                    val next = runCatching { URI(target).resolve(location) }.getOrNull()
                        ?: error("服务器返回的重定向地址无法解析")
                    target = next.toString()
                    require(target.startsWith("https://", true) && Links.valid(target)) {
                        "重定向到了不安全或无效的地址，已停止"
                    }
                    continue
                }
                require(code in 200..299) { "服务器返回 HTTP $code" }
                val bytes = openStream(connection).use { readBounded(it, MAX_BYTES) }
                val charset = charsetOf(connection.contentType, bytes)
                return parseHead(decode(bytes, charset), target)
            } catch (e: java.io.IOException) {
                throw java.io.IOException(e.message ?: "网络请求失败", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 纯解析：不碰网络，可以直接单测。
     *
     * baseUrl 用来把 `og:image="/a.png"` 这类相对地址展开；留空时相对地址一律丢弃。
     */
    fun parseHead(html: String, baseUrl: String = ""): Head {
        // secure_url 优先：它就是同一张图的 https 版本。逐个试而不是交给 metaContent 一次挑，
        // 因为「存在但展不开」的图（http:// 或畸形相对路径）要跳过，去试下一个候选。
        val image = IMAGE_KEYS.firstNotNullOfOrNull { key ->
            Html.metaContent(html, listOf(key))
                ?.let { Links.absolute(baseUrl, it) }
                ?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        return Head(
            // 一定要 collapse：og:title 里带换行是常态（网页作者为了排版在 meta 里换行写），
            // 原样入库会让卡片在句子中间断成两截。
            title = Html.collapse((Html.metaContent(html, listOf("og:title", "twitter:title")) ?: Html.titleTag(html)).orEmpty()).take(200),
            description = Html.collapse(Html.metaContent(html, listOf("og:description", "description", "twitter:description")).orEmpty()).take(300),
            siteName = Html.collapse(Html.metaContent(html, listOf("og:site_name", "application-name")).orEmpty()).take(60),
            image = image.take(500)
        )
    }

    private val IMAGE_KEYS = listOf("og:image:secure_url", "og:image", "og:image:url", "twitter:image:src", "twitter:image")

    /**
     * 离线正文抓取（Reader Mode Snapshot）。
     * 限额读取网页内容（最多 384KB），提取纯净正文用于离线阅读。
     */
    fun fetchArticle(url: String): String {
        require(Links.valid(url)) { "链接格式不合法" }
        require(url.startsWith("https://", true)) { "只能抓取 https 链接，明文 http 已禁用" }
        var target = url
        var hops = 0
        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-${MAX_ARTICLE_BYTES - 1}")
                setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("User-Agent", "LinkVault/1.3 (local bookmark manager; offline reader)")
                setRequestProperty("Connection", "close")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) { "服务器返回了空的重定向地址" }
                    require(hops++ < MAX_REDIRECTS) { "重定向次数过多，已停止" }
                    val next = runCatching { URI(target).resolve(location) }.getOrNull()
                        ?: error("服务器返回的重定向地址无法解析")
                    target = next.toString()
                    require(target.startsWith("https://", true) && Links.valid(target)) {
                        "重定向到了不安全或无效的地址，已停止"
                    }
                    continue
                }
                require(code in 200..299) { "服务器返回 HTTP $code" }
                val bytes = openStream(connection).use { readBounded(it, MAX_ARTICLE_BYTES) }
                val charset = charsetOf(connection.contentType, bytes)
                val html = decode(bytes, charset)
                val article = Html.extractArticle(html, target)
                require(article.isNotBlank()) { "未能从页面提取到有效正文" }
                return article
            } catch (e: java.io.IOException) {
                throw java.io.IOException(e.message ?: "网络请求失败", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 下载一张图片的原始字节，交给 Images 解码。
     *
     * 图片地址有两个来源：抓回来的网页，以及用户自己导入的备份文件。后者可以手填任何
     * 字符串，所以这里必须重新验一遍 https 与合法性，不能因为「入库时查过」就放行。
     * 故意不发 Referer：加载谁的图，不该让那个站顺带知道这条收藏存在。
     */
    fun fetchImage(url: String): ByteArray {
        require(Links.valid(url)) { "图片地址不合法" }
        require(url.startsWith("https://", true)) { "只加载 https 图片，明文 http 已禁用" }
        var target = url
        var hops = 0
        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg;q=0.9,*/*;q=0.1")
                setRequestProperty("User-Agent", "LinkVault/1.3 (local bookmark manager)")
                setRequestProperty("Connection", "close")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) { "服务器返回了空的重定向地址" }
                    require(hops++ < MAX_REDIRECTS) { "重定向次数过多，已停止" }
                    val next = runCatching { URI(target).resolve(location) }.getOrNull()
                        ?: error("服务器返回的重定向地址无法解析")
                    target = next.toString()
                    require(target.startsWith("https://", true) && Links.valid(target)) {
                        "重定向到了不安全或无效的地址，已停止"
                    }
                    continue
                }
                require(code in 200..299) { "图片服务器返回 HTTP $code" }
                return openStream(connection).use { readBounded(it, MAX_IMAGE_BYTES) }
            } catch (e: java.io.IOException) {
                throw java.io.IOException(e.message ?: "网络请求失败", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 向用户自配的接口发一个 JSON 请求（目前只有翻译在用）。
     *
     * 密钥只进 Authorization 头，绝不写进 URL 或日志。错误响应体也要读出来，
     * 否则「401 / 模型名写错 / 余额不足」在界面上只会变成一句没用的「请求失败」。
     */
    fun post(endpoint: String, apiKey: String, body: String): String {
        require(endpoint.startsWith("https://", true)) { "翻译接口必须走 https" }
        require(Links.valid(endpoint)) { "翻译接口地址不合法" }
        require(apiKey.isNotBlank()) { "还没有填写接口密钥" }
        val originHost = hostOf(endpoint)
        var target = endpoint
        var hops = 0
        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = 60_000
                instanceFollowRedirects = false
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("User-Agent", "LinkVault/1.4 (local bookmark manager)")
                setRequestProperty("Connection", "close")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                    require(!location.isNullOrBlank()) { "接口返回了空的重定向地址" }
                    require(hops++ < MAX_REDIRECTS) { "接口重定向次数过多，已停止" }
                    val next = runCatching { URI(target).resolve(location) }.getOrNull()
                        ?: error("接口返回的重定向地址无法解析")
                    target = next.toString()
                    require(target.startsWith("https://", true) && Links.valid(target)) {
                        "接口重定向到了不安全或无效的地址，已停止"
                    }
                    // 密钥只发给用户自己填的那个主机：跨域跳转一律停，否则一次配置笔误就把 key 送到别人域名上。
                    val host = hostOf(target)
                    require(host == originHost) { "接口跳转到了另一个域名（$host），为避免密钥外泄已停止" }
                    continue
                }
                // 非 2xx 时正文在 errorStream 里，那里才有能看懂的原因
                val raw = if (code in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
                val bytes = openStream(raw, connection.contentEncoding?.lowercase(Locale.ROOT).orEmpty()).use { readBounded(it, MAX_REPLY_BYTES) }
                val text = String(bytes, Charsets.UTF_8)
                require(code in 200..299) {
                    val reason = runCatching { JSONObject(text).optJSONObject("error")?.optString("message")?.trim() }
                        .getOrNull().orEmpty()
                    "接口返回 HTTP $code" + if (reason.isBlank()) "" else "：${reason.take(200)}"
                }
                return text
            } catch (e: java.io.IOException) {
                throw java.io.IOException(e.message ?: "网络请求失败", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")

    /**
     * 自动处理网络响应流：
     * 1. 识别 Content-Encoding: gzip 或通过 0x1f 0x8b 魔数嗅探，包裹 GZIPInputStream；
     * 2. 识别 deflate 编码，包裹 InflaterInputStream；
     * 3. 避免压缩二进制数据当作文本直接解码导致的严重乱码。
     */
    internal fun openStream(connection: HttpURLConnection): InputStream =
        openStream(connection.inputStream, connection.contentEncoding?.lowercase(Locale.ROOT).orEmpty())

    internal fun openStream(raw: InputStream, encoding: String = ""): InputStream {
        val buffered = BufferedInputStream(raw)
        buffered.mark(4)
        val magic = ByteArray(2)
        val readMagic = buffered.read(magic)
        buffered.reset()

        val isGzip = encoding.contains("gzip") || (readMagic == 2 && (magic[0].toInt() and 0xFF) == 0x1F && (magic[1].toInt() and 0xFF) == 0x8B)
        val isDeflate = encoding.contains("deflate")

        if (isGzip) {
            return try {
                GZIPInputStream(buffered)
            } catch (_: Exception) {
                buffered.reset()
                buffered
            }
        }
        if (isDeflate) {
            return try {
                InflaterInputStream(buffered)
            } catch (_: Exception) {
                buffered.reset()
                buffered
            }
        }
        return buffered
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (output.size() < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        } catch (_: java.util.zip.ZipException) {
            // 压缩流在截断处结束，返回已解压部分
        } catch (_: java.io.EOFException) {
            // 到达流末尾
        }
        return output.toByteArray()
    }

    /**
     * 多重字符集识别：
     * 1. 优先读取响应头 Content-Type 里的 charset；
     * 2. 其次嗅探解压后前 16KB 内容中的 <meta charset="..."> 或 <meta http-equiv="Content-Type"> 或 xml encoding；
     * 3. 统一规范国内常见的 GBK、GB2312、CP936 别名至 GB18030。
     */
    internal fun charsetOf(contentType: String?, bytes: ByteArray): Charset? {
        val headerMatch = Regex("""charset\s*=\s*['"]?([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())?.groupValues?.get(1)
        if (!headerMatch.isNullOrBlank()) {
            val cs = resolveCharset(headerMatch)
            if (cs != null) return cs
        }

        if (bytes.isNotEmpty()) {
            val sampleLen = minOf(bytes.size, 16384)
            val sample = String(bytes, 0, sampleLen, Charsets.ISO_8859_1)
            val metaMatch = Regex("""(?:charset|encoding)\s*=\s*['"]?([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(sample)?.groupValues?.get(1)
            if (!metaMatch.isNullOrBlank()) {
                val cs = resolveCharset(metaMatch)
                if (cs != null) return cs
            }
        }
        return null
    }

    private fun resolveCharset(name: String): Charset? {
        val trimmed = name.trim().lowercase(Locale.ROOT)
        val normalized = when (trimmed) {
            "gb2312", "gb_2312", "gbk", "cp936", "ms936" -> "GB18030"
            "big5", "big5-hkscs" -> "Big5"
            "utf8" -> "UTF-8"
            else -> name.trim()
        }
        return runCatching { Charset.forName(normalized) }.getOrNull()
    }

    /**
     * 稳健解码：
     * 1. 若显式探测到非 UTF-8 字符集（如 GBK），优先按该字符集解码；
     * 2. 若未显式标明，先用严格 UTF-8 校验；如果遇到非 UTF-8 字节序（国内老站常见），自动无缝回退至 GB18030 解码，彻底解决中文乱码。
     */
    internal fun decode(bytes: ByteArray, preferredCharset: Charset?): String {
        if (bytes.isEmpty()) return ""
        if (preferredCharset != null && preferredCharset != Charsets.UTF_8) {
            return runCatching {
                preferredCharset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrElse { String(bytes, preferredCharset) }
        }

        val utf8Decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            val gbk = runCatching { Charset.forName("GB18030") }.getOrNull()
            if (gbk != null) {
                runCatching {
                    gbk.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                }.getOrElse { String(bytes, Charsets.UTF_8) }
            } else {
                String(bytes, Charsets.UTF_8)
            }
        }
    }
}
