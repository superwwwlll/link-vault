package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagesTest {
    private val realFetcher = Images.fetcher

    private fun png(width: Int, height: Int): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(20, 90, 200))
        val out = ByteArrayOutputStream()
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out))
        source.recycle()
        return out.toByteArray()
    }

    @Test fun decodesSmallImagesAtFullSize() {
        val bitmap = Images.decode(png(800, 500))
        assertNotNull(bitmap)
        assertEquals(800, bitmap!!.width)
        assertEquals(500, bitmap.height)
    }

    @Test fun downsamplesHugeImagesInsteadOfKeepingThemInMemory() {
        val bitmap = Images.decode(png(2400, 2400))
        assertNotNull(bitmap)
        assertTrue("应当被缩小，实测 ${bitmap!!.width}", bitmap.width <= 1300)
        // 只能等比缩小，不能被压扁
        assertTrue(bitmap.width == bitmap.height)
    }

    @Test fun unreadableBytesAreTreatedAsNoImage() {
        assertNull(Images.decode(ByteArray(0)))
        assertNull(Images.decode("这根本不是一张图片".toByteArray()))
        assertNull(Images.decode(ByteArray(64)))
    }

    @Test fun anythingButHttpsNeverReachesTheNetwork() = runBlocking {
        var calls = 0
        Images.fetcher = { calls++; png(60, 40) }
        try {
            listOf("", "http://example.com/a.png", "javascript:alert(1)", "file:///sdcard/a.png").forEach {
                assertNull(it, Images.load(it))
            }
            assertEquals("非法地址必须在发请求之前就拦掉", 0, calls)
        } finally { Images.fetcher = realFetcher }
    }

    @Test fun aFailedFetchIsNoImageRatherThanAnError() = runBlocking {
        Images.clear()
        Images.fetcher = { throw IOException("网络已断开") }
        try {
            assertNull(Images.load("https://cdn.example.com/a.png"))
        } finally { Images.fetcher = realFetcher }
    }

    @Test fun theSecondRequestForTheSameUrlComesFromTheCache() = runBlocking {
        Images.clear()
        val url = "https://cdn.example.com/cover.png"
        var calls = 0
        Images.fetcher = { calls++; png(60, 40) }
        try {
            assertNotNull(Images.load(url))
            assertNotNull(Images.load(url))
            assertEquals(1, calls)
        } finally { Images.fetcher = realFetcher }
    }
}
