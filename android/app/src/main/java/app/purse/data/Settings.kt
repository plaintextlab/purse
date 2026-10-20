package app.purse.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class DarkMode { SYSTEM, LIGHT, DARK }

data class SyncConfig(
    val repoUrl: String = "",
    val branch: String = "main",
    val username: String = "",
    val token: String = "",
    /** Folder inside the shared sync repo. Defaults to the app name when first configured. */
    val folder: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val auto: Boolean = false,
    val lastSyncAt: Long = 0,
    val lastStatus: String = "",
) {
    val configured: Boolean get() = repoUrl.isNotBlank() && folder.isNotBlank()
}

data class Settings(
    val theme: String = "gruvbox",
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val currency: String = "₹",
    val indianGrouping: Boolean = true,
    /** Display name used in-app, for pinned shortcuts and as the default sync folder. */
    val appName: String = "Purse",
    val smsEnabled: Boolean = false,
    val smsNotify: Boolean = true,
    val monthlyBudget: Long = 0,
    val defaultAccountId: String? = null,
    val sync: SyncConfig = SyncConfig(),
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    private object K {
        val theme = stringPreferencesKey("theme")
        val dark = stringPreferencesKey("dark_mode")
        val currency = stringPreferencesKey("currency")
        val indian = booleanPreferencesKey("indian_grouping")
        val appName = stringPreferencesKey("app_name")
        val sms = booleanPreferencesKey("sms_enabled")
        val smsNotify = booleanPreferencesKey("sms_notify")
        val budget = longPreferencesKey("monthly_budget")
        val defaultAccount = stringPreferencesKey("default_account")
        val repo = stringPreferencesKey("sync_repo")
        val branch = stringPreferencesKey("sync_branch")
        val user = stringPreferencesKey("sync_user")
        val token = stringPreferencesKey("sync_token")
        val folder = stringPreferencesKey("sync_folder")
        val authorName = stringPreferencesKey("sync_author_name")
        val authorEmail = stringPreferencesKey("sync_author_email")
        val auto = booleanPreferencesKey("sync_auto")
        val lastAt = longPreferencesKey("sync_last_at")
        val lastStatus = stringPreferencesKey("sync_last_status")
    }

    val flow: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.theme] = s.theme
            prefs[K.dark] = s.darkMode.name
            prefs[K.currency] = s.currency
            prefs[K.indian] = s.indianGrouping
            prefs[K.appName] = s.appName
            prefs[K.sms] = s.smsEnabled
            prefs[K.smsNotify] = s.smsNotify
            prefs[K.budget] = s.monthlyBudget
            if (s.defaultAccountId != null) prefs[K.defaultAccount] = s.defaultAccountId else prefs.remove(K.defaultAccount)
            prefs[K.repo] = s.sync.repoUrl
            prefs[K.branch] = s.sync.branch
            prefs[K.user] = s.sync.username
            prefs[K.token] = s.sync.token
            prefs[K.folder] = s.sync.folder
            prefs[K.authorName] = s.sync.authorName
            prefs[K.authorEmail] = s.sync.authorEmail
            prefs[K.auto] = s.sync.auto
            prefs[K.lastAt] = s.sync.lastSyncAt
            prefs[K.lastStatus] = s.sync.lastStatus
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        val ds = d.sync
        return Settings(
            theme = this[K.theme] ?: d.theme,
            darkMode = enumOr(this[K.dark], d.darkMode),
            currency = this[K.currency] ?: d.currency,
            indianGrouping = this[K.indian] ?: d.indianGrouping,
            appName = this[K.appName] ?: d.appName,
            smsEnabled = this[K.sms] ?: d.smsEnabled,
            smsNotify = this[K.smsNotify] ?: d.smsNotify,
            monthlyBudget = this[K.budget] ?: d.monthlyBudget,
            defaultAccountId = this[K.defaultAccount],
            sync = SyncConfig(
                repoUrl = this[K.repo] ?: ds.repoUrl,
                branch = this[K.branch] ?: ds.branch,
                username = this[K.user] ?: ds.username,
                token = this[K.token] ?: ds.token,
                folder = this[K.folder] ?: ds.folder,
                authorName = this[K.authorName] ?: ds.authorName,
                authorEmail = this[K.authorEmail] ?: ds.authorEmail,
                auto = this[K.auto] ?: ds.auto,
                lastSyncAt = this[K.lastAt] ?: ds.lastSyncAt,
                lastStatus = this[K.lastStatus] ?: ds.lastStatus,
            ),
        )
    }
}
