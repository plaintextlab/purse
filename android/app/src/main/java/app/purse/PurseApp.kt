package app.purse

import android.app.Application
import android.content.Context
import app.purse.data.PurseDatabase
import app.purse.data.Repository
import app.purse.data.SettingsStore
import app.purse.sms.Notifications
import app.purse.sms.SmsProcessor
import app.purse.sync.GitSync
import app.purse.sync.SyncScheduler
import app.purse.widget.PurseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PurseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.scope.launch {
            container.repo.seed()
            SyncScheduler.schedulePeriodic(this@PurseApp, container.settings.current().sync)
        }
    }
}

/** Manual dependency container; one per process. */
class AppContainer(private val context: Context) {
    /** Outlives screens so writes finish even if the user navigates away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    private val db = PurseDatabase.build(context)

    val repo = Repository(
        db = db,
        scope = scope,
        onLocalChange = {
            refreshWidgets()
            scope.launch {
                val sync = settings.current().sync
                if (sync.auto && sync.configured) SyncScheduler.afterLocalChange(context)
            }
        },
        onRemoteChange = { refreshWidgets() },
    )
    val sms = SmsProcessor(repo, settings)
    val gitSync = GitSync(context, repo, settings)

    fun refreshWidgets() {
        scope.launch { PurseWidget.refresh(context) }
    }
}

val Context.container: AppContainer get() = (applicationContext as PurseApp).container
