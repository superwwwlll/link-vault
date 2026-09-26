package cn.linkvault

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 可选的联网抓取。
 *
 * 设计边界（有意为之，请勿放宽）：
 * - 只在用户逐条点击「抓取页面信息」且设置开关已打开时才会发起请求，绝不自动联网；
 * - 只请求 https，明文 http 直接拒绝；
 * - 只读页面开头一小段，不下载正文、图片或整页；
 * - 不发送 Cookie、不带凭据、不执行脚本、不跟随跨站重定向链（最多 3 跳，且必须仍是 https）；
 * - 解析出的内容只存在本机数据库里。
 */
object Net {
    const val MAX_BYTES = 96 * 1024
    const val TIMEOUT_MS = 12_000
    const val MAX_REDIRECTS = 3

    data class Head(val title: String, val description: String, val siteName: String) {
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
                setRequestProperty("Range", "bytes=0-$MAX_BYTES")
                setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
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
                    require(target.startsWith("https://", true)) { "重定向到了非 https 地址，已停止" }
                    continue
                }
                require(code in 200..299) { "服务器返回 HTTP $code" }
                val bytes = connection.inputStream.use { readBounded(it, MAX_BYTES) }
                val charset = charsetOf(connection.contentType, bytes)
                return parseHead(decode(bytes, charset))
            } catch (e: java.io.IOException) {
                throw java.io.IOException(e.message ?: "网络请求失败", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** 纯解析：不碰网络，可以直接单测。 */
    fun parseHead(html: String): Head = Head(
        title = (Html.metaContent(html, listOf("og:title", "twitter:title")) ?: Html.titleTag(html)).orEmpty().take(200),
        description = Html.metaContent(html, listOf("og:description", "description", "twitter:description")).orEmpty().take(300),
        siteName = Html.metaContent(html, listOf("og:site_name", "application-name")).orEmpty().take(60)
    )

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** 优先用响应头声明的编码，其次从页面头部的 meta charset 嗅探。中文站点常见 GBK，需要照顾。 */
    private fun charsetOf(contentType: String?, bytes: ByteArray): Charset {
        val header = Regex("charset\\s*=\\s*\"?([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType.orEmpty())?.groupValues?.get(1)
        val sniff = if (bytes.isEmpty()) null else {
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        }
        for (name in listOfNotNull(header, sniff)) {
            val charset = runCatching { Charset.forName(name.trim()) }.getOrNull()
            if (charset != null) return charset
        }
        return Charsets.UTF_8
    }

    /** 只截了前 96KB，末尾可能切断多字节字符，所以用宽容解码而不是直接抛错。 */
    private fun decode(bytes: ByteArray, charset: Charset): String = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
}
