package app.purse.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class AccountType(val label: String, val emoji: String) {
    SAVINGS("Savings account", "🏦"),
    CURRENT("Current account", "🏢"),
    CREDIT_CARD("Credit card", "💳"),
    CASH("Cash", "💵"),
    WALLET("Wallet / UPI", "📱"),
    FD("Fixed deposit", "🔒"),
    RD("Recurring deposit", "🔁"),
    MUTUAL_FUND("Mutual fund", "📈"),
    STOCKS("Stocks", "📊"),
    LOAN("Loan", "🧾"),
    OTHER("Other", "💼"),
}

enum class TxnType { EXPENSE, INCOME, TRANSFER, ADJUSTMENT }

enum class CategoryKind { EXPENSE, INCOME }

enum class TxnSource { MANUAL, SMS }

fun newId(): String = UUID.randomUUID().toString()

inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val type: AccountType,
    /** Last digits of account / card numbers, comma separated. Used to route SMS. */
    val numberHint: String = "",
    val excludeFromTotal: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val kind: CategoryKind,
    val icon: String = "",
    val sortOrder: Int = 0,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "transactions",
    indices = [Index("timestamp"), Index("accountId"), Index("toAccountId"), Index("smsHash")],
)
data class TxnEntity(
    @PrimaryKey val id: String = newId(),
    val type: TxnType,
    /** Minor units. Positive for expense/income/transfer, signed for adjustments. */
    val amount: Long,
    val accountId: String,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val note: String = "",
    val timestamp: Long,
    val source: TxnSource = TxnSource.MANUAL,
    val smsHash: String? = null,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

@Entity(tableName = "sms_templates")
data class SmsTemplateEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    /** Wildcard sender pattern such as *HDFC* */
    val senderPattern: String,
    /** Body template with {amount}, {account}, {merchant}, {*} placeholders. */
    val pattern: String,
    val sample: String = "",
    val txnType: TxnType = TxnType.EXPENSE,
    /** Fixed account; null means "match by the {account} digits". */
    val accountId: String? = null,
    val categoryId: String? = null,
    val enabled: Boolean = true,
    val updatedAt: Long = 0,
    val deleted: Boolean = false,
)

data class AccountBalance(val accountId: String, val balance: Long)
