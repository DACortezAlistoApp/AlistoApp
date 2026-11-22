package ph.alisto.contractor

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.AlistoLocationTracker
import ph.alisto.kernel.AlistoUiKit
import ph.alisto.kernel.AlistoUiKit.dp

object ContractorOfferBridge {
    @Volatile var activeBookingId: String? = null
    @Volatile var lastResultDecision: String? = null
    @Volatile var lastResultBookingId: String? = null
    @Volatile var lastHandledAtMs: Long = 0L
    @Volatile var isMainActivityForeground: Boolean = false

    fun buildIntent(context: Context, offerEnvelope: JSONObject): Intent {
        val offer = offerEnvelope.optJSONObject("offer") ?: JSONObject()
        val secs = offerEnvelope.optInt("seconds_remaining", 25).coerceIn(3, 60)
        return Intent(context, ContractorOfferOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra("booking_id", offer.optString("id", ""))
            putExtra("booking_ref", offer.optString("booking_ref", "ALISTO"))
            putExtra("offer_source", offer.optString("offer_source", "AUTO_ASSIGN"))
            putExtra("vertical_key", offer.optString("vertical_key", "delivery_logistics"))
            putExtra("service_title", offer.optString("service_title", "Dispatch Request"))
            putExtra("pickup_address", offer.optString("pickup_address", ""))
            putExtra("pickup_note", offer.optString("pickup_note", ""))
            putExtra("dropoff_address", offer.optString("dropoff_address", ""))
            putExtra("dropoff_note", offer.optString("dropoff_note", ""))
            putExtra("booking_notes", offer.optString("booking_notes", ""))
            putExtra("municipality", offer.optString("municipality", ""))
            putExtra("barangay_zone", offer.optString("barangay_zone", ""))
            putExtra("quoted_fee", offer.optDouble("quoted_fee", 0.0))
            putExtra("tip_amount", offer.optDouble("tip_amount", 0.0))
            putExtra("payment_method", offer.optString("payment_method", "CASH"))
            putExtra("client_nickname", offer.optString("client_nickname", "Customer"))
            putExtra("client_trust_score", offer.optDouble("client_trust_score", 100.0))
            putExtra("pickup_lat", offer.optDouble("pickup_lat", 0.0))
            putExtra("pickup_lng", offer.optDouble("pickup_lng", 0.0))
            putExtra("dropoff_lat", offer.optDouble("dropoff_lat", 0.0))
            putExtra("dropoff_lng", offer.optDouble("dropoff_lng", 0.0))
            putExtra("actual_distance_km", offer.optDouble("actual_distance_km", -1.0))
            putExtra("seconds_remaining", secs)
        }
    }
}

class ContractorOfferOverlayActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countDownTimer: CountDownTimer? = null
    private var decisionInFlight = false
    private var bookingId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlistoUiKit.applyDarkSystemBars(this)
        wakeScreenForOffer()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@ContractorOfferOverlayActivity, "Tap Accept or Reject to respond.", Toast.LENGTH_SHORT).show()
            }
        })

        renderOfferFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderOfferFromIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenForOffer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun computeRoadDistanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double? {
        if (lat1 == 0.0 && lng1 == 0.0) return null
        if (lat2 == 0.0 && lng2 == 0.0) return null
        val res = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, res)
        val straightKm = res[0].toDouble() / 1000.0
        if (straightKm < 0.02) return null
        return straightKm * 1.25
    }

    private fun formatKm(km: Double): String {
        return if (km < 1.0) "${(km * 1000).toInt()} m" else "${"%.1f".format(km)} km"
    }

    private fun renderOfferFromIntent(srcIntent: Intent) {
        countDownTimer?.cancel()
        decisionInFlight = false

        bookingId = srcIntent.getStringExtra("booking_id").orEmpty()
        if (bookingId.isBlank()) {
            finish()
            return
        }
        ContractorOfferBridge.activeBookingId = bookingId
        ContractorSoundEngine.startIncomingOfferAlarm(this)

        val bookingRef = srcIntent.getStringExtra("booking_ref").orEmpty()
        val offerSource = srcIntent.getStringExtra("offer_source").orEmpty()
        val verticalKey = srcIntent.getStringExtra("vertical_key").orEmpty()
        val serviceTitle = srcIntent.getStringExtra("service_title").orEmpty()
        val pickupAddress = srcIntent.getStringExtra("pickup_address").orEmpty()
        val pickupNote = srcIntent.getStringExtra("pickup_note").orEmpty()
        val dropoffAddress = srcIntent.getStringExtra("dropoff_address").orEmpty()
        val dropoffNote = srcIntent.getStringExtra("dropoff_note").orEmpty()
        val bookingNotes = srcIntent.getStringExtra("booking_notes").orEmpty()
        val municipality = srcIntent.getStringExtra("municipality").orEmpty()
        val barangayZone = srcIntent.getStringExtra("barangay_zone").orEmpty()
        val quotedFee = srcIntent.getDoubleExtra("quoted_fee", 0.0)
        val tipAmount = srcIntent.getDoubleExtra("tip_amount", 0.0)
        val paymentMethod = srcIntent.getStringExtra("payment_method").orEmpty().ifBlank { "CASH" }
        val clientNickname = srcIntent.getStringExtra("client_nickname").orEmpty().ifBlank { "Customer" }
        val clientTrust = srcIntent.getDoubleExtra("client_trust_score", 100.0)
        val pickupLat = srcIntent.getDoubleExtra("pickup_lat", 0.0)
        val pickupLng = srcIntent.getDoubleExtra("pickup_lng", 0.0)
        val dropoffLat = srcIntent.getDoubleExtra("dropoff_lat", 0.0)
        val dropoffLng = srcIntent.getDoubleExtra("dropoff_lng", 0.0)
        val recordedDistKm = srcIntent.getDoubleExtra("actual_distance_km", -1.0)
        val secondsRemaining = srcIntent.getIntExtra("seconds_remaining", 25).coerceIn(3, 60)

        val tripDistKm = if (recordedDistKm > 0.0) recordedDistKm else computeRoadDistanceKm(pickupLat, pickupLng, dropoffLat, dropoffLng)
        val myLat = AlistoLocationTracker.lastLat
        val myLng = AlistoLocationTracker.lastLng
        val toPickupKm = if (myLat != null && myLng != null) computeRoadDistanceKm(myLat, myLng, pickupLat, pickupLng) else null

        val dm = resources.displayMetrics
        val swDp = dm.widthPixels / dm.density
        val shDp = dm.heightPixels / dm.density
        val compact = swDp < 370f || shDp < 680f
        val hPad = dp(if (compact) 14 else 18)
        val vPad = dp(if (compact) 12 else 18)

        val isBoardAward = offerSource.equals("BOARD_REQUEST", ignoreCase = true)
        val headerTag = if (isBoardAward) "BOOKING AWARDED" else "AUTO-ASSIGNED OFFER"
        val headerAccent = if (isBoardAward) AlistoUiKit.EMERALD else AlistoUiKit.GOLD_ACCENT
        val verticalLabel = when (verticalKey) {
            "delivery_logistics" -> "DELIVERY"
            "ride_hailing" -> "LOCAL RIDE"
            else -> "SKILLS & ERRANDS"
        }

        // Full-height responsive container with safe edge-to-edge inset padding
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#040914"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(hPad, bars.top + vPad, hPad, bars.bottom + vPad)
            insets
        }

        // 1. Pinned Top Urgency & Countdown Bar
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        topRow.addView(AlistoUiKit.badgePill(this, headerTag, "#0F294A", headerAccent))
        topRow.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        val timerBadge = TextView(this).apply {
            text = "${secondsRemaining}s"
            textSize = if (compact) 16f else 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FEF08A"))
            background = AlistoUiKit.roundedBg(this@ContractorOfferOverlayActivity, "#451A03", 14, AlistoUiKit.GOLD_ACCENT)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        topRow.addView(timerBadge)
        root.addView(topRow)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = secondsRemaining * 10
            this.progress = secondsRemaining * 10
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
            ).apply { bottomMargin = dp(10) }
        }
        root.addView(progress)

        // 2. Scrollable Middle Details Card (adapts cleanly to any screen height)
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { bottomMargin = dp(10) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Hero Payout + Route Distance Card
        val hero = AlistoUiKit.surfaceCard(this, "#0B1D39", headerAccent, padDp = if (compact) 12 else 16)
        hero.addView(TextView(this).apply {
            text = "$verticalLabel  •  $bookingRef  •  $paymentMethod"
            textSize = if (compact) 11f else 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD"))
        })
        hero.addView(TextView(this).apply {
            text = "PHP ${"%.2f".format(quotedFee + tipAmount)}"
            textSize = if (compact) 28f else 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
            setPadding(0, dp(2), 0, dp(2))
        })
        hero.addView(TextView(this).apply {
            text = if (tipAmount > 0.0) {
                "Fare: PHP ${"%.2f".format(quotedFee)} + PHP ${"%.2f".format(tipAmount)} Tip (100% yours)"
            } else {
                "Quoted Fare: PHP ${"%.2f".format(quotedFee)}"
            }
            textSize = if (compact) 11.5f else 12.5f
            setTextColor(Color.parseColor("#CBD5E1"))
        })

        // Distance Strip (Pickup -> Drop-Off + Distance to Pickup)
        val distRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        if (tripDistKm != null) {
            distRow.addView(
                AlistoUiKit.badgePill(this, "Route: ${formatKm(tripDistKm)} (Pickup → Drop-Off)", "#064E3B", "#6EE7B7").apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
                }
            )
        }
        if (toPickupKm != null) {
            distRow.addView(AlistoUiKit.badgePill(this, "Pickup: ${formatKm(toPickupKm)} away", "#1E293B", "#7DD3FC"))
        }
        if (distRow.childCount > 0) hero.addView(distRow)
        body.addView(hero)

        // Route & Customer Details Card
        val detailCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = if (compact) 12 else 16)
        detailCard.addView(TextView(this).apply {
            text = serviceTitle
            textSize = if (compact) 15.5f else 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            setPadding(0, 0, 0, dp(8))
        })
        detailCard.addView(TextView(this).apply {
            text = "PICKUP"
            textSize = 10.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
        })
        detailCard.addView(TextView(this).apply {
            text = buildString {
                append(pickupAddress.ifBlank { "$barangayZone, $municipality" })
                if (pickupNote.isNotBlank()) append("\nNote: $pickupNote")
            }
            textSize = if (compact) 12.5f else 13.5f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            setPadding(0, dp(2), 0, dp(8))
        })
        if (dropoffAddress.isNotBlank() && dropoffAddress != pickupAddress) {
            detailCard.addView(TextView(this).apply {
                text = "DROP-OFF" + if (tripDistKm != null) "  •  ${formatKm(tripDistKm)} TRIP" else ""
                textSize = 10.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FB7185"))
            })
            detailCard.addView(TextView(this).apply {
                text = buildString {
                    append(dropoffAddress)
                    if (dropoffNote.isNotBlank()) append("\nNote: $dropoffNote")
                }
                textSize = if (compact) 12.5f else 13.5f
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                setPadding(0, dp(2), 0, dp(8))
            })
        }
        detailCard.addView(TextView(this).apply {
            text = "Customer: $clientNickname  •  Trust ${"%.0f".format(clientTrust)}%"
            textSize = if (compact) 12f else 12.5f
            setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
        })
        if (bookingNotes.isNotBlank()) {
            detailCard.addView(TextView(this).apply {
                text = "Booking Note: $bookingNotes"
                textSize = 12f
                setTextColor(Color.parseColor("#FDE68A"))
                setPadding(0, dp(6), 0, 0)
            })
        }
        body.addView(detailCard)
        scroll.addView(body)
        root.addView(scroll)

        // 3. Pinned Bottom Action Dock (Always Visible on Every Screen Dimension)
        val statusHint = TextView(this).apply {
            text = "Respond before timer reaches 0s — expired offers auto-forward."
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusHint)

        val btnHeight = if (compact) 48 else 54
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val rejectBtn = AlistoUiKit.pillButton(
            this, "REJECT", "#450A0A", "#FCA5A5", "#EF4444", heightDp = btnHeight, textSizeSp = if (compact) 14f else 15f
        ) {
            submitDecision("REJECT", statusHint)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply { rightMargin = dp(8) }
        }
        val acceptBtn = AlistoUiKit.pillButton(
            this, "ACCEPT BOOKING", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, "#6EE7B7", heightDp = btnHeight, textSizeSp = if (compact) 14.5f else 15.5f
        ) {
            submitDecision("ACCEPT", statusHint)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        btnRow.addView(rejectBtn)
        btnRow.addView(acceptBtn)
        root.addView(btnRow)

        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        countDownTimer = object : CountDownTimer(secondsRemaining * 1000L, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                val secs = ((millisUntilFinished + 999L) / 1000L).toInt().coerceAtLeast(0)
                timerBadge.text = "${secs}s"
                progress.progress = (millisUntilFinished / 100L).toInt()
            }

            override fun onFinish() {
                timerBadge.text = "0s"
                progress.progress = 0
                submitDecision("TIMEOUT", statusHint)
            }
        }.start()
    }

    private fun submitDecision(decision: String, statusHint: TextView) {
        if (decisionInFlight) return
        decisionInFlight = true
        countDownTimer?.cancel()
        ContractorSoundEngine.stopIncomingOfferAlarm()
        ContractorDispatchMonitorService.cancelOfferNotification(this)

        statusHint.text = when (decision) {
            "ACCEPT" -> "Accepting booking & locking dispatch..."
            "REJECT" -> "Declining & forwarding to next pro..."
            else -> "Offer timed out — forwarding..."
        }

        val token = AlistoKernelClient.currentSessionToken
            ?: getSharedPreferences(ContractorDispatchMonitorService.PREFS_NAME, MODE_PRIVATE)
                .getString(ContractorDispatchMonitorService.KEY_SESSION_TOKEN, null)

        uiScope.launch {
            val res = AlistoKernelClient.respondBookingOfferRpc(bookingId, decision, token)
            ContractorOfferBridge.lastResultDecision = decision
            ContractorOfferBridge.lastResultBookingId = bookingId
            ContractorOfferBridge.lastHandledAtMs = System.currentTimeMillis()
            ContractorOfferBridge.activeBookingId = null

            res.onSuccess {
                when (decision) {
                    "ACCEPT" -> ContractorSoundEngine.playAcceptedTone(this@ContractorOfferOverlayActivity)
                    else -> ContractorSoundEngine.playRejectedTone(this@ContractorOfferOverlayActivity)
                }
                val msg = when (decision) {
                    "ACCEPT" -> "Booking accepted! Opening Active Booking tab."
                    "REJECT" -> "Booking declined and forwarded."
                    else -> "Offer timer lapsed and forwarded."
                }
                Toast.makeText(this@ContractorOfferOverlayActivity, msg, Toast.LENGTH_LONG).show()
                if (decision == "ACCEPT") {
                    startActivity(Intent(this@ContractorOfferOverlayActivity, ContractorMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("open_tab", "ACTIVE")
                    })
                }
            }.onFailure { err ->
                Toast.makeText(this@ContractorOfferOverlayActivity, err.message ?: "Offer response could not be completed.", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        ContractorSoundEngine.stopIncomingOfferAlarm()
        ContractorDispatchMonitorService.cancelOfferNotification(this)
        if (ContractorOfferBridge.activeBookingId == bookingId) {
            ContractorOfferBridge.activeBookingId = null
        }
        uiScope.cancel()
    }
}
