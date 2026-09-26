package cn.linkvault

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityTest {
    private fun share(url: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    @Test fun coldWarmShareAndRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, share("Https://example.com/first")).setup()
        try {
            var vm = ViewModelProvider(controller.get())[VaultViewModel::class.java]
            assertEquals("Https://example.com/first", vm.draft!!.url)
            vm.edit(vm.draft!!.copy(title = "旋转不丢失", notes = "备注", tags = "一,二"))
            controller.recreate()
            vm = ViewModelProvider(controller.get())[VaultViewModel::class.java]
            assertEquals("旋转不丢失", vm.draft!!.title)
            controller.newIntent(share("https://example.com/second"))
            assertEquals("Https://example.com/first", vm.draft!!.url)
            assertEquals("https://example.com/second", vm.pending)
            vm.cancel(); vm.importPending()
            assertEquals("https://example.com/second", vm.draft!!.url)
            vm.cancel()
            controller.newIntent(share("https://example.com/third"))
            assertEquals("https://example.com/third", vm.draft!!.url)
        } finally { controller.pause().stop().destroy() }
    }
}
