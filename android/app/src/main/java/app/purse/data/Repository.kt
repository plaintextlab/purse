package app.purse.data

import androidx.room.withTransaction
import app.purse.core.AccountRec
import app.purse.core.CategoryRec
import app.purse.core.Snapshot
import app.purse.core.SyncMerge
import app.purse.core.TemplateRec
import app.purse.core.TxnRec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Single entry point for reading and writing data. Every local write bumps
 * `updatedAt` and calls [onLocalChange] (widget refresh + sync scheduling).
 * Deletes are soft (tombstones) so they propagate through sync.
 */
class Repository(
    private val db: PurseDatabase,
    scope: CoroutineScope,
    private val onLocalChange: () -> Unit,
    private val onRemoteChange: () -> Unit,
) {
    private fun now() = System.currentTimeMillis()

    val accounts: StateFlow<List<AccountEntity>> =
        db.accounts().observe().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Account id -> balance in minor units. */
    val balances: StateFlow<Map<String, Long>> =
        db.accounts().observeBalances()
            .map { list -> list.associate { it.accountId to it.balance } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** All categories including deleted ones (for labelling old transactions). */
    val categories: StateFlow<List<CategoryEntity>> =
        db.categories().observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val templates: StateFlow<List<SmsTemplateEntity>> =
        db.templates().observe().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun txnsBetween(from: Long, to: Long): Flow<List<TxnEntity>> = db.txns().observeRange(from, to)

    fun recent(limit: Int): Flow<List<TxnEntity>> = db.txns().observeRecent(limit)

    fun search(q: String): Flow<List<TxnEntity>> = db.txns().search(q)

    suspend fun txn(id: String): TxnEntity? = db.txns().get(id)

    suspend fun account(id: String): AccountEntity? = db.accounts().get(id)

    suspend fun template(id: String): SmsTemplateEntity? = db.templates().get(id)

    suspend fun activeAccounts(): List<AccountEntity> = db.accounts().all().filter { !it.deleted }

    suspend fun activeTemplates(): List<SmsTemplateEntity> = db.templates().all().filter { !it.deleted && it.enabled }

    suspend fun hasSms(hash: String): Boolean = db.txns().countSms(hash) > 0

    // ---- writes ----

    suspend fun saveTxn(t: TxnEntity) {
        db.txns().upsert(listOf(t.copy(updatedAt = now())))
        onLocalChange()
    }

    suspend fun deleteTxn(id: String) {
        val t = db.txns().get(id) ?: return
        db.txns().upsert(listOf(t.copy(deleted = true, updatedAt = now())))
        onLocalChange()
    }

    suspend fun saveAccount(a: AccountEntity) {
        db.accounts().upsert(listOf(a.copy(updatedAt = now())))
        onLocalChange()
    }

    /** Deletes the account together with all of its transactions. */
    suspend fun deleteAccount(id: String) {
        val a = db.accounts().get(id) ?: return
        db.withTransaction {
            db.txns().deleteForAccount(id, now())
            db.accounts().upsert(listOf(a.copy(deleted = true, updatedAt = now())))
        }
        onLocalChange()
    }

    /** Records an adjustment so the account balance becomes [target]. History stays intact. */
    suspend fun setBalance(accountId: String, target: Long, note: String = "Balance set") {
        val current = db.accounts().balances().firstOrNull { it.accountId == accountId }?.balance ?: 0L
        val diff = target - current
        if (diff == 0L) return
        saveTxn(
            TxnEntity(
                type = TxnType.ADJUSTMENT,
                amount = diff,
                accountId = accountId,
                note = note,
                timestamp = now(),
            ),
        )
    }

    suspend fun saveCategory(c: CategoryEntity) {
        db.categories().upsert(listOf(c.copy(updatedAt = now())))
        onLocalChange()
    }

    suspend fun deleteCategory(c: CategoryEntity) = saveCategory(c.copy(deleted = true))

    suspend fun saveTemplate(t: SmsTemplateEntity) {
        db.templates().upsert(listOf(t.copy(updatedAt = now())))
        onLocalChange()
    }

    suspend fun deleteTemplate(t: SmsTemplateEntity) = saveTemplate(t.copy(deleted = true))

    /**
     * Default categories and accounts. Fixed ids and updatedAt = 0 mean two devices
     * seeding independently converge after sync instead of duplicating.
     */
    suspend fun seed() {
        if (db.categories().all().isEmpty()) {
            val expense = listOf(
                "food" to "🍔 Food", "groceries" to "🛒 Groceries", "transport" to "🚕 Transport",
                "shopping" to "🛍️ Shopping", "bills" to "💡 Bills", "rent" to "🏠 Rent",
                "entertainment" to "🎬 Entertainment", "health" to "💊 Health", "education" to "📚 Education",
                "travel" to "✈️ Travel", "other" to "📦 Other",
            )
            val income = listOf(
                "salary" to "💼 Salary", "interest" to "🏦 Interest", "refund" to "↩️ Refund",
                "gift" to "🎁 Gift", "other-income" to "💰 Other income",
            )
            fun build(list: List<Pair<String, String>>, kind: CategoryKind) = list.mapIndexed { i, (id, label) ->
                CategoryEntity(
                    id = "seed-cat-$id",
                    name = label.substringAfter(' '),
                    icon = label.substringBefore(' '),
                    kind = kind,
                    sortOrder = i,
                )
            }
            db.categories().upsert(build(expense, CategoryKind.EXPENSE) + build(income, CategoryKind.INCOME))
        }
        if (db.accounts().all().isEmpty()) {
            db.accounts().upsert(
                listOf(
                    AccountEntity(id = "seed-acc-cash", name = "Cash", type = AccountType.CASH, sortOrder = 0),
                    AccountEntity(id = "seed-acc-bank", name = "Bank", type = AccountType.SAVINGS, sortOrder = 1),
                ),
            )
        }
    }

    // ---- sync ----

    suspend fun snapshot(): Snapshot = Snapshot(
        accounts = db.accounts().all().map { it.toRec() },
        categories = db.categories().all().map { it.toRec() },
        transactions = db.txns().all().map { it.toRec() },
        templates = db.templates().all().map { it.toRec() },
    )

    /** Applies records from [remote] that are newer than ours. Returns how many changed. */
    suspend fun applyIncoming(remote: Snapshot): Int {
        var changed = 0
        db.withTransaction {
            val acc = SyncMerge.incoming(db.accounts().all().map { it.toRec() }, remote.accounts)
            db.accounts().upsert(acc.map { it.toEntity() })
            val cat = SyncMerge.incoming(db.categories().all().map { it.toRec() }, remote.categories)
            db.categories().upsert(cat.map { it.toEntity() })
            val txn = SyncMerge.incoming(db.txns().all().map { it.toRec() }, remote.transactions)
            db.txns().upsert(txn.map { it.toEntity() })
            val tpl = SyncMerge.incoming(db.templates().all().map { it.toRec() }, remote.templates)
            db.templates().upsert(tpl.map { it.toEntity() })
            changed = acc.size + cat.size + txn.size + tpl.size
        }
        if (changed > 0) onRemoteChange()
        return changed
    }
}

private fun AccountEntity.toRec() = AccountRec(id, name, type.name, numberHint, excludeFromTotal, archived, sortOrder, updatedAt, deleted)

private fun AccountRec.toEntity() = AccountEntity(
    uuid, name, enumOr(type, AccountType.OTHER), numberHint, excludeFromTotal, archived, sortOrder, updatedAt, deleted,
)

private fun CategoryEntity.toRec() = CategoryRec(id, name, kind.name, icon, sortOrder, updatedAt, deleted)

private fun CategoryRec.toEntity() = CategoryEntity(uuid, name, enumOr(kind, CategoryKind.EXPENSE), icon, sortOrder, updatedAt, deleted)

private fun TxnEntity.toRec() = TxnRec(
    id, type.name, amount, accountId, toAccountId, categoryId, note, timestamp, source.name, smsHash, updatedAt, deleted,
)

private fun TxnRec.toEntity() = TxnEntity(
    uuid, enumOr(type, TxnType.EXPENSE), amount, accountId, toAccountId, categoryId, note, timestamp,
    enumOr(source, TxnSource.MANUAL), smsHash, updatedAt, deleted,
)

private fun SmsTemplateEntity.toRec() = TemplateRec(
    id, name, senderPattern, pattern, sample, txnType.name, accountId, categoryId, enabled, updatedAt, deleted,
)

private fun TemplateRec.toEntity() = SmsTemplateEntity(
    uuid, name, senderPattern, pattern, sample, enumOr(txnType, TxnType.EXPENSE), accountId, categoryId, enabled, updatedAt, deleted,
)
