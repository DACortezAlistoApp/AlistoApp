package ph.alisto.contractor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import ph.alisto.kernel.AlistoKernelClient

class ContractorDispatchMonitorService : Service() {

    companion object {
        const val PREFS_NAME = "alisto_contractor_dispatch"
        const val KEY_SESSION_TOKEN = "session_token"
        private const val CH_STATUS = "alisto_contractor_online_status"
        private const val CH_OFFER = "alisto_contractor_urgent_offer_v2"
        private const val NOTIF_STATUS_ID = 4101
        private const val NOTIF_OFFER_ID = 4102

        fun saveSessionToken(context: Context, token: String?) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                if (token.isNullOrBlank()) remove(KEY_SESSION_TOKEN) else putString(KEY_SESSION_TOKEN, token)
            }.apply()
        }

        fun startMonitoring(context: Context, sessionToken: String?) {
            if (sessionToken.isNullOrBlank()) return
            saveSessionToken(context, sessionToken)
            val intent = Intent(context, ContractorDispatchMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stopMonitoring(context: Context) {
            try {
                context.stopService(Intent(context, ContractorDispatchMonitorService::class.java))
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var lastLaunchedKey: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        val statusNotif = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_STATUS_ID, statusNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_STATUS_ID, statusNotif)
        }
        startOfferPollingLoop()
        return START_STICKY
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Alisto Pro Online Status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps Alisto dispatch listening while you are online."
                }
            )
        }
        if (nm.getNotificationChannel(CH_OFFER) == null) {
            val ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            nm.createNotificationChannel(
                NotificationChannel(CH_OFFER, "Incoming Booking Offers", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Urgent full-screen alerts for auto-assigned and awarded bookings."
                    enableVibration(true)
                    setSound(ringUri, attrs)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun buildStatusNotification(): Notification {
        val openIntent = Intent(this, ContractorMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_alisto_app_logo)
            .setContentTitle("Alisto Pro • Online")
            .setContentText("Listening for auto-assigned jobs & booking awards")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startOfferPollingLoop() {
        if (pollJob?.isActive == true) return
        pollJob = serviceScope.launch {
            while (isActive) {
                val token = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SESSION_TOKEN, null)
                if (token.isNullOrBlank()) {
                    stopSelf()
                    break
                }
                val envelope = AlistoKernelClient.fetchPendingContractorOfferRpc(token)
                if (envelope != null) {
                    val offer = envelope.optJSONObject("offer")
                    val bid = offer?.optString("id").orEmpty()
                    val round = offer?.optInt("offer_round", 1) ?: 1
                    val key = "$bid:$round"
                    val recentlyHandledSame = (ContractorOfferBridge.lastResultBookingId == bid &&
                        System.currentTimeMillis() - ContractorOfferBridge.lastHandledAtMs < 5000L)
                    if (bid.isNotBlank() && ContractorOfferBridge.activeBookingId != bid && key != lastLaunchedKey && !recentlyHandledSame) {
                        lastLaunchedKey = key
                        presentDominantOffer(envelope)
                    }
                }
                delay(3_500L)
            }
        }
    }

    private fun presentDominantOffer(envelope: JSONObject) {
        val offer = envelope.optJSONObject("offer") ?: return
        val overlayIntent = ContractorOfferBridge.buildIntent(this, envelope)
        val fullScreenPi = PendingIntent.getActivity(
            this, 20, overlayIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fee = offer.optDouble("quoted_fee", 0.0)
        val title = offer.optString("service_title", "Incoming Booking")
        val ref = offer.optString("booking_ref", "ALISTO")
        val secs = envelope.optInt("seconds_remaining", 25)

        val notif = NotificationCompat.Builder(this, CH_OFFER)
            .setSmallIcon(R.drawable.ic_alisto_app_logo)
            .setContentTitle("Incoming Booking • PHP ${"%.2f".format(fee)} (${secs}s)")
            .setContentText("$ref • $title — Tap to Accept or Reject")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_OFFER_ID, notif)

        // Launch full-screen dominant activity directly when allowed (foreground or overlay permission granted)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) {
                startActivity(overlayIntent)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        serviceScope.cancel()
    }
}
