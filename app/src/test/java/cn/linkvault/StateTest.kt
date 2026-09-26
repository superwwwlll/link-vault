package cn.linkvault
import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StateTest {
    @Test fun restoreDraftAndKeepIncomingSharePending() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val handle = SavedStateHandle()
            val vm = VaultViewModel(app, handle)
            vm.edit(Draft(url = "https://example.com", title = "未完成", notes = "保留", tags = "标签"))
            vm.receive("https://x.com/a/status/1?s=20")
            assertEquals("未完成", vm.draft!!.title)
            assertNotNull(vm.pending)
            val restored = VaultViewModel(app, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
            assertEquals(vm.draft, restored.draft)
            assertEquals(vm.pending, restored.pending)
            restored.cancel(); restored.importPending()
            assertEquals("https://x.com/a/status/1?s=20", restored.draft!!.url)
            restored.extractText("https://a.example https://b.example")
            assertEquals("https://a.example", restored.draft!!.url)
            assertTrue(restored.notice!!.contains("仅取第一个"))
            restored.edit(restored.draft!!.copy(url = "javascript:alert(1)")); restored.save()
            assertNotNull(restored.error); assertNotNull(restored.draft)

            restored.setSort(2)
            assertEquals(2, restored.sortOrder)
        } finally { Dispatchers.resetMain() }
    }
}
