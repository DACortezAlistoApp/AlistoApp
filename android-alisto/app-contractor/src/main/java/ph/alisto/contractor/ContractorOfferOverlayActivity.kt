package ph.alisto.contractor

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.AlistoUiKit
import ph.alisto.kernel.AlistoUiKit.dp

object ContractorOfferBridge {
    @Volatile var activeBookingId: String? = null
    @Volatile var lastResultDecision: String? = null
    @Volatile var lastResultBookingId: String? = null
    @Volatile var lastHandledAtMs: Long = 0L

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
            putExtra("seconds_remaining", secs)
        }
    }
}

class ContractorOfferOverlayActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var countDownTimer: CountDownTimer? = null
    private var ringtone: Ringtone? = null
    private var decisionInFlight = false
    private var bookingId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlistoUiKit.applyDarkSystemBars(this)
        wakeScreenForOffer()

        // Block accidental back-button dismissal while countdown is active
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

    @Suppress("DEPRECATION")
    private fun playOfferAlert() {
        stopOfferAlert()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)?.apply { play() }
        } catch (_: Exception) {}

        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                val pattern = longArrayOf(0, 350, 200, 350, 200, 450)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }

    private fun stopOfferAlert() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
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
        playOfferAlert()

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
        val secondsRemaining = srcIntent.getIntExtra("seconds_remaining", 25).coerceIn(3, 60)

        val isBoardAward = offerSource.equals("BOARD_REQUEST", ignoreCase = true)
        val headerTag = if (isBoardAward) "BOOKING AWARDED TO YOU" else "AUTO-ASSIGNED DISPATCH"
        val headerAccent = if (isBoardAward) AlistoUiKit.EMERALD else AlistoUiKit.GOLD_ACCENT
        val verticalLabel = when (verticalKey) {
            "delivery_logistics" -> "DELIVERY"
            "ride_hailing" -> "LOCAL RIDE"
            else -> "SKILLS & ERRANDS"
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#040914"))
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }

        // Top urgency header & countdown pill
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        topRow.addView(AlistoUiKit.badgePill(this, headerTag, "#0F294A", headerAccent).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        topRow.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        val timerBadge = TextView(this).apply {
            text = "${secondsRemaining}s"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FEF08A"))
            background = AlistoUiKit.roundedBg(this@ContractorOfferOverlayActivity, "#1E293B", 14, AlistoUiKit.GOLD_ACCENT)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        topRow.addView(timerBadge)
        root.addView(topRow)

        val timerBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = secondsRemaining * 100
            progress = secondsRemaining * 100
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { bottomMargin = dp(16) }
        }
        root.addView(timerBar)

        // Scrollable booking detail sheet
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Hero payout card
        val payoutCard = AlistoUiKit.surfaceCard(this, "#0B1D39", headerAccent, padDp = 18)
        payoutCard.addView(TextView(this).apply {
            text = "$verticalLabel  •  $bookingRef  •  $paymentMethod"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#93C5FD"))
        })
        payoutCard.addView(TextView(this).apply {
            text = "PHP ${"%.2f".format(quotedFee)}"
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
            setPadding(0, dp(4), 0, dp(2))
        })
        if (tipAmount > 0.0) {
            payoutCard.addView(TextView(this).apply {
                text = "+ PHP ${"%.2f".format(tipAmount)} Customer Tip Included"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
                setPadding(0, 0, 0, dp(6))
            })
        }
        payoutCard.addView(TextView(this).apply {
            text = serviceTitle
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(payoutCard)

        // Route & Customer details card
        val routeCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER_LIGHT, padDp = 16)
        routeCard.addView(TextView(this).apply {
            text = "PICKUP LOCATION"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
        })
        routeCard.addView(TextView(this).apply {
            text = buildString {
                append(pickupAddress.ifBlank { "$barangayZone, $municipality" })
                if (pickupNote.isNotBlank()) append("\nNote: $pickupNote")
            }
            textSize = 14f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            setPadding(0, dp(3), 0, dp(12))
        })

        if (dropoffAddress.isNotBlank() && dropoffAddress != pickupAddress) {
            routeCard.addView(TextView(this).apply {
                text = "DROP-OFF DESTINATION"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#FB7185"))
            })
            routeCard.addView(TextView(this).apply {
                text = buildString {
                    append(dropoffAddress)
                    if (dropoffNote.isNotBlank()) append("\nNote: $dropoffNote")
                }
                textSize = 14f
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                setPadding(0, dp(3), 0, dp(12))
            })
        }

        if (bookingNotes.isNotBlank()) {
            routeCard.addView(TextView(this).apply {
                text = "Customer Note: $bookingNotes"
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#FDE68A"))
                background = AlistoUiKit.roundedBg(this@ContractorOfferOverlayActivity, "#2A1B08", 10, "#92400E")
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            })
        }

        routeCard.addView(TextView(this).apply {
            text = "Customer: $clientNickname  •  Trust Score ${"%.0f".format(clientTrust)}%"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
        })
        content.addView(routeCard)

        content.addView(TextView(this).apply {
            text = "Respond before the timer ends. Unanswered offers automatically forward to the next available contractor."
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            setPadding(dp(8), dp(4), dp(8), dp(12))
        })

        scroll.addView(content)
        root.addView(scroll)

        // Bottom dominant Accept / Reject action bar
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        actionBar.addView(
            AlistoUiKit.pillButton(
                this, "REJECT", "#450A0A", "#FCA5A5", "#EF4444", heightDp = 56, textSizeSp = 15f
            ) { submitOfferDecision("REJECT") }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply { rightMargin = dp(10) }
            }
        )
        actionBar.addView(
            AlistoUiKit.pillButton(
                this, "ACCEPT BOOKING", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, "#6EE7B7", heightDp = 56, textSizeSp = 16f
            ) { submitOfferDecision("ACCEPT") }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
            }
        )
        root.addView(actionBar)
        setContentView(root)

        val totalMs = secondsRemaining * 1000L
        countDownTimer = object : CountDownTimer(totalMs, 100L) {
            override fun onTick(millisUntilFinished: Long) {
                val secsLeft = ((millisUntilFinished + 999L) / 1000L).toInt().coerceAtLeast(1)
                timerBadge.text = "${secsLeft}s"
                timerBar.progress = (millisUntilFinished / 10L).toInt().coerceAtLeast(0)
                if (secsLeft <= 7) {
                    timerBadge.setTextColor(Color.parseColor("#FCA5A5"))
                }
            }

            override fun onFinish() {
                timerBadge.text = "0s"
                timerBar.progress = 0
                submitOfferDecision("TIMEOUT")
            }
        }.start()
    }

    private fun submitOfferDecision(decision: String) {
        if (decisionInFlight) return
        decisionInFlight = true
        countDownTimer?.cancel()
        stopOfferAlert()

        val savedToken = getSharedPreferences(ContractorDispatchMonitorService.PREFS_NAME, MODE_PRIVATE)
            .getString(ContractorDispatchMonitorService.KEY_SESSION_TOKEN, null)

        uiScope.launch {
            val res = AlistoKernelClient.respondBookingOfferRpc(bookingId, decision, savedToken)
            ContractorOfferBridge.activeBookingId = null
            ContractorOfferBridge.lastResultDecision = decision
            ContractorOfferBridge.lastResultBookingId = bookingId
            ContractorOfferBridge.lastHandledAtMs = System.currentTimeMillis()

            res.onSuccess {
                val msg = when (decision) {
                    "ACCEPT" -> "Booking accepted! Navigating to Active Job."
                    "REJECT" -> "Booking declined and forwarded."
                    else -> "Offer timer lapsed and forwarded."
                }
                Toast.makeText(this@ContractorOfferOverlayActivity, msg, Toast.LENGTH_LONG).show()
                if (decision == "ACCEPT") {
                    startActivity(Intent(this@ContractorOfferOverlayActivity, ContractorMainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("open_tab", "JOBS")
                        putExtra("open_jobs_filter", "MINE")
                    })
                }
            }.onFailure { err ->
                Toast.makeText(this@ContractorOfferOverlayActivity, err.message ?: "Offer closed.", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        stopOfferAlert()
        if (ContractorOfferBridge.activeBookingId == bookingId) {
            ContractorOfferBridge.activeBookingId = null
        }
        uiScope.cancel()
    }
}
