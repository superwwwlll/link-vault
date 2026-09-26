package cn.linkvault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureTest {

    @Test fun titleComesFromTheSystemShare() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/a")
            .putExtra(Intent.EXTRA_SUBJECT, "页面标题")
        val shared = Capture.fromShare(intent)!!
        assertEquals("https://example.com/a", shared.text)
        assertEquals("页面标题", shared.title)
    }

    @Test fun titleExtraIsAcceptedToo() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/a")
            .putExtra("android.intent.extra.TITLE", "另一种标题")
        assertEquals("另一种标题", Capture.fromShare(intent)!!.title)
    }

    @Test fun htmlExtraIsKeptSoTheTitleCanBeReadOffline() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/html")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/a")
            .putExtra("android.intent.extra.HTML_TEXT", """<a href="https://example.com/a">从 HTML 拿到的标题</a>""")
        val shared = Capture.fromShare(intent)!!
        assertEquals("从 HTML 拿到的标题", Links.titleFromHtml(shared.html, shared.text))
    }

    @Test fun htmlOnlyShareStillExtractsTheAnchorUrl() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/html")
            .putExtra("android.intent.extra.HTML_TEXT", """<a href="https://example.com/a?utm_source=share&amp;id=7">只有 HTML 的分享</a>""")
        val shared = Capture.fromShare(intent)!!
        assertEquals(listOf("https://example.com/a?utm_source=share&id=7"), Links.extract(shared.text, shared.html))
        assertEquals("只有 HTML 的分享", Links.titleFromHtml(shared.html, Links.extract(shared.text, shared.html).first()))
    }

    @Test fun multipleSharedLinksAreMerged() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
            .putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf("https://a.example", "https://b.example"))
        val shared = Capture.fromShare(intent)!!
        assertEquals(listOf("https://a.example", "https://b.example"), Links.extract(shared.text))
    }

    @Test fun selectedTextIsTreatedAsAShare() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, "看一下 https://example.com/selected")
        assertEquals(listOf("https://example.com/selected"), Links.extract(Capture.fromShare(intent)!!.text))
    }

    @Test fun nonTextSharesAreIgnored() {
        assertNull(Capture.fromShare(Intent(Intent.ACTION_SEND).setType("image/png")))
        assertNull(Capture.fromShare(Intent(Intent.ACTION_VIEW).setData(Uri.parse("https://example.com"))))
        assertNull(Capture.fromShare(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(Capture.fromShare(null))
    }

    @Test fun clipboardCarriesBothPlainTextAndHtml() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newHtmlText("链接", "https://example.com/x", """<a href="https://example.com/x">剪贴板标题</a>"""))
        val shared = Capture.clipboard(context)
        assertEquals("https://example.com/x", shared.text)
        assertEquals("剪贴板标题", Links.titleFromHtml(shared.html, shared.text))
    }

    @Test fun emptyClipboardIsHarmless() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
        val shared = Capture.clipboard(context)
        assertTrue(shared.text.isBlank())
        assertTrue(shared.html.isBlank())
    }
}
