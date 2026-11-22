package ph.alisto.contractor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.location.Location
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import ph.alisto.kernel.AlistoLocationTracker
import ph.alisto.kernel.AlistoUiKit
import ph.alisto.kernel.AlistoUiKit.dp

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
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollJob: Job? = null
    private var lastLaunchedKey: String = ""
    private var lastLaunchAtMs: Long = 0L

    private var floatingOverlayView: View? = null
    private var floatingTimer: CountDownTimer? = null
    private var floatingDecisionInFlight: Boolean = false

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
                    description = "Full-screen visual alert for booking offers. Audio plays exclusively via Alisto's in-app sound engine."
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
                    val overlayAlreadyOpen = ContractorOfferBridge.activeBookingId == bid || floatingOverlayView != null
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
                        dismissFloatingOverlay()
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

        // Play in-app road-ready audio immediately (never native phone call/message tones)
        ContractorSoundEngine.startIncomingOfferAlarm(this)

        // Silent high-priority full-screen intent notification (backup if overlay permission is off)
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

        mainHandler.post {
            try {
                if (ContractorOfferBridge.isMainActivityForeground) {
                    startActivity(overlayIntent)
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@ContractorDispatchMonitorService)) {
                    // App is minimized: draw a real WindowManager overlay directly over other apps
                    showFloatingWindowOverlay(envelope)
                } else {
                    try { startActivity(overlayIntent) } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                try { startActivity(overlayIntent) } catch (_: Exception) {}
            }
        }
    }

    private fun computeRoadDistKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double? {
        if ((lat1 == 0.0 && lng1 == 0.0) || (lat2 == 0.0 && lng2 == 0.0)) return null
        val res = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, res)
        val km = res[0].toDouble() / 1000.0
        return if (km < 0.02) null else km * 1.25
    }

    private fun showFloatingWindowOverlay(envelope: JSONObject) {
        dismissFloatingOverlay()
        val offer = envelope.optJSONObject("offer") ?: return
        val bid = offer.optString("id", "")
        if (bid.isBlank()) return

        ContractorOfferBridge.activeBookingId = bid
        floatingDecisionInFlight = false

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val ctx = this
        val bookingRef = offer.optString("booking_ref", "ALISTO")
        val offerSource = offer.optString("offer_source", "AUTO_ASSIGN")
        val verticalKey = offer.optString("vertical_key", "delivery_logistics")
        val serviceTitle = offer.optString("service_title", "Incoming Dispatch")
        val pickupAddress = offer.optString("pickup_address", "")
        val dropoffAddress = offer.optString("dropoff_address", "")
        val bookingNotes = offer.optString("booking_notes", "")
        val quotedFee = offer.optDouble("quoted_fee", 0.0)
        val tipAmount = offer.optDouble("tip_amount", 0.0)
        val paymentMethod = offer.optString("payment_method", "CASH").ifBlank { "CASH" }
        val clientNickname = offer.optString("client_nickname", "Customer").ifBlank { "Customer" }
        val pickupLat = offer.optDouble("pickup_lat", 0.0)
        val pickupLng = offer.optDouble("pickup_lng", 0.0)
        val dropoffLat = offer.optDouble("dropoff_lat", 0.0)
        val dropoffLng = offer.optDouble("dropoff_lng", 0.0)
        val secsInit = envelope.optInt("seconds_remaining", 25).coerceIn(3, 60)
        val tripKm = computeRoadDistKm(pickupLat, pickupLng, dropoffLat, dropoffLng)
        val myLat = AlistoLocationTracker.lastLat
        val myLng = AlistoLocationTracker.lastLng
        val toPickupKm = if (myLat != null && myLng != null) computeRoadDistKm(myLat, myLng, pickupLat, pickupLng) else null

        val isBoardAward = offerSource.equals("BOARD_REQUEST", ignoreCase = true)
        val accent = if (isBoardAward) AlistoUiKit.EMERALD else AlistoUiKit.GOLD_ACCENT

        val backdrop = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F0040914"))
            setPadding(dp(14), dp(42), dp(14), dp(36))
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.roundedBg(ctx, "#0B1324", 18, accent)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        topRow.addView(AlistoUiKit.badgePill(ctx, if (isBoardAward) "BOOKING AWARDED" else "AUTO-ASSIGNED OFFER", "#0F294A", accent))
        topRow.addView(LinearLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        val timerTv = TextView(ctx).apply {
            text = "${secsInit}s"; textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FEF08A"))
            background = AlistoUiKit.roundedBg(ctx, "#451A03", 12, AlistoUiKit.GOLD_ACCENT)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        topRow.addView(timerTv)
        card.addView(topRow)

        val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = secsInit * 10
            this.progress = secsInit * 10
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).apply { bottomMargin = dp(10) }
        }
        card.addView(progress)

        // Scrollable middle section with weight=1 so bottom action buttons NEVER clip off-screen
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(10) }
        }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        body.addView(TextView(ctx).apply {
            text = "${verticalKey.replace("_", " ").uppercase()} • $bookingRef • $paymentMethod"
            textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#93C5FD"))
        })
        body.addView(TextView(ctx).apply {
            text = "PHP ${"%.2f".format(quotedFee + tipAmount)}"
            textSize = 30f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
        })
        if (tripKm != null || toPickupKm != null) {
            val dLine = buildString {
                if (tripKm != null) append("Route: ${"%.1f".format(tripKm)} km (Pickup → Drop-Off)")
                if (toPickupKm != null) {
                    if (isNotEmpty()) append("  •  ")
                    append("Pickup: ${"%.1f".format(toPickupKm)} km away")
                }
            }
            body.addView(TextView(ctx).apply {
                text = dLine
                textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#6EE7B7"))
                setPadding(0, dp(3), 0, dp(8))
            })
        }
        body.addView(TextView(ctx).apply {
            text = serviceTitle
            textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        body.addView(TextView(ctx).apply {
            text = "Pickup: $pickupAddress" +
                (if (dropoffAddress.isNotBlank() && dropoffAddress != pickupAddress) "\n\nDrop-Off: $dropoffAddress" else "") +
                "\n\nCustomer: $clientNickname" +
                (if (bookingNotes.isNotBlank()) "\nNote: $bookingNotes" else "")
            textSize = 13f; setTextColor(Color.parseColor("#CBD5E1")); setPadding(0, dp(8), 0, dp(4))
        })
        scroll.addView(body)
        card.addView(scroll)

        val statusHint = TextView(ctx).apply {
            text = "Respond before 0s — expired offers auto-forward."
            textSize = 11.5f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(8))
        }
        card.addView(statusHint)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        btnRow.addView(
            AlistoUiKit.pillButton(ctx, "REJECT", "#450A0A", "#FCA5A5", "#EF4444", heightDp = 48, textSizeSp = 14f) {
                submitFloatingDecision(bid, "REJECT", statusHint)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply { rightMargin = dp(8) } }
        )
        btnRow.addView(
            AlistoUiKit.pillButton(ctx, "ACCEPT BOOKING", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, "#6EE7B7", heightDp = 48, textSizeSp = 14.5f) {
                submitFloatingDecision(bid, "ACCEPT", statusHint)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f) }
        )
        card.addView(btnRow)
        backdrop.addView(card)

        try {
            wm.addView(backdrop, params)
            floatingOverlayView = backdrop
            floatingTimer = object : CountDownTimer(secsInit * 1000L, 100L) {
                override fun onTick(millisUntilFinished: Long) {
                    val s = ((millisUntilFinished + 999L) / 1000L).toInt().coerceAtLeast(0)
                    timerTv.text = "${s}s"
                    progress.progress = (millisUntilFinished / 100L).toInt()
                }
                override fun onFinish() {
                    timerTv.text = "0s"
                    progress.progress = 0
                    submitFloatingDecision(bid, "TIMEOUT", statusHint)
                }
            }.start()
        } catch (_: Exception) {
            ContractorOfferBridge.activeBookingId = null
        }
    }

    private fun submitFloatingDecision(bid: String, decision: String, statusHint: TextView) {
        if (floatingDecisionInFlight) return
        floatingDecisionInFlight = true
        floatingTimer?.cancel()
        ContractorSoundEngine.stopIncomingOfferAlarm()
        cancelOfferNotification(this)

        statusHint.text = when (decision) {
            "ACCEPT" -> "Accepting booking..."
            "REJECT" -> "Declining & forwarding..."
            else -> "Timed out — forwarding..."
        }

        val token = AlistoKernelClient.currentSessionToken
            ?: getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SESSION_TOKEN, null)

        mainScope.launch {
            val res = AlistoKernelClient.respondBookingOfferRpc(bid, decision, token)
            ContractorOfferBridge.lastResultDecision = decision
            ContractorOfferBridge.lastResultBookingId = bid
            ContractorOfferBridge.lastHandledAtMs = System.currentTimeMillis()
            ContractorOfferBridge.activeBookingId = null
            dismissFloatingOverlay()

            res.onSuccess {
                if (decision == "ACCEPT") {
                    ContractorSoundEngine.playAcceptedTone(this@ContractorDispatchMonitorService)
                    Toast.makeText(this@ContractorDispatchMonitorService, "Booking accepted! Opening Active tab.", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(this@ContractorDispatchMonitorService, ContractorMainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra("open_tab", "ACTIVE")
                        })
                    } catch (_: Exception) {}
                } else {
                    ContractorSoundEngine.playRejectedTone(this@ContractorDispatchMonitorService)
                    Toast.makeText(this@ContractorDispatchMonitorService, "Booking offer forwarded.", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                Toast.makeText(this@ContractorDispatchMonitorService, err.message ?: "Offer response failed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dismissFloatingOverlay() {
        mainHandler.post {
            floatingTimer?.cancel()
            floatingTimer = null
            floatingOverlayView?.let { v ->
                try {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(v)
                } catch (_: Exception) {}
            }
            floatingOverlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        dismissFloatingOverlay()
        serviceScope.cancel()
        mainScope.cancel()
        ContractorSoundEngine.stopIncomingOfferAlarm()
    }
}
