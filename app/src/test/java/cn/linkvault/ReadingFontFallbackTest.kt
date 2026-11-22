package cn.linkvault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「藏」用包里的朱雀字形。「々」朱雀没有，Android 10 及以上应画成系统字体里的那个字。
 * Robolectric 的 Paint.hasGlyph 对这份字体不可靠，所以直接比像素。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingFontFallbackTest {
    @Test fun missingCharacterUsesTheSystemGlyph() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val loaded = ReadingTypefaceLoader.loadBlocking(context, ReadingAndroidFont(FontWeight.Normal))
        assertNotNull(loaded)
        val reading = draw(loaded)
        val system = draw(Typeface.DEFAULT)
        assertFalse(same(reading, system, 0, 120))
        assertTrue(same(reading, system, 120, 240))
        reading.recycle()
        system.recycle()
    }

    private fun draw(typeface: Typeface): Bitmap {
        val bitmap = Bitmap.createBitmap(240, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            this.typeface = typeface
        }
        canvas.drawText("藏々", 8f, 76f, paint)
        return bitmap
    }

    private fun same(left: Bitmap, right: Bitmap, x0: Int, x1: Int): Boolean {
        for (y in 0 until left.height) {
            for (x in x0 until x1) {
                if (left.getPixel(x, y) != right.getPixel(x, y)) return false
            }
        }
        return true
    }
}
