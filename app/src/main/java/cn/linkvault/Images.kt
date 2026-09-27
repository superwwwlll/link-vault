package cn.linkvault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 封面图与正文图片的加载：内存缓存 + 手工解码，不引第三方库。
 *
 * 缓存只活在内存里。数据库和快照文件里存的都只有 URL，所以断网或重启后图片会重新下载，
 * 而文字照样读得懂 —— 这是「离线可读」这个说法的边界，不是漏了磁盘缓存。
 */
internal object Images {
    /** 解码后允许保留的像素数，超过的图按 2 的幂次缩小后再进内存。 */
    const val MAX_PIXELS = 2_800_000L

    /**
     * 取字节的接缝。
     *
     * 截图用例要把它换成固定字节：验收通道只有一台没有 adb 的机器，
     * 让截图依赖真实网络等于没有截图。
     */
    @Volatile
    var fetcher: (String) -> ByteArray = { Net.fetchImage(it) }

    /** 只装解码后的位图，按位图字节数计重；上限取堆的 1/8，宁可重下也不吃内存。 */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    /**
     * 纯解码：不碰网络，可以直接单测。
     *
     * 畸形或超大的图一律返回 null，由界面当作「没有图」。
     */
    fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.size < 24) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        }
    }

    /** BitmapFactory 只支持 2 的幂，所以逐档翻倍，直到这一档的像素数落进上限。 */
    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (sample < 32 && (width / sample).toLong() * (height / sample) > MAX_PIXELS) sample *= 2
        return sample
    }

    /** 取字节 → 解码 → 进缓存；任何一步失败都只当作「这张图没有」，不弹提示。 */
    suspend fun load(url: String): Bitmap? {
        cached(url)?.let { return it }
        if (!Links.valid(url) || !url.startsWith("https://", true)) return null
        val bytes = withContext(Dispatchers.IO) {
            runCatching { fetcher(url) }.getOrNull()
        } ?: return null
        coroutineContext.ensureActive()
        val bitmap = decode(bytes) ?: return null
        cache.put(url, bitmap)
        return bitmap
    }

    /** 清空缓存。退出登录式的清理用不到，留给付费测试和「省流量」开关。 */
    fun clear() = cache.evictAll()
}

/**
 * 异步取一张图，取到之前返回 null。
 *
 * 调用方在拿到 null 时应当什么都不画：加载中的占位块会在页面上闪一下灰，
 * 而失败时那块灰会一直留着，比没有图更难看。
 */
@Composable
internal fun rememberLoadedImage(url: String): ImageBitmap? {
    if (url.isBlank()) return null
    var bitmap by remember(url) { mutableStateOf(Images.cached(url)?.asImageBitmap()) }
    LaunchedEffect(url) {
        if (bitmap == null) Images.load(url)?.let { bitmap = it.asImageBitmap() }
    }
    return bitmap
}
