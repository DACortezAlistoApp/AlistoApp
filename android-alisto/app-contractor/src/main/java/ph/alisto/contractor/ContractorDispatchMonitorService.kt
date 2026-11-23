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
import android.os.PowerManager
import android.view.ContextThemeWrapper
import android.provider.Settings
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
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
        private const val CH_OFFER_SILENT = "alisto_contractor_urgent_offer_v4_heads_up"
        const val ACTION_OFFER_DECISION = "ph.alisto.contractor.ACTION_OFFER_DECISION"
        const val ACTION_TEST_OVERLAY = "ph.alisto.contractor.ACTION_TEST_OVERLAY"
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

        fun scheduleOverlaySelfTest(context: Context) {
            val intent = Intent(context, ContractorDispatchMonitorService::class.java).apply {
                action = ACTION_TEST_OVERLAY
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                try { context.startService(intent) } catch (_: Exception) {}
            }
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
    private var activeOverlayWm: WindowManager? = null
    private var isDemoOverlayActive: Boolean = false
    private var floatingTimer: CountDownTimer? = null
    private var floatingDecisionInFlight: Boolean = false
    private var offerWakeLock: PowerManager.WakeLock? = null

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

        when (intent?.action) {
            ACTION_OFFER_DECISION -> {
                val bid = intent.getStringExtra("booking_id").orEmpty()
                val dec = intent.getStringExtra("decision").orEmpty()
                if (bid.isNotBlank() && dec.isNotBlank()) {
                    handleNotificationActionDecision(bid, dec)
                }
            }
            ACTION_TEST_OVERLAY -> {
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "Minimize Alisto Contractor now — test 15s overlay will pop in 5s!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                mainHandler.postDelayed({
                    val mockOffer = JSONObject().apply {
                        put("id", "DEMO_OVERLAY_TEST")
                        put("booking_ref", "DEMO-TEST")
                        put("offer_source", "AUTO_ASSIGN")
                        put("vertical_key", "ride_hailing")
                        put("service_title", "Minimized Overlay Self-Test (Demo Only)")
                        put("pickup_address", "SM City Clark Main Entrance, Angeles City")
                        put("dropoff_address", "Nepo Quad, Sto. Rosario, Angeles City")
                        put("booking_notes", "Demo test card — no live booking is affected.")
                        put("quoted_fee", 95.0)
                        put("tip_amount", 15.0)
                        put("payment_method", "CASH")
                        put("client_nickname", "Overlay Test")
                    }
                    val mockEnv = JSONObject().apply {
                        put("seconds_remaining", 15)
                        put("offer", mockOffer)
                    }
                    isDemoOverlayActive = true
                    presentDominantOffer(mockEnv)
                }, 5_000L)
            }
        }
        return START_STICKY
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.deleteNotificationChannel("alisto_contractor_urgent_offer_v2") } catch (_: Exception) {}
        try { nm.deleteNotificationChannel("alisto_contractor_urgent_offer_v3_silent") } catch (_: Exception) {}

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
                NotificationChannel(CH_OFFER_SILENT, "Incoming Booking Offers (Heads-Up)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "High-priority heads-up alert for incoming booking offers. Audio plays via Alisto's in-app sound engine."
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 220, 120, 220)
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
                    if (!floatingDecisionInFlight && !isDemoOverlayActive &&
                        (lastLaunchedKey.isNotEmpty() || floatingOverlayView != null || ContractorOfferBridge.activeBookingId != null)
                    ) {
                        lastLaunchedKey = ""
                        ContractorOfferBridge.activeBookingId = null
                        cancelOfferNotification(this@ContractorDispatchMonitorService)
                        dismissFloatingOverlay()
                        ContractorOfferBridge.dismissActiveOverlay("Booking offer expired or cancelled.")
                        ContractorSoundEngine.stopIncomingOfferAlarm()
                    }
                }
                delay(1_500L)
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
        val secs = envelope.optInt("seconds_remaining", 15).coerceIn(1, 30)

        // Play in-app road-ready audio immediately (never native phone call/message tones)
        ContractorSoundEngine.startIncomingOfferAlarm(this)

        val bid = offer.optString("id", "")
        val acceptPi = PendingIntent.getBroadcast(
            this, 21,
            Intent(this, ContractorOfferActionReceiver::class.java).apply {
                putExtra("booking_id", bid); putExtra("decision", "ACCEPT")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rejectPi = PendingIntent.getBroadcast(
            this, 22,
            Intent(this, ContractorOfferActionReceiver::class.java).apply {
                putExtra("booking_id", bid); putExtra("decision", "REJECT")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // High-priority heads-up notification (NOT silent, so Android 14 displays the banner over any app)
        val notif = NotificationCompat.Builder(this, CH_OFFER_SILENT)
            .setSmallIcon(R.drawable.ic_alisto_app_logo)
            .setContentTitle("Incoming Booking • PHP ${"%.2f".format(fee)} (${secs}s)")
            .setContentText("$ref • $title — Tap Accept or Reject")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setTimeoutAfter((secs + 3) * 1000L)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "REJECT", rejectPi)
            .addAction(android.R.drawable.ic_menu_send, "ACCEPT", acceptPi)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_OFFER_ID, notif)

        mainHandler.post {
            try {
                val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@ContractorDispatchMonitorService)
                if (ContractorOfferBridge.isMainActivityForeground && !isDemoOverlayActive) {
                    startActivity(overlayIntent)
                } else if (canOverlay) {
                    // Minimized Maxim-style path: render directly onto Android WindowContext overlay
                    showFloatingWindowOverlay(envelope)
                } else {
                    Log.w("AlistoOfferOverlay", "Display over other apps permission is OFF; relying on heads-up notification.")
                }
            } catch (e: Exception) {
                Log.e("AlistoOfferOverlay", "Failed to present minimized offer overlay: ${e.message}", e)
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

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (ctx, wm) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val disp = dm.getDisplay(Display.DEFAULT_DISPLAY)
                val winCtx = createDisplayContext(disp).createWindowContext(overlayType, null)
                val winWm = winCtx.getSystemService(WindowManager::class.java)
                ContextThemeWrapper(winCtx, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar) to winWm
            } catch (e: Exception) {
                Log.w("AlistoOfferOverlay", "WindowContext fallback: ${e.message}")
                val fallbackWm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar) to fallbackWm
            }
        } else {
            val fallbackWm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar) to fallbackWm
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            offerWakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "alisto:offer_overlay_wake"
            )?.apply { acquire(25_000L) }
        } catch (_: Exception) {}

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
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
        val deadlineMs = ContractorOfferBridge.resolveServerDeadlineMs(envelope)
        val remInitMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
        val secsInit = ((remInitMs + 999L) / 1000L).toInt().coerceIn(1, 30)
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
            activeOverlayWm = wm
            wm.addView(backdrop, params)
            floatingOverlayView = backdrop
            val startRemMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(1_000L)
            floatingTimer = object : CountDownTimer(startRemMs, 100L) {
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
        } catch (e: Exception) {
            Log.e("AlistoOfferOverlay", "wm.addView failed: ${e.message}", e)
            activeOverlayWm = null
            isDemoOverlayActive = false
            ContractorOfferBridge.activeBookingId = null
            Toast.makeText(this, "Overlay blocked by OS (${e.javaClass.simpleName}) — use heads-up alert.", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleNotificationActionDecision(bid: String, decision: String) {
        if (bid == "DEMO_OVERLAY_TEST") {
            isDemoOverlayActive = false
            ContractorOfferBridge.activeBookingId = null
            ContractorSoundEngine.stopIncomingOfferAlarm()
            cancelOfferNotification(this)
            dismissFloatingOverlay()
            Toast.makeText(this, "Demo overlay $decision verified!", Toast.LENGTH_SHORT).show()
            return
        }
        val dummyHint = TextView(this)
        submitFloatingDecision(bid, decision, dummyHint)
    }

    private fun submitFloatingDecision(bid: String, decision: String, statusHint: TextView) {
        if (floatingDecisionInFlight) return
        floatingDecisionInFlight = true
        floatingTimer?.cancel()
        ContractorSoundEngine.stopIncomingOfferAlarm()
        cancelOfferNotification(this)

        if (bid == "DEMO_OVERLAY_TEST" || isDemoOverlayActive) {
            isDemoOverlayActive = false
            ContractorOfferBridge.activeBookingId = null
            dismissFloatingOverlay()
            Toast.makeText(this, "Minimized overlay self-test ($decision) passed!", Toast.LENGTH_SHORT).show()
            return
        }

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
                    val wm = activeOverlayWm ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    wm.removeViewImmediate(v)
                } catch (_: Exception) {}
            }
            floatingOverlayView = null
            activeOverlayWm = null
            try {
                if (offerWakeLock?.isHeld == true) offerWakeLock?.release()
            } catch (_: Exception) {}
            offerWakeLock = null
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
