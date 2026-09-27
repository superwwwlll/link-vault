package cn.linkvault

import android.app.Application
import android.net.Uri
import org.robolectric.Shadows
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.IOException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupStateTest {
    @Test fun restoreImportPreviewWithoutMutatingDatabase() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val file = File(app.cacheDir, "pending-import.json")
            val secure = File(app.cacheDir, "pending-import.lvault").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val sample = Bookmark(url = "https://example.com/import-state", canonical = "https://example.com/import-state", title = "预览恢复")
            file.writeBytes(Backup.encode(listOf(sample)))
            val handle = SavedStateHandle(mapOf("importPreview" to true))
            val vm = VaultViewModel(app, handle); store.put("preview", vm)
            runBlocking { withTimeout(10000) { while (vm.busy) delay(10) } }
            assertEquals("预览恢复", vm.preview!!.items.single().title)
            assertNull(runBlocking { VaultDb.get(app).bookmarks().byKey(sample.canonical) })
            vm.cancelImport()
            // 临时文件在 IO 线程上删，主线程不等它
            runBlocking { withTimeout(10000) { while (file.exists() || secure.exists()) delay(10) } }
            assertNull(vm.preview); assertFalse(file.exists()); assertEquals(false, handle.get<Boolean>("importPreview"))
            assertFalse("加密预览的临时底稿也不能留在 cache 里", secure.exists())
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun missingPreviewFailsClosedAndKeepsDraft() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            File(app.cacheDir, "pending-import.json").delete()
            val vm = VaultViewModel(app, SavedStateHandle(mapOf("importPreview" to true))); store.put("missing", vm)
            vm.edit(Draft(title = "草稿不丢失", url = "https://example.com"))
            runBlocking { withTimeout(10000) { while (vm.busy) delay(10) } }
            assertNull(vm.preview); assertNotNull(vm.error); assertEquals("草稿不丢失", vm.draft!!.title)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun contentResolverExportAndImportRoundTrip() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val dao = VaultDb.get(app).bookmarks()
            val url = "https://example.com/saf-roundtrip"
            runBlocking { if (dao.byKey(url) == null) dao.insert(Bookmark(url = url, canonical = url, title = "文件备份")) }
            val vm = VaultViewModel(app, SavedStateHandle()); store.put("saf", vm)
            val uri = Uri.parse("content://cn.linkvault.test/roundtrip.json")
            val output = ByteArrayOutputStream()
            Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, output)
            vm.export(uri)
            runBlocking { withTimeout(10000) { while (vm.busy) delay(10) } }
            assertTrue(vm.message!!.startsWith("已导出"))
            val data = output.toByteArray(); assertTrue(Backup.decode(data).any { it.title == "文件备份" })
            vm.clearMessage()
            Shadows.shadowOf(app.contentResolver).registerInputStream(uri, data.inputStream())
            vm.prepareImport(uri)
            runBlocking { withTimeout(10000) { while (vm.busy) delay(10) } }
            assertEquals(0, vm.preview!!.added)
            val before = runBlocking { dao.all() }
            vm.confirmImport()
            runBlocking { withTimeout(10000) { while (vm.busy) delay(10) } }
            assertEquals(before, runBlocking { dao.all() })
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun writeFailureIsVisibleAndPreservesDraft() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        try {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = VaultViewModel(app, SavedStateHandle()); store.put("error", vm)
            vm.edit(Draft(url = "https://example.com", notes = "未完成备注"))
            val uri = Uri.parse("content://cn.linkvault.test/failing.json")
            Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, object : OutputStream() { override fun write(b: Int) { throw IOException("磁盘空间不足") } })
            vm.export(uri)
            runBlocking { withTimeout(10000) { while (vm.busy) delay(10) } }
            assertTrue(vm.error!!.contains("导出失败")); assertTrue(vm.error!!.contains("磁盘空间不足"))
            assertEquals("未完成备注", vm.draft!!.notes)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

}
