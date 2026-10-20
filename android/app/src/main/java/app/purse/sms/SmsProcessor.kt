package app.purse.sms

import android.content.Context
import android.provider.Telephony
import app.purse.core.AccountHint
import app.purse.core.Glob
import app.purse.core.SmsMatch
import app.purse.core.SmsPattern
import app.purse.data.Repository
import app.purse.data.SettingsStore
import app.purse.data.SmsTemplateEntity
import app.purse.data.TxnEntity
import app.purse.data.TxnSource
import app.purse.data.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** An SMS that matched one of the user's templates. */
data class SmsCandidate(
    val sender: String,
    val body: String,
    val timestamp: Long,
    val hash: String,
    val template: SmsTemplateEntity,
    val match: SmsMatch,
    val accountId: String?,
)

class SmsProcessor(private val repo: Repository, private val settings: SettingsStore) {

    /** Finds the first enabled template whose sender pattern and body template match. */
    suspend fun evaluate(sender: String, body: String, timestamp: Long): SmsCandidate? {
        val templates = repo.activeTemplates()
        for (t in templates) {
            if (!Glob.matches(t.senderPattern, sender)) continue
            val match = SmsPattern.compileOrNull(t.pattern)?.match(body) ?: continue
            return SmsCandidate(sender, body, timestamp, hash(body, timestamp), t, match, resolveAccount(t, match))
        }
        return null
    }

    private suspend fun resolveAccount(t: SmsTemplateEntity, m: SmsMatch): String? {
        val accounts = repo.activeAccounts()
        t.accountId?.let { id -> if (accounts.any { it.id == id }) return id }
        m.account?.let { digits -> accounts.firstOrNull { AccountHint.matches(digits, it.numberHint) }?.let { return it.id } }
        val fallback = settings.current().defaultAccountId
        return accounts.firstOrNull { it.id == fallback }?.id ?: accounts.firstOrNull()?.id
    }

    /** Saves the candidate unless it was imported before. Returns the new transaction. */
    suspend fun import(c: SmsCandidate): TxnEntity? {
        if (repo.hasSms(c.hash)) return null
        val accountId = c.accountId ?: return null
        val txn = TxnEntity(
            type = if (c.template.txnType == TxnType.INCOME) TxnType.INCOME else TxnType.EXPENSE,
            amount = c.match.amountMinor,
            accountId = accountId,
            categoryId = c.template.categoryId,
            note = c.match.merchant.orEmpty(),
            timestamp = c.timestamp,
            source = TxnSource.SMS,
            smsHash = c.hash,
        )
        repo.saveTxn(txn)
        return txn
    }

    /** Matching messages from the inbox since [since] that are not imported yet. Needs READ_SMS. */
    suspend fun scanInbox(context: Context, since: Long): List<SmsCandidate> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<Triple<String, String, Long>>()
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)
                val body = c.getString(1)
                val sent = c.getLong(3)
                if (address != null && body != null) rows += Triple(address, body, if (sent > 0) sent else c.getLong(2))
            }
        }
        val out = mutableListOf<SmsCandidate>()
        for ((address, body, ts) in rows) {
            val cand = evaluate(address, body, ts)
            if (cand != null && !repo.hasSms(cand.hash)) out += cand
        }
        out
    }

    companion object {
        /**
         * Identity of an SMS for de-duplication between the live receiver and inbox scans.
         * Both use the service-centre timestamp, truncated to seconds.
         */
        fun hash(body: String, timestamp: Long): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest("${body.trim()}|${timestamp / 1000}".toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
