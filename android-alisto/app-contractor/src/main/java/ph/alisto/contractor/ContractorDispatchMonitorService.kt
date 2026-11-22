package ph.alisto.contractor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        private const val CH_STATUS = "alisto_contractor_online_status_v2"
        private const val CH_OFFER_SILENT = "alisto_contractor_urgent_offer_v3_silent"
        private const val NOTIF_STATUS_ID = 4101
        const val NOTIF_OFFER_ID = 4102

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
                cancelOfferNotification(context)
                ContractorSoundEngine.stopIncomingOfferAlarm()
                context.stopService(Intent(context, ContractorDispatchMonitorService::class.java))
            } catch (_: Exception) {}
        }

        fun cancelOfferNotification(context: Context) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF_OFFER_ID)
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollJob: Job? = null
    private var lastLaunchedKey: String = ""
    private var lastLaunchAtMs: Long = 0L

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

        // Remove legacy ringtone-enabled channels if present so native phone tones never fire
        try { nm.deleteNotificationChannel("alisto_contractor_urgent_offer_v2") } catch (_: Exception) {}

        if (nm.getNotificationChannel(CH_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Alisto Pro Online Status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps Alisto dispatch listening while you are online."
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        if (nm.getNotificationChannel(CH_OFFER_SILENT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_OFFER_SILENT, "Incoming Booking Offers (In-App Audio)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Full-screen visual alert for booking offers. Audio is played exclusively by Alisto's in-app sound engine."
                    setSound(null, null)
                    enableVibration(false)
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
            .setSilent(true)
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
                    val now = System.currentTimeMillis()
                    val recentlyHandledSame = (ContractorOfferBridge.lastResultBookingId == bid &&
                        now - ContractorOfferBridge.lastHandledAtMs < 5500L)
                    val overlayAlreadyOpen = ContractorOfferBridge.activeBookingId == bid
                    // Allow safe re-prompt if overlay failed to appear within 6s while offer is still live
                    val shouldLaunch = bid.isNotBlank() && !overlayAlreadyOpen && !recentlyHandledSame &&
                        (key != lastLaunchedKey || (now - lastLaunchAtMs) > 6500L)

                    if (shouldLaunch) {
                        lastLaunchedKey = key
                        lastLaunchAtMs = now
                        presentDominantOffer(envelope)
                    }
                } else {
                    if (lastLaunchedKey.isNotEmpty() && ContractorOfferBridge.activeBookingId == null) {
                        lastLaunchedKey = ""
                        cancelOfferNotification(this@ContractorDispatchMonitorService)
                        ContractorSoundEngine.stopIncomingOfferAlarm()
                    }
                }
                delay(2_500L)
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

        // Start in-app road-ready audio immediately (never native phone ringtone)
        ContractorSoundEngine.startIncomingOfferAlarm(this)

        // Silent high-priority full-screen intent notification (backs up minimized & locked states)
        val notif = NotificationCompat.Builder(this, CH_OFFER_SILENT)
            .setSmallIcon(R.drawable.ic_alisto_app_logo)
            .setContentTitle("Incoming Booking • PHP ${"%.2f".format(fee)} (${secs}s)")
            .setContentText("$ref • $title — Tap to Accept or Reject")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(true)
            .setSound(null)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_OFFER_ID, notif)

        // Launch full-screen activity directly when app is in foreground (any tab) or overlay permission is granted
        mainHandler.post {
            try {
                val canDirectLaunch = ContractorOfferBridge.isMainActivityForeground ||
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    Settings.canDrawOverlays(this@ContractorDispatchMonitorService)
                if (canDirectLaunch) {
                    startActivity(overlayIntent)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        serviceScope.cancel()
        ContractorSoundEngine.stopIncomingOfferAlarm()
    }
}
