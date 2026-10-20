package app.purse.sync

import android.content.Context
import app.purse.core.SyncCodec
import app.purse.data.Repository
import app.purse.data.SettingsStore
import app.purse.data.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Syncs app data into `<folder>/` of a shared git repository over HTTPS.
 *
 * The database is the source of truth and git is only the transport, so a sync
 * never has to resolve git conflicts:
 *  1. fetch, then hard-reset the local checkout to the remote branch
 *  2. merge the folder's JSON into the database (last-writer-wins per record)
 *  3. rewrite the folder from the database, commit, push
 *  4. if the push races with another device, start again from 1
 * Other folders in the repository (other apps) are never touched.
 */
class GitSync(
    private val context: Context,
    private val repo: Repository,
    private val settings: SettingsStore,
) {
    private val mutex = Mutex()
    private val workDir = File(context.filesDir, "git-sync")

    suspend fun sync(): String = mutex.withLock {
        val cfg = settings.current().sync
        val status = try {
            require(cfg.configured) { "Sync is not configured" }
            withContext(Dispatchers.IO) { run(cfg) }
        } catch (e: Exception) {
            "Failed: " + (e.message ?: e.javaClass.simpleName)
        }
        settings.update { it.copy(sync = it.sync.copy(lastSyncAt = System.currentTimeMillis(), lastStatus = status)) }
        status
    }

    /** Removes the local checkout, e.g. after changing the repository URL. */
    suspend fun reset() = mutex.withLock {
        withContext(Dispatchers.IO) { workDir.deleteRecursively() }
    }

    private suspend fun run(cfg: SyncConfig): String {
        installSystemReader()
        val creds = UsernamePasswordCredentialsProvider(cfg.username.ifBlank { "git" }, cfg.token)
        val branch = cfg.branch.ifBlank { "main" }
        val folder = sanitizeFolder(cfg.folder)
        val git = open(cfg.repoUrl.trim(), branch)
        git.use {
            repeat(3) { attempt ->
                git.fetch()
                    .setRemote("origin")
                    .setCredentialsProvider(creds)
                    .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                    .setRemoveDeletedRefs(true)
                    .call()

                val repository = git.repository
                val remoteId = repository.resolve("refs/remotes/origin/$branch")
                if (remoteId != null) {
                    val update = repository.updateRef("refs/heads/$branch")
                    update.setNewObjectId(remoteId)
                    update.isForceUpdate = true
                    update.forceUpdate()
                }
                repository.updateRef(Constants.HEAD).link("refs/heads/$branch")
                if (remoteId != null) {
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef(remoteId.name()).call()
                }
                runCatching { git.clean().setCleanDirectories(true).setForce(true).call() }

                // Merge remote → local.
                val dir = File(workDir, folder)
                val files = dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".json") }
                    .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
                val pulled = if (files.isEmpty()) 0 else repo.applyIncoming(SyncCodec.decode(files))

                // Local → folder.
                dir.deleteRecursively()
                SyncCodec.encode(repo.snapshot()).forEach { (path, content) ->
                    val f = File(dir, path)
                    f.parentFile?.mkdirs()
                    f.writeText(content)
                }
                git.add().addFilepattern(folder).call()
                git.add().setUpdate(true).addFilepattern(folder).call()
                if (git.status().addPath(folder).call().isClean) {
                    return "Up to date" + if (pulled > 0) " · $pulled received" else ""
                }

                val who = PersonIdent(
                    cfg.authorName.ifBlank { folder },
                    cfg.authorEmail.ifBlank { "purse@localhost" },
                )
                git.commit()
                    .setMessage("$folder: sync from ${android.os.Build.MODEL} at ${stamp()}")
                    .setAuthor(who)
                    .setCommitter(who)
                    .setSign(false)
                    .call()

                val updates = git.push()
                    .setRemote("origin")
                    .setCredentialsProvider(creds)
                    .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    .call()
                    .flatMap { it.remoteUpdates }
                val raced = updates.any {
                    it.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                        it.status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED
                }
                val failed = updates.firstOrNull {
                    it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
                }
                when {
                    failed == null -> return "Synced" + if (pulled > 0) " · $pulled received" else ""
                    raced && attempt < 2 -> Unit // another device pushed first; go again
                    else -> error("Push ${failed.status}: ${failed.message ?: ""}")
                }
            }
        }
        error("Gave up after repeated push races")
    }

    private fun open(url: String, branch: String): Git {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Use an https:// repository URL" }
        val git = if (File(workDir, ".git").isDirectory) {
            Git.open(workDir)
        } else {
            workDir.deleteRecursively()
            workDir.mkdirs()
            Git.init().setDirectory(workDir).setInitialBranch(branch).call()
        }
        val config = git.repository.config
        if (config.getString("remote", "origin", "url") != url) {
            config.setString("remote", "origin", "url", url)
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            config.save()
        }
        return git
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())

    private fun installSystemReader() {
        if (SystemReader.getInstance() is AndroidSystemReader) return
        val home = File(context.filesDir, "jgit").apply { mkdirs() }
        System.setProperty("user.home", home.absolutePath)
        SystemReader.setInstance(AndroidSystemReader(SystemReader.getInstance(), home))
    }

    companion object {
        fun sanitizeFolder(name: String): String =
            name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").trim('.', ' ').ifBlank { "purse" }
    }
}

/**
 * Android has no system or global git config; point JGit at private files instead
 * of letting it probe for a `git` executable.
 */
private class AndroidSystemReader(private val base: SystemReader, private val home: File) : SystemReader() {
    override fun getHostname(): String = "android"
    override fun getenv(variable: String?): String? = base.getenv(variable)
    override fun getProperty(key: String?): String? = base.getProperty(key)
    override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, File(home, "gitconfig"), fs)
    override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig = EmptyConfig(parent, fs)
    override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig = FileBasedConfig(parent, File(home, "jgitconfig"), fs)
    override fun getCurrentTime(): Long = System.currentTimeMillis()
    override fun getTimezone(`when`: Long): Int = TimeZone.getDefault().getOffset(`when`) / 60_000
}

private class EmptyConfig(parent: Config?, fs: FS?) : FileBasedConfig(parent, null, fs) {
    override fun load() = Unit
    override fun isOutdated(): Boolean = false
}
