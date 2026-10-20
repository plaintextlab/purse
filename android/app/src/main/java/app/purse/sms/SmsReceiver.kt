package app.purse.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.purse.container
import kotlinx.coroutines.launch

/** Turns incoming bank SMS into transactions when SMS tracking is enabled. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        val sender = parts[0].originatingAddress ?: parts[0].displayOriginatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val timestamp = parts[0].timestampMillis

        val container = context.container
        val pending = goAsync()
        container.scope.launch {
            try {
                val settings = container.settings.current()
                if (!settings.smsEnabled) return@launch
                val candidate = container.sms.evaluate(sender, body, timestamp) ?: return@launch
                val txn = container.sms.import(candidate) ?: return@launch
                if (settings.smsNotify) {
                    val account = container.repo.account(txn.accountId)?.name.orEmpty()
                    Notifications.txnAdded(context, txn, settings, account)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
