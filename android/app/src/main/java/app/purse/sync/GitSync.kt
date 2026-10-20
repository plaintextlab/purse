package app.purse.sync

import android.content.Context
import app.purse.core.GitFolderSync
import app.purse.core.Snapshot
import app.purse.core.SyncStore
import app.purse.data.Repository
import app.purse.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Android side of git sync: settings, locking and status. The engine is [GitFolderSync]. */
class GitSync(
    context: Context,
    private val repo: Repository,
    private val settings: SettingsStore,
) {
    private val mutex = Mutex()
    private val engine = GitFolderSync(File(context.filesDir, "git-sync"), File(context.filesDir, "jgit"))

    private val store = object : SyncStore {
        override suspend fun snapshot(): Snapshot = repo.snapshot()
        override suspend fun applyIncoming(remote: Snapshot): Int = repo.applyIncoming(remote)
    }

    suspend fun sync(): String = mutex.withLock {
        val cfg = settings.current().sync
        val status = try {
            require(cfg.configured) { "Sync is not configured" }
            require(cfg.repoUrl.trim().startsWith("https://")) { "Use an https:// repository URL" }
            val options = GitFolderSync.Options(
                url = cfg.repoUrl,
                branch = cfg.branch,
                folder = cfg.folder,
                username = cfg.username,
                token = cfg.token,
                authorName = cfg.authorName,
                authorEmail = cfg.authorEmail,
                device = android.os.Build.MODEL ?: "android",
            )
            withContext(Dispatchers.IO) { engine.sync(options, store) }
        } catch (e: Exception) {
            "Failed: " + (e.message ?: e.javaClass.simpleName)
        }
        settings.update { it.copy(sync = it.sync.copy(lastSyncAt = System.currentTimeMillis(), lastStatus = status)) }
        status
    }

    /** Drops the local checkout, e.g. after switching repositories. */
    suspend fun reset() = mutex.withLock {
        withContext(Dispatchers.IO) { engine.reset() }
    }

    companion object {
        fun sanitizeFolder(name: String) = GitFolderSync.sanitizeFolder(name)
    }
}
