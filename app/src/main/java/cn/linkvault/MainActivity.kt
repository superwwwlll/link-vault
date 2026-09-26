package cn.linkvault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    private val vm: VaultViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent {
            val dark = when (vm.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            VaultTheme(dark) { VaultScreen(vm) }
        }
    }
    override fun onResume() {
        super.onResume()
        vm.checkClipboard(this)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent?) {
        val shared = Capture.fromShare(intent) ?: return
        if (shared.text.isBlank() && shared.html.isBlank()) vm.fail("分享中没有可识别的链接文字")
        else vm.receive(shared)
        intent?.removeExtra(Intent.EXTRA_TEXT)
        intent?.removeExtra("android.intent.extra.HTML_TEXT")
    }
}
