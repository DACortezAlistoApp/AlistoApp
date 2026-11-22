package ph.alisto.customer

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.launch
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.AlistoUiKit
import ph.alisto.kernel.AlistoUiKit.dp
import ph.alisto.kernel.BookingRecord
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

object CustomerDoorbellEngine {
    private const val SAMPLE_RATE = 24000

    /**
     * Synthesizes a clear, warm two-tone doorbell chime ("Ding-Dong") so the customer
     * hears immediately when their contractor taps Arrived at Pickup.
     */
    fun playArrivalDoorbell(context: Context) {
        val appCtx = context.applicationContext
        vibrateDoublePulse(appCtx)
        thread(isDaemon = true, name = "AlistoDoorbell") {
            val ding = buildBellNote(880.0, 1760.0, 380, 0.92)
            val gap = ShortArray((SAMPLE_RATE * 0.05).toInt())
            val dong = buildBellNote(659.25, 1318.51, 620, 0.94)
            val pcm = ShortArray(ding.size + gap.size + dong.size)
            System.arraycopy(ding, 0, pcm, 0, ding.size)
            System.arraycopy(gap, 0, pcm, ding.size, gap.size)
            System.arraycopy(dong, 0, pcm, ding.size + gap.size, dong.size)

            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(pcm.size * 2)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(minBuf)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((pcm.size * 1000L) / SAMPLE_RATE + 80L)
            } catch (_: Exception) {
            } finally {
                try { track?.stop(); track?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun buildBellNote(f1: Double, f2: Double, durMs: Int, gain: Double): ShortArray {
        val n = (SAMPLE_RATE * durMs) / 1000
        val out = ShortArray(n)
        val attack = (SAMPLE_RATE * 0.01).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE.toDouble()
            val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-4.2 * t)
            val wave = 0.72 * sin(2.0 * PI * f1 * t) + 0.28 * sin(2.0 * PI * f2 * t)
            out[i] = (wave * env * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun vibrateDoublePulse(context: Context) {
        try {
            val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                val pattern = longArrayOf(0, 180, 120, 260)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {}
    }
}

internal data class WaitingTimerSnapshot(
    val isWaitingActive: Boolean,
    val freeSecondsLeft: Long,
    val billableMinutes: Int,
    val runningFeePhp: Double,
    val label: String
)

internal fun computeLiveWaitingSnapshot(b: BookingRecord): WaitingTimerSnapshot {
    val rules = AlistoKernelClient.currentPricingRules
    val freeSec = rules.freeWaitMinutes * 60L
    if (b.status != "ARRIVED" || b.arrivedAt.isNullOrBlank()) {
        return WaitingTimerSnapshot(false, freeSec, b.waitingBillableMinutes, b.waitingFeePhp, "")
    }
    return try {
        val arrived = Instant.parse(b.arrivedAt)
        val elapsedSec = Duration.between(arrived, Instant.now()).seconds.coerceAtLeast(0L)
        if (elapsedSec <= freeSec) {
            val rem = (freeSec - elapsedSec).coerceAtLeast(0L)
            val mm = rem / 60L
            val ss = rem % 60L
            WaitingTimerSnapshot(
                true, rem, 0, 0.0,
                "Free Waiting Window: %02d:%02d remaining (PHP %.2f/min after)".format(mm, ss, rules.waitingRatePerMinPhp)
            )
        } else {
            val overSec = elapsedSec - freeSec
            val billMins = ceil(overSec / 60.0).toInt().coerceAtLeast(1)
            val fee = billMins * rules.waitingRatePerMinPhp
            val mm = overSec / 60L
            val ss = overSec % 60L
            WaitingTimerSnapshot(
                true, 0L, billMins, fee,
                "Paid Waiting Active (+%02d:%02d over free %dm) • +PHP %.2f".format(mm, ss, rules.freeWaitMinutes, fee)
            )
        }
    } catch (_: Exception) {
        WaitingTimerSnapshot(true, freeSec, 0, 0.0, "Free ${rules.freeWaitMinutes}-minute waiting window active")
    }
}

internal fun CustomerMainActivity.showBroadcastChargesConfirmation(
    serviceTitle: String,
    fee: Double,
    tip: Double,
    paymentMethod: String,
    onAccept: () -> Unit
) {
    val rules = AlistoKernelClient.currentPricingRules
    val total = fee + tip
    val payLabel = if (paymentMethod == "WALLET_CREDITS") "Alisto Wallet Credits (Prepaid)" else "Cash on Hand"

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(10))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    box.addView(TextView(this).apply {
        text = "PHP ${"%.2f".format(total)}"
        textSize = 26f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
    })
    box.addView(TextView(this).apply {
        text = "$serviceTitle\nBase / Quoted Fare: PHP ${"%.2f".format(fee)}\nTip (100% to Pro): PHP ${"%.2f".format(tip)}\nPayment Method: $payLabel"
        textSize = 13f; setTextColor(Color.parseColor("#E2E8F0")); setPadding(0, dp(8), 0, dp(12))
    })
    box.addView(TextView(this).apply {
        text = "Waiting Time Policy:\nYour booking includes a free ${rules.freeWaitMinutes}-minute waiting window once your contractor arrives at pickup. Waiting time exceeding ${rules.freeWaitMinutes} minutes will automatically incur PHP ${"%.2f".format(rules.waitingRatePerMinPhp)} per minute on your final bill."
        textSize = 12.5f; setTextColor(Color.parseColor("#FDE68A"))
        background = AlistoUiKit.roundedBg(this@showBroadcastChargesConfirmation, "#2A1B08", 10, "#92400E")
        setPadding(dp(12), dp(10), dp(12), dp(10))
    })

    AlertDialog.Builder(this)
        .setTitle("Confirm Booking Charges")
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Accept & Broadcast") { _, _ -> onAccept() }
        .show()
}

internal fun CustomerMainActivity.showAssignedContractorProfileModal(b: BookingRecord) {
    val proName = b.contractorFullName ?: "Assigned Contractor"
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(10))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }

    val topRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }
    val bmp = try {
        val raw = b.contractorProfilePhotoUrl.orEmpty()
        if (raw.startsWith("data:image") && raw.contains(",")) {
            val bytes = Base64.decode(raw.substringAfter(","), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else null
    } catch (_: Exception) { null }

    if (bmp != null) {
        topRow.addView(ImageView(this).apply {
            setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(12) }
        })
    } else {
        topRow.addView(TextView(this).apply {
            text = proName.take(1).uppercase(); gravity = Gravity.CENTER; textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.BG_DEEP))
            background = AlistoUiKit.roundedBg(this@showAssignedContractorProfileModal, AlistoUiKit.GOLD_ACCENT, 29)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { rightMargin = dp(12) }
        })
    }

    topRow.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(this@showAssignedContractorProfileModal).apply {
            text = proName + if (b.contractorVipBadge) " • VIP" else ""
            textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        })
        if (b.contractorUsername.isNotBlank()) {
            addView(TextView(this@showAssignedContractorProfileModal).apply {
                text = "@${b.contractorUsername}"
                textSize = 12.5f; setTextColor(Color.parseColor("#7DD3FC"))
            })
        }
        addView(TextView(this@showAssignedContractorProfileModal).apply {
            text = "★ ${"%.2f".format(b.contractorRatingAvg)} (${b.contractorRatingCount} reviews)"
            textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
        })
    })
    box.addView(topRow)

    fun row(k: String, v: String) {
        box.addView(TextView(this).apply {
            text = "$k: $v"
            textSize = 13.5f; setTextColor(Color.parseColor("#E2E8F0")); setPadding(0, dp(4), 0, dp(4))
        })
    }

    val vehDesc = listOfNotNull(b.contractorVehicleColor, b.contractorVehicleMake, b.contractorVehicleModel)
        .filter { it.isNotBlank() }.joinToString(" ")
    val vehType = b.contractorVehicleType?.replace("_", " ")?.replaceFirstChar { it.uppercase() }.orEmpty()
    row("Contact Phone", b.contractorPhone?.ifBlank { null } ?: "In-App Chat Only")
    if (vehDesc.isNotBlank() || vehType.isNotBlank()) {
        row("Vehicle", vehDesc.ifBlank { vehType })
    }
    if (!b.contractorPlateNo.isNullOrBlank()) {
        row("Plate Number", b.contractorPlateNo!!)
    }
    row("Current Status", b.status.replace("_", " "))

    val builder = AlertDialog.Builder(this)
        .setTitle("Contractor Profile")
        .setView(box)
        .setNegativeButton("Close", null)

    if (!b.contractorPhone.isNullOrBlank()) {
        builder.setPositiveButton("Call Contractor") { _, _ ->
            try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${b.contractorPhone}"))) } catch (_: Exception) {}
        }
    }
    builder.show()
}

internal fun CustomerMainActivity.showArrivalDoorbellModal(b: BookingRecord) {
    CustomerDoorbellEngine.playArrivalDoorbell(this)
    val rules = AlistoKernelClient.currentPricingRules
    val proName = b.contractorFullName ?: "Your Alisto Contractor"
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(10))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    box.addView(TextView(this).apply {
        text = "Ding-dong! $proName is right outside your pickup point."
        textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
    })
    box.addView(TextView(this).apply {
        text = "Please head out to meet your contractor now.\n\nYour free ${rules.freeWaitMinutes}-minute waiting window has already started. Waiting time past ${rules.freeWaitMinutes} minutes is billed at PHP ${"%.2f".format(rules.waitingRatePerMinPhp)}/min."
        textSize = 13f; setTextColor(Color.parseColor("#E2E8F0")); setPadding(0, dp(10), 0, dp(4))
    })

    AlertDialog.Builder(this)
        .setTitle("Contractor Arrived • ${b.bookingRef}")
        .setView(box)
        .setCancelable(false)
        .setPositiveButton("I am going out") { _, _ ->
            uiScope.launch {
                AlistoKernelClient.ackCustomerArrivalRpc(b.id)
                Toast.makeText(this@showArrivalDoorbellModal, "Got it — let your pro know you're stepping out!", Toast.LENGTH_SHORT).show()
                showAuthenticatedCustomerPortal()
            }
        }
        .show()
}

internal fun CustomerMainActivity.buildKindnessAndWaitingBanner(b: BookingRecord): View {
    val rules = AlistoKernelClient.currentPricingRules
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    // 1. Warm, encouraging kindness reminder while waiting for pickup
    if (b.status in listOf("CREATED", "PENDING", "SEARCHING", "ACCEPTED", "ON_THE_WAY", "ARRIVED")) {
        wrap.addView(TextView(this).apply {
            text = "A Little Kindness Goes a Long Way — Our Alisto pros brave traffic and weather to serve you safely. A warm greeting, clear instructions, and mutual respect brighten every trip!"
            textSize = 12f; setTextColor(Color.parseColor("#BAE6FD"))
            background = AlistoUiKit.roundedBg(this@buildKindnessAndWaitingBanner, "#082032", 12, "#0EA5E9")
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        })
    }

    // 2. Accepted / On The Way readiness reminder
    if (b.status in listOf("ACCEPTED", "ON_THE_WAY")) {
        wrap.addView(TextView(this).apply {
            text = "Get Ready for Pickup: Once your pro arrives, you have ${rules.freeWaitMinutes} free minutes to meet them. Wait times past ${rules.freeWaitMinutes} minutes incur PHP ${"%.2f".format(rules.waitingRatePerMinPhp)}/min."
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FDE68A"))
            background = AlistoUiKit.roundedBg(this@buildKindnessAndWaitingBanner, "#2A1B08", 12, "#B45309")
            setPadding(dp(12), dp(9), dp(12), dp(9))
        })
    }

    // 3. Live 5-minute waiting countdown once ARRIVED (runs regardless of customer ack)
    if (b.status == "ARRIVED") {
        val snap = computeLiveWaitingSnapshot(b)
        val over = snap.billableMinutes > 0
        val waitBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.roundedBg(
                this@buildKindnessAndWaitingBanner,
                if (over) "#450A0A" else "#064E3B",
                12,
                if (over) "#F43F5E" else "#10B981"
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        waitBox.addView(TextView(this).apply {
            text = snap.label
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (over) "#FDA4AF" else "#6EE7B7"))
        })
        if (b.customerGoingOutAt.isNullOrBlank()) {
            waitBox.addView(
                AlistoUiKit.pillButton(this, "I am going out", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 38, textSizeSp = 12.5f) {
                    uiScope.launch {
                        AlistoKernelClient.ackCustomerArrivalRpc(b.id)
                        Toast.makeText(this@buildKindnessAndWaitingBanner, "Confirmed — heading out to meet your pro!", Toast.LENGTH_SHORT).show()
                        showAuthenticatedCustomerPortal()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                }
            )
        } else {
            waitBox.addView(TextView(this).apply {
                text = "✓ You confirmed you are heading out."
                textSize = 11.5f; setTextColor(Color.parseColor("#D1FAE5")); setPadding(0, dp(4), 0, 0)
            })
        }
        wrap.addView(waitBox)
    }

    return wrap
}
