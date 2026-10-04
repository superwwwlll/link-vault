package cn.linkvault

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AiSettingsTest {
    private lateinit var app: Application
    private lateinit var vm: VaultViewModel
    private val store = ViewModelStore()
    private val realTransport = Analysis.transport
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("settings", 0).edit().clear().commit()
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return VaultViewModel(app, SavedStateHandle()) as T
            }
        })[VaultViewModel::class.java]
        Analysis.transport = { _, _, _ -> """{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]}""" }
    }
    @After fun teardown() {
        val job = vm.viewModelScope.coroutineContext[Job]!!
        store.clear()
        runBlocking { withTimeout(10_000) { job.join() } }
        Analysis.transport = realTransport; Dispatchers.resetMain()
    }
    private fun waitUntil(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!condition()) delay(5) } }
    @Test fun defaultsVerificationAndProviderKeyIsolationWork() {
        assertEquals(AiProviders.default.endpoint, vm.aiEndpoint)
        assertEquals(AiProviders.default.model, vm.aiModel)
        assertFalse(vm.aiVerified); assertFalse(vm.analysis.auto)
        vm.aiKey("test-key"); vm.testAiConnection()
        waitUntil { !vm.aiTesting }
        assertTrue(vm.aiVerified)
        assertFalse("总结不要求开启翻译", vm.aiEnabled)
        vm.aiModel("other-model")
        assertFalse(vm.aiVerified)
        vm.selectAiProvider(AiProviders.presets.first { it.id == "zhipu" })
        assertEquals("", vm.aiKey); assertFalse(vm.aiVerified)
        assertEquals("glm-5.3", vm.aiModel)
    }
    @Test fun noteSaveHooksTriggerAutomaticSummaryAndDeletionClearsIt() {
        val db = VaultDb.get(app)
        runBlocking { db.notes().clear(); db.vault().clear() }
        vm.aiKey("test-key"); vm.testAiConnection(); waitUntil { !vm.aiTesting }
        vm.analysis.auto(true)
        vm.notes.create("新的工作材料"); vm.notes.save()
        waitUntil { !vm.notes.busy && vm.analysis.records.isNotEmpty() }
        val row = runBlocking { db.notes().all().single() }
        assertTrue(vm.analysis.records.containsKey("n-${row.id}"))
        vm.notes.delete(row.id); waitUntil { !vm.notes.busy }
        assertNull(AnalysisStore.read(app, "n-${row.id}"))
        assertFalse(vm.analysis.records.containsKey("n-${row.id}"))
    }
}
