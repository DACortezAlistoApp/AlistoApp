package ph.alisto.kernel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object AlistoPushCenter {
    private const val CHANNEL_ID = "alisto_realtime_alerts"
    private const val CHANNEL_NAME = "Alisto Live Dispatch Alerts"
    private val dispatchedIds = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun ensureNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4106)
            }
        }
    }

    private fun ensureChannel(context: Context): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Real-time Engine 06 notifications for bids, arrivals, telemetry, wallet credits, and chat"
                    enableVibration(true)
                }
                nm.createNotificationChannel(ch)
            }
        }
        return nm
    }

    fun dispatchUnreadNotifications(context: Context, scope: CoroutineScope): Int {
        val pending = AlistoKernelClient.unreadNotifications.filter { it.id !in dispatchedIds }
        if (pending.isEmpty()) return 0

        val nm = ensureChannel(context)
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        val newlyHandled = mutableListOf<String>()
        val walletCreditAlerts = mutableListOf<UnreadNotificationRecord>()

        pending.forEach { n ->
            dispatchedIds.add(n.id)
            newlyHandled.add(n.id)
            if (n.eventType.equals("WALLET_CREDITED", ignoreCase = true)) {
                walletCreditAlerts.add(n)
            }
            if (canPost) {
                val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, CHANNEL_ID)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }
                val notif = builder
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(n.title)
                    .setContentText(n.body)
                    .setStyle(Notification.BigTextStyle().bigText(n.body))
                    .setAutoCancel(true)
                    .build()
                nm.notify(n.id.hashCode(), notif)
            }
        }

        // Show in-app confirmation popup for every successful wallet credit (Customer & Contractor)
        if (walletCreditAlerts.isNotEmpty() && context is Activity) {
            val latest = walletCreditAlerts.first()
            mainHandler.post {
                if (!context.isFinishing && !context.isDestroyed) {
                    AlertDialog.Builder(context)
                        .setTitle(latest.title.ifBlank { "Alisto Wallet Credited" })
                        .setMessage(latest.body)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        if (newlyHandled.isNotEmpty()) {
            scope.launch {
                AlistoKernelClient.markNotificationsReadRpc(newlyHandled)
            }
        }
        return newlyHandled.size
    }
}
