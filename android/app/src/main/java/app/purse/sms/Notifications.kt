package app.purse.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.purse.MainActivity
import app.purse.R
import app.purse.core.Money
import app.purse.data.Settings
import app.purse.data.TxnEntity
import app.purse.data.TxnType

object Notifications {
    private const val CHANNEL_SMS = "sms_txn"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SMS, "Transactions from SMS", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun txnAdded(context: Context, txn: TxnEntity, settings: Settings, accountName: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        val amount = Money.format(txn.amount, settings.currency, settings.indianGrouping)
        val verb = if (txn.type == TxnType.INCOME) "received" else "spent"
        val open = PendingIntent.getActivity(
            context,
            txn.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_EDIT_TXN, txn.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$amount $verb")
            .setContentText(listOf(txn.note, accountName).filter { it.isNotBlank() }.joinToString(" · ") + " — tap to categorise")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(txn.id.hashCode(), n)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}
