package app.purse.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import android.content.Context
import kotlinx.coroutines.flow.Flow

// Expense/transfer-out reduce the source account, income and transfer-in increase,
// adjustments carry their own sign.
private const val BALANCES = """
    SELECT a.id AS accountId,
      COALESCE((SELECT SUM(CASE t.type
                  WHEN 'EXPENSE' THEN -t.amount
                  WHEN 'TRANSFER' THEN -t.amount
                  ELSE t.amount END)
                FROM transactions t WHERE t.deleted = 0 AND t.accountId = a.id), 0)
      + COALESCE((SELECT SUM(t.amount) FROM transactions t
                  WHERE t.deleted = 0 AND t.type = 'TRANSFER' AND t.toAccountId = a.id), 0) AS balance
    FROM accounts a WHERE a.deleted = 0
"""

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observe(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: String): AccountEntity?

    @Upsert
    suspend fun upsert(items: List<AccountEntity>)

    @Query(BALANCES)
    fun observeBalances(): Flow<List<AccountBalance>>

    @Query(BALANCES)
    suspend fun balances(): List<AccountBalance>
}

@Dao
interface CategoryDao {
    /** Includes deleted rows so old transactions can still show their category name. */
    @Query("SELECT * FROM categories ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<CategoryEntity>

    @Upsert
    suspend fun upsert(items: List<CategoryEntity>)
}

@Dao
interface TxnDao {
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND timestamp >= :from AND timestamp < :to ORDER BY timestamp DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TxnEntity>>

    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TxnEntity>>

    @Query(
        """SELECT t.* FROM transactions t LEFT JOIN categories c ON c.id = t.categoryId
           WHERE t.deleted = 0 AND (t.note LIKE '%' || :q || '%' OR c.name LIKE '%' || :q || '%')
           ORDER BY t.timestamp DESC LIMIT 500""",
    )
    fun search(q: String): Flow<List<TxnEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: String): TxnEntity?

    @Query("SELECT * FROM transactions")
    suspend fun all(): List<TxnEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE smsHash = :hash")
    suspend fun countSms(hash: String): Int

    @Query("UPDATE transactions SET deleted = 1, updatedAt = :now WHERE deleted = 0 AND (accountId = :accountId OR toAccountId = :accountId)")
    suspend fun deleteForAccount(accountId: String, now: Long)

    @Upsert
    suspend fun upsert(items: List<TxnEntity>)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM sms_templates WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observe(): Flow<List<SmsTemplateEntity>>

    @Query("SELECT * FROM sms_templates")
    suspend fun all(): List<SmsTemplateEntity>

    @Query("SELECT * FROM sms_templates WHERE id = :id")
    suspend fun get(id: String): SmsTemplateEntity?

    @Upsert
    suspend fun upsert(items: List<SmsTemplateEntity>)
}

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, TxnEntity::class, SmsTemplateEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PurseDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun txns(): TxnDao
    abstract fun templates(): TemplateDao

    companion object {
        fun build(context: Context): PurseDatabase =
            Room.databaseBuilder(context, PurseDatabase::class.java, "purse.db").build()
    }
}
