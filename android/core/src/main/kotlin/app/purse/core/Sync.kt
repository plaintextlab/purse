package app.purse.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The sync format is plain JSON so other clients (e.g. the planned desktop app)
 * can read and write the same folder. See docs/sync-format.md.
 *
 * Every record carries a stable [uuid], an [updatedAt] timestamp (epoch millis)
 * and a [deleted] tombstone flag. Merging is last-writer-wins per record.
 */
interface Syncable {
    val uuid: String
    val updatedAt: Long
}

@Serializable
data class AccountRec(
    override val uuid: String,
    val name: String,
    val type: String,
    val numberHint: String = "",
    val excludeFromTotal: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    override val updatedAt: Long,
    val deleted: Boolean = false,
) : Syncable

@Serializable
data class CategoryRec(
    override val uuid: String,
    val name: String,
    val kind: String,
    val icon: String = "",
    val sortOrder: Int = 0,
    override val updatedAt: Long,
    val deleted: Boolean = false,
) : Syncable

@Serializable
data class TxnRec(
    override val uuid: String,
    val type: String,
    /** Minor units. Positive for expense/income/transfer; signed for adjustments. */
    val amount: Long,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val note: String = "",
    val timestamp: Long,
    val source: String = "MANUAL",
    val smsHash: String? = null,
    override val updatedAt: Long,
    val deleted: Boolean = false,
) : Syncable

@Serializable
data class TemplateRec(
    override val uuid: String,
    val name: String,
    val senderPattern: String,
    val pattern: String,
    val sample: String = "",
    val txnType: String = "EXPENSE",
    val accountId: String? = null,
    val categoryId: String? = null,
    val enabled: Boolean = true,
    override val updatedAt: Long,
    val deleted: Boolean = false,
) : Syncable

@Serializable
data class Snapshot(
    val accounts: List<AccountRec> = emptyList(),
    val categories: List<CategoryRec> = emptyList(),
    val transactions: List<TxnRec> = emptyList(),
    val templates: List<TemplateRec> = emptyList(),
)

@Serializable
private data class Meta(val format: Int, val app: String = "purse")

@Serializable
private data class Bundle(val format: Int, val data: Snapshot)

object SyncCodec {
    const val FORMAT = 1
    const val META = "meta.json"
    const val ACCOUNTS = "accounts.json"
    const val CATEGORIES = "categories.json"
    const val TEMPLATES = "sms-templates.json"
    const val TXN_DIR = "transactions"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val month = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC)

    /** Relative path -> file content. Transactions are split per month (UTC) to keep diffs small. */
    fun encode(s: Snapshot): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out[META] = json.encodeToString(Meta.serializer(), Meta(FORMAT))
        out[ACCOUNTS] = json.encodeToString(s.accounts.sortedBy { it.uuid })
        out[CATEGORIES] = json.encodeToString(s.categories.sortedBy { it.uuid })
        out[TEMPLATES] = json.encodeToString(s.templates.sortedBy { it.uuid })
        s.transactions
            .groupBy { month.format(Instant.ofEpochMilli(it.timestamp)) }
            .toSortedMap()
            .forEach { (ym, list) ->
                out["$TXN_DIR/$ym.json"] = json.encodeToString(list.sortedWith(compareBy({ it.timestamp }, { it.uuid })))
            }
        return out
    }

    /** Reads files written by [encode]. Unknown files are ignored. */
    fun decode(files: Map<String, String>): Snapshot {
        files[META]?.let {
            val meta = json.decodeFromString(Meta.serializer(), it)
            require(meta.format <= FORMAT) { "Sync data uses format ${meta.format}; update the app" }
        }
        val txns = files.filterKeys { it.startsWith("$TXN_DIR/") && it.endsWith(".json") }
            .values.flatMap { json.decodeFromString<List<TxnRec>>(it) }
        return Snapshot(
            accounts = latest(files[ACCOUNTS]?.let { json.decodeFromString<List<AccountRec>>(it) }.orEmpty()),
            categories = latest(files[CATEGORIES]?.let { json.decodeFromString<List<CategoryRec>>(it) }.orEmpty()),
            transactions = latest(txns),
            templates = latest(files[TEMPLATES]?.let { json.decodeFromString<List<TemplateRec>>(it) }.orEmpty()),
        )
    }

    /** Single-file backup used by export/import. */
    fun encodeBundle(s: Snapshot): String = json.encodeToString(Bundle.serializer(), Bundle(FORMAT, s))

    fun decodeBundle(text: String): Snapshot {
        val b = json.decodeFromString(Bundle.serializer(), text)
        require(b.format <= FORMAT) { "Backup uses format ${b.format}; update the app" }
        return b.data
    }

    private fun <T : Syncable> latest(list: List<T>): List<T> =
        list.groupBy { it.uuid }.values.map { dupes -> dupes.maxBy { it.updatedAt } }
}

object SyncMerge {
    /** Remote records that are new or strictly newer than the local copy. Ties keep local. */
    fun <T : Syncable> incoming(local: Collection<T>, remote: Collection<T>): List<T> {
        val byId = local.associateBy { it.uuid }
        return remote.filter { r ->
            val l = byId[r.uuid]
            l == null || r.updatedAt > l.updatedAt
        }
    }
}
