package app.purse.core

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
import java.util.TimeZone

/** Where the app's records come from and go to during a sync. */
interface SyncStore {
    suspend fun snapshot(): Snapshot

    /** Applies newer remote records; returns how many changed. */
    suspend fun applyIncoming(remote: Snapshot): Int
}

/**
 * Syncs a [SyncStore] into `<folder>/` of a shared git repository.
 *
 * The store is the source of truth and git is only the transport, so a sync
 * never has to resolve git conflicts:
 *  1. fetch, then hard-reset the local checkout to the remote branch
 *  2. merge the folder's JSON into the store (last-writer-wins per record)
 *  3. rewrite the folder from the store, commit, push
 *  4. if another device pushed in between, start again from 1
 * Other folders in the repository (other apps) are never touched.
 *
 * Blocking; call from an IO thread.
 */
class GitFolderSync(private val workDir: File, private val configHome: File) {

    data class Options(
        val url: String,
        val branch: String,
        val folder: String,
        val username: String = "",
        val token: String = "",
        val authorName: String = "",
        val authorEmail: String = "",
        val device: String = "device",
    )

    suspend fun sync(o: Options, store: SyncStore): String {
        installSystemReader(configHome)
        val creds = UsernamePasswordCredentialsProvider(o.username.ifBlank { "git" }, o.token)
        val branch = o.branch.ifBlank { "main" }
        val folder = sanitizeFolder(o.folder)
        open(o.url.trim(), branch).use { git ->
            repeat(MAX_ATTEMPTS) { attempt ->
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
                git.clean().setCleanDirectories(true).setForce(true).call()

                val dir = File(workDir, folder)
                val files = if (dir.isDirectory) {
                    dir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".json") }
                        .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }
                } else {
                    emptyMap()
                }
                val pulled = if (files.isEmpty()) 0 else store.applyIncoming(SyncCodec.decode(files))
                val received = if (pulled > 0) " · $pulled received" else ""

                dir.deleteRecursively()
                SyncCodec.encode(store.snapshot()).forEach { (path, content) ->
                    val f = File(dir, path)
                    f.parentFile?.mkdirs()
                    f.writeText(content)
                }
                git.add().addFilepattern(folder).call()
                git.add().setUpdate(true).addFilepattern(folder).call()
                if (git.status().addPath(folder).call().isClean) return "Up to date$received"

                val who = PersonIdent(o.authorName.ifBlank { folder }, o.authorEmail.ifBlank { "$folder@localhost" })
                git.commit()
                    .setMessage("$folder: sync from ${o.device}")
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
                val failed = updates.firstOrNull {
                    it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
                } ?: return "Synced$received"
                val raced = failed.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
                    failed.status == RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED
                if (!raced || attempt == MAX_ATTEMPTS - 1) {
                    error("Push ${failed.status}" + (failed.message?.let { ": $it" } ?: ""))
                }
                // Another device pushed first; go round again.
            }
        }
        error("unreachable")
    }

    /** Removes the local checkout, e.g. after switching repositories. */
    fun reset() {
        workDir.deleteRecursively()
    }

    private fun open(url: String, branch: String): Git {
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

    companion object {
        private const val MAX_ATTEMPTS = 3

        fun sanitizeFolder(name: String): String =
            name.trim().replace(Regex("""[\\/:*?"<>|]"""), "_").trim('.', ' ').ifBlank { "purse" }

        /**
         * There is no system or global git config on Android; point JGit at private
         * files instead of letting it probe for a `git` executable.
         */
        @Synchronized
        fun installSystemReader(home: File) {
            if (SystemReader.getInstance() is IsolatedSystemReader) return
            home.mkdirs()
            SystemReader.setInstance(IsolatedSystemReader(SystemReader.getInstance(), home))
        }
    }
}

private class IsolatedSystemReader(private val base: SystemReader, private val home: File) : SystemReader() {
    override fun getHostname(): String = "purse"
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
