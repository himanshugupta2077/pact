package app.pact

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import app.pact.ui.block.BlockScreen
import app.pact.ui.theme.PactTheme

class BlockActivity : ComponentActivity() {

    private val pkgFlow = MutableStateFlow<String?>(null)
    private val requestFlow = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        applyIntent(intent)
        setContent {
            PactTheme(darkTheme = true) {
                val pkg by pkgFlow.collectAsState()
                val request by requestFlow.collectAsState()
                val name = pkg
                if (name.isNullOrBlank()) {
                    goHome()
                } else {
                    BlockScreen(
                        packageName = name,
                        onGoHome = { goHome() },
                        startOnRequest = request,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent) {
        pkgFlow.value = intent.getStringExtra(EXTRA_PACKAGE)
        requestFlow.value = intent.getBooleanExtra(EXTRA_REQUEST, false)
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_REQUEST = "request"
    }
}
