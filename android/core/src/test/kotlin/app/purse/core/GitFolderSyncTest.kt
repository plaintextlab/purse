package app.purse.core

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-memory store standing in for the app database. */
private class MemStore(var data: Snapshot = Snapshot()) : SyncStore {
    override suspend fun snapshot() = data

    override suspend fun applyIncoming(remote: Snapshot): Int {
        val acc = SyncMerge.incoming(data.accounts, remote.accounts)
        val txn = SyncMerge.incoming(data.transactions, remote.transactions)
        data = data.copy(
            accounts = (data.accounts.associateBy { it.uuid } + acc.associateBy { it.uuid }).values.toList(),
            transactions = (data.transactions.associateBy { it.uuid } + txn.associateBy { it.uuid }).values.toList(),
        )
        return acc.size + txn.size
    }

    fun add(t: TxnRec) {
        data = data.copy(transactions = data.transactions + t)
    }
}

class GitFolderSyncTest {
    private val root: File = Files.createTempDirectory("purse-sync").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun bare(): String {
        val dir = File(root, "remote.git")
        Git.init().setBare(true).setDirectory(dir).setInitialBranch("main").call().close()
        return dir.toURI().toString()
    }

    private fun device(name: String) = GitFolderSync(File(root, name), File(root, "$name-home"))

    private fun txn(id: String, amount: Long, updated: Long) =
        TxnRec(id, "EXPENSE", amount, "a1", timestamp = 1_790_000_000_000, updatedAt = updated)

    @Test
    fun twoDevicesConvergeAndLeaveOtherFoldersAlone() = runBlocking {
        val url = bare()

        // Another app already uses the shared repo.
        val other = File(root, "other")
        Git.cloneRepository().setURI(url).setDirectory(other).call().use { g ->
            File(other, "notes-app").mkdirs()
            File(other, "notes-app/data.txt").writeText("keep me")
            g.add().addFilepattern(".").call()
            g.commit().setMessage("other app").setAuthor("o", "o@x").setCommitter("o", "o@x").setSign(false).call()
            g.push().setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/main")).call()
        }

        val phone = MemStore(Snapshot(accounts = listOf(AccountRec("a1", "Cash", "CASH", updatedAt = 1))))
        val tablet = MemStore()
        val a = device("phone")
        val b = device("tablet")
        val opts = GitFolderSync.Options(url = url, branch = "main", folder = "Purse", device = "test")

        phone.add(txn("t1", 100, 1))
        assertEquals("Synced", a.sync(opts, phone))
        // The tablet only receives; what it would write back is identical, so no commit.
        assertEquals("Up to date · 2 received", b.sync(opts, tablet))
        assertEquals(setOf("t1"), tablet.data.transactions.map { it.uuid }.toSet())

        // Concurrent edits: both devices change things without syncing in between.
        tablet.add(txn("t2", 200, 2))
        phone.data = phone.data.copy(transactions = listOf(txn("t1", 150, 3)))
        b.sync(opts, tablet)
        a.sync(opts, phone) // must pull t2 before pushing

        b.sync(opts, tablet)
        assertEquals(phone.data.transactions.toSet(), tablet.data.transactions.toSet())
        assertEquals(150, tablet.data.transactions.first { it.uuid == "t1" }.amount)

        // Nothing new: no extra commit.
        assertTrue(a.sync(opts, phone).startsWith("Up to date"))

        val check = File(root, "check")
        Git.cloneRepository().setURI(url).setDirectory(check).call().close()
        assertEquals("keep me", File(check, "notes-app/data.txt").readText())
        assertTrue(File(check, "Purse/transactions/2026-09.json").isFile)
    }

    @Test
    fun retriesWhenAnotherDevicePushedFirst() = runBlocking {
        val url = bare()
        val opts = GitFolderSync.Options(url = url, branch = "main", folder = "Purse")
        val phone = MemStore()
        val tablet = MemStore()
        phone.add(txn("p", 1, 1))
        tablet.add(txn("t", 2, 1))

        // The phone's push lands between the tablet's fetch and push: simulate by
        // syncing the phone from inside the tablet's snapshot step.
        val racing = object : SyncStore by tablet {
            var raced = false
            override suspend fun snapshot(): Snapshot {
                if (!raced) {
                    raced = true
                    device("phone").sync(opts, phone)
                }
                return tablet.snapshot()
            }
        }
        val status = device("tablet").sync(opts, racing)
        assertTrue(status.startsWith("Synced"), status)
        assertEquals(setOf("p", "t"), tablet.data.transactions.map { it.uuid }.toSet())
    }

    @Test
    fun sanitizesFolder() {
        assertEquals("My_Money", GitFolderSync.sanitizeFolder("My/Money"))
        assertEquals("purse", GitFolderSync.sanitizeFolder(" .. "))
        assertFalse(GitFolderSync.sanitizeFolder(".git").startsWith("."))
    }
}
