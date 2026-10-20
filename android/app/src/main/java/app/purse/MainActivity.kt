package app.purse

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.purse.ui.LocalContainer
import app.purse.ui.PurseRoot
import app.purse.ui.theme.PurseTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Deep link requested by a shortcut, widget or notification; consumed by [PurseRoot]. */
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingRoute.value = routeFor(intent)
        val container = application.container
        setContent {
            val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            CompositionLocalProvider(LocalContainer provides container) {
                PurseTheme(s) {
                    PurseRoot(settings = s, pendingRoute = pendingRoute)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFor(intent)?.let { pendingRoute.value = it }
    }

    companion object {
        const val ACTION_ADD = "app.purse.ADD"
        const val EXTRA_EDIT_TXN = "edit_txn"

        private fun routeFor(intent: Intent?): String? = when {
            intent == null -> null
            intent.action == ACTION_ADD -> "add"
            intent.getStringExtra(EXTRA_EDIT_TXN) != null -> "add?id=${intent.getStringExtra(EXTRA_EDIT_TXN)}"
            else -> null
        }
    }
}
