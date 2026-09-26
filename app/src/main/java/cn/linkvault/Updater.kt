package cn.linkvault

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 应用内自更新。
 *
 * 只访问自己发布仓库的两个固定地址，除此之外不发任何请求、不带任何本机信息、不带 Cookie：
 *
 *   <BASE>/latest.json            版本清单（几个字段的 JSON，几十字节）
 *   <BASE>/lian-cang-debug.apk    安装包
 *
 * 下载完会核对 SHA-256；即使文件被替换，Android 在安装时还会再校验签名是否与已装版本一致，
 * 不一致会直接拒绝安装 —— 两道都过不了。
 */
object Updater {
    const val BASE = "https://github.com/superwwwlll/link-vault/releases/latest/download"
    const val MANIFEST_URL = "$BASE/latest.json"
    const val APK_URL = "$BASE/lian-cang-debug.apk"
    const val PACKAGE = "cn.linkvault"
    private const val TAG = "LinkVault.Update"

    /** 连接给 20 秒；读取给 45 秒 —— 只要有字节在滴，读超时就不会触发，
     *  所以这个值实际上是「彻底卡死」的判据，不能设太短，但也不能没有。 */
    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 45_000
    const val MAX_APK = 60L * 1024 * 1024
    const val MAX_MANIFEST = 64 * 1024
    const val AUTHORITY_SUFFIX = ".updates"

    data class Info(
        val versionName: String,
        val versionCode: Long,
        val size: Long,
        val sha256: String,
        val releasedAt: String
    )

    /** 纯解析，可直接单测。任何字段不合法都拒绝整份清单，不半信半疑地往下走。 */
    fun parseManifest(text: String): Info {
        val root = JSONObject(text)
        require(root.optString("package") == PACKAGE) { "版本清单不属于本应用" }
        val code = root.optLong("versionCode", 0L)
        require(code > 0L) { "版本清单缺少有效的 versionCode" }
        val name = root.optString("versionName").trim()
        require(name.isNotEmpty()) { "版本清单缺少 versionName" }
        val sha = root.optString("sha256").trim().lowercase()
        require(sha.length == 64 && sha.all { it in "0123456789abcdef" }) { "版本清单的 sha256 不合法" }
        val size = root.optLong("size", 0L)
        require(size in 0..MAX_APK) { "版本清单的体积不合法" }
        return Info(name.take(40), code, size, sha, root.optString("releasedAt").trim().take(40))
    }

    fun isNewer(remote: Info, installedVersionCode: Long): Boolean = remote.versionCode > installedVersionCode

    fun fetchManifest(): Info = fetchManifest(MANIFEST_URL)

    fun fetchManifest(url: String): Info = parseManifest(String(read(url, MAX_MANIFEST), Charsets.UTF_8))

    /**
     * 下载到 [target]，边下边算 SHA-256，不符就删掉并报错。
     * 返回下载后的文件；进度回调可能拿不到总长度（某些重定向响应不给），此时 total 为 -1。
     */
    fun download(target: File, expectedSha256: String, onProgress: (Long, Long) -> Unit): File =
        download(APK_URL, target, expectedSha256, onProgress)

    /**
     * 与上面相同，但可以指向任意地址 —— 便于用本地 HTTP 服务把「边下边校验」的
     * 逻辑完整测穿，不依赖外网。
     */
    fun download(url: String, target: File, expectedSha256: String, onProgress: (Long, Long) -> Unit): File {
        target.parentFile?.mkdirs()
        val connection = open(url)
        try {
            val code = connection.responseCode
            require(code in 200..299) { "服务器返回 HTTP $code" }
            val declared = connection.contentLengthLong
            require(declared <= MAX_APK) { "安装包体积异常" }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        require(read <= MAX_APK) { "安装包超过大小上限" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(read, declared)
                    }
                }
            }
            val actual = digest.digest().toHex()
            require(actual == expectedSha256.lowercase()) { "下载内容与版本清单不符，已丢弃" }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        return target
    }

    /** 系统是否已允许本应用安装未知来源的包。第一次更新必须由用户去系统设置里打开。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * 安装意图。优先用 Android 推荐的 ACTION_VIEW + 包存档 MIME，
     * 失败再退回旧的 ACTION_INSTALL_PACKAGE。
     *
     * 同时带上 clipData 并逐个授权可处理该意图的包：
     * 部分系统（尤其国产 ROM）不会把 addFlags 的读权限传递给实际接管安装的组件，
     * 导致安装器打不开这个 URI —— 它会把这个情况报成「解析软件包时出现问题」。
     */
    fun installIntents(context: Context, file: File): List<Intent> {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        // 注意：setClipData 在 Java 里返回 void，不能链式调用，得单独设。
        // 带上 clipData 是为了让读权限能跟着意图传给真正接管安装的组件。
        val preferred = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(flags)
        preferred.clipData = ClipData.newRawUri("apk", uri)
        val legacy = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(flags)
        return listOf(preferred, legacy)
    }

    /** 兜底：用系统浏览器打开下载页。用户已经证明这条路能走通。 */
    fun downloadPageIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) out.append("%02x".format(byte.toInt() and 0xFF))
        return out.toString()
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/octet-stream, application/json")
        setRequestProperty("User-Agent", "LinkVault-Android")
        setRequestProperty("Connection", "close")
    }

    /** 把技术性异常翻译成能看懂、且知道下一步该干嘛的话。 */
    fun describe(error: Throwable): String {
        Log.i(TAG, "更新失败", error)
        return when (error) {
            is java.net.SocketTimeoutException -> "网络太慢或连接中断（下载被卡住超过 ${READ_TIMEOUT_MS / 1000} 秒）。可以改用下面的「用浏览器下载」。"
            is java.net.UnknownHostException -> "连不上 GitHub，当前网络可能访问不了。可以改用下面的「用浏览器下载」。"
            is java.net.ConnectException -> "连接被拒绝或网络不可用。可以改用下面的「用浏览器下载」。"
            is javax.net.ssl.SSLException -> "安全连接建立失败，网络可能被干扰。可以改用下面的「用浏览器下载」。"
            else -> error.localizedMessage ?: error.javaClass.simpleName
        }
    }

    private fun read(url: String, limit: Int): ByteArray {
        val connection = open(url)
        try {
            val code = connection.responseCode
            require(code in 200..299) { "服务器返回 HTTP $code" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (output.size() < limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
