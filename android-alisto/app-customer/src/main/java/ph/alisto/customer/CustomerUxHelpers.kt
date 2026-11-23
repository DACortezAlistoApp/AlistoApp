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
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ph.alisto.kernel.AlistoChatEngine
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.AlistoUiKit
import ph.alisto.kernel.AlistoUiKit.dp
import ph.alisto.kernel.BookingRecord
import ph.alisto.kernel.ChatMessage
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin

object CustomerDoorbellEngine {
    private const val SAMPLE_RATE = 24000

    fun playArrivalDoorbell(context: Context) {
        val appCtx = context.applicationContext
        vibrateDoublePulse(appCtx)
        playToneSequence(
            listOf(Triple(880.0, 1760.0, 380), Triple(0.0, 0.0, 50), Triple(659.25, 1318.51, 620)),
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        )
    }

    fun playIncomingChatChime(context: Context) {
        playToneSequence(
            listOf(Triple(784.0, 1568.0, 110), Triple(0.0, 0.0, 35), Triple(1046.5, 2093.0, 160)),
            AudioAttributes.USAGE_NOTIFICATION
        )
    }

    private fun playToneSequence(notes: List<Triple<Double, Double, Int>>, usage: Int) {
        thread(isDaemon = true, name = "AlistoCustomerTone") {
            val chunks = notes.map { (f1, f2, ms) ->
                if (f1 <= 0.0) ShortArray((SAMPLE_RATE * ms) / 1000)
                else buildBellNote(f1, f2, ms, 0.90)
            }
            val total = chunks.sumOf { it.size }
            val pcm = ShortArray(total)
            var off = 0
            chunks.forEach { c -> System.arraycopy(c, 0, pcm, off, c.size); off += c.size }

            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(pcm.size * 2)
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
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
    if (b.status != "ARRIVED") {
        return WaitingTimerSnapshot(false, freeSec, b.waitingBillableMinutes, b.waitingFeePhp, "")
    }
    val arrived = AlistoKernelClient.parseServerInstant(b.arrivedAt) ?: Instant.now()
    val elapsedSec = Duration.between(arrived, Instant.now()).seconds.coerceAtLeast(0L)
    return if (elapsedSec <= freeSec) {
        val rem = (freeSec - elapsedSec).coerceAtLeast(0L)
        val mm = rem / 60L
        val ss = rem % 60L
        WaitingTimerSnapshot(
            true, rem, 0, 0.0,
            "Free Waiting Timer: %02d:%02d remaining (PHP %.2f/min after)".format(mm, ss, rules.waitingRatePerMinPhp)
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
        text = "$serviceTitle\nBase / Quoted Fare: PHP${"%.2f".format(fee)}\nTip (100% to Pro): PHP ${"%.2f".format(tip)}\nPayment Method: $payLabel"
        textSize = 13f; setTextColor(Color.parseColor("#E2E8F0")); setPadding(0, dp(8), 0, dp(12))
    })
    box.addView(TextView(this).apply {
        text = "Waiting Time Policy:\nYour booking includes a free ${rules.freeWaitMinutes}-minute waiting window once your contractor arrives at pickup. Waiting time exceeding ${rules.freeWaitMinutes} minutes will automatically incur PHP${"%.2f".format(rules.waitingRatePerMinPhp)} per minute on your final bill."
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
            text = "$k:$v"
            textSize = 13.5f; setTextColor(Color.parseColor("#E2E8F0")); setPadding(0, dp(4), 0, dp(4))
        })
    }

    val vehDesc = listOfNotNull(b.contractorVehicleColor, b.contractorVehicleMake, b.contractorVehicleModel)
        .filter { it.isNotBlank() }.joinToString(" ")
    val vehType = b.contractorVehicleType?.replace("_", " ")?.replaceFirstChar { it.uppercase() }.orEmpty()
    row("Contact Phone", b.contractorPhone?.ifBlank { null } ?: "In-App Chat Only")
    if (vehDesc.isNotBlank() || vehType.isNotBlank()) row("Vehicle", vehDesc.ifBlank { vehType })
    if (!b.contractorPlateNo.isNullOrBlank()) row("Plate Number", b.contractorPlateNo!!)
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
        text = "Please head out to meet your contractor now.\n\nYour free ${rules.freeWaitMinutes}-minute waiting window has already started. Waiting time past ${rules.freeWaitMinutes} minutes is billed at PHP${"%.2f".format(rules.waitingRatePerMinPhp)}/min."
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

internal fun CustomerMainActivity.buildKindnessAndWaitingBanner(b: BookingRecord, compactForMap: Boolean = false): View {
    val rules = AlistoKernelClient.currentPricingRules
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(if (compactForMap) 4 else 10) }
    }

    // 1. Live 1-second ticking 5-minute waiting countdown once ARRIVED (always visible even in compact map mode)
    if (b.status == "ARRIVED") {
        val snap0 = computeLiveWaitingSnapshot(b)
        val over0 = snap0.billableMinutes > 0
        val waitBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.roundedBg(
                this@buildKindnessAndWaitingBanner,
                if (over0) "#450A0A" else "#064E3B",
                12,
                if (over0) "#F43F5E" else "#10B981"
            )
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(if (compactForMap) 0 else 8) }
        }
        val timerTv = TextView(this).apply {
            text = snap0.label
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (over0) "#FDA4AF" else "#6EE7B7"))
        }
        waitBox.addView(timerTv)

        val tickHandler = Handler(Looper.getMainLooper())
        val ticker = object : Runnable {
            override fun run() {
                if (!waitBox.isAttachedToWindow) return
                val cur = computeLiveWaitingSnapshot(b)
                val isOver = cur.billableMinutes > 0
                timerTv.text = cur.label
                timerTv.setTextColor(Color.parseColor(if (isOver) "#FDA4AF" else "#6EE7B7"))
                waitBox.background = AlistoUiKit.roundedBg(
                    this@buildKindnessAndWaitingBanner,
                    if (isOver) "#450A0A" else "#064E3B",
                    12,
                    if (isOver) "#F43F5E" else "#10B981"
                )
                tickHandler.postDelayed(this, 1_000L)
            }
        }
        waitBox.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { tickHandler.postDelayed(ticker, 1_000L) }
            override fun onViewDetachedFromWindow(v: View) { tickHandler.removeCallbacks(ticker) }
        })

        if (b.customerGoingOutAt.isNullOrBlank()) {
            waitBox.addView(
                AlistoUiKit.pillButton(this, "I am going out", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 36, textSizeSp = 12f) {
                    uiScope.launch {
                        AlistoKernelClient.ackCustomerArrivalRpc(b.id)
                        Toast.makeText(this@buildKindnessAndWaitingBanner, "Confirmed — heading out to meet your pro!", Toast.LENGTH_SHORT).show()
                        showAuthenticatedCustomerPortal()
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
                }
            )
        } else {
            waitBox.addView(TextView(this).apply {
                text = "✓ You confirmed you are heading out."
                textSize = 11.5f; setTextColor(Color.parseColor("#D1FAE5")); setPadding(0, dp(3), 0, 0)
            })
        }
        wrap.addView(waitBox)
    }

    if (compactForMap) return wrap

    // 2. Warm, encouraging kindness reminder while waiting for pickup
    if (b.status in listOf("CREATED", "PENDING", "SEARCHING", "ACCEPTED", "ON_THE_WAY", "ARRIVED")) {
        wrap.addView(TextView(this).apply {
            text = "A Little Kindness Goes a Long Way — Our Alisto pros brave traffic and weather to serve you safely. A warm greeting, clear instructions, and mutual respect brighten every trip!"
            textSize = 12f; setTextColor(Color.parseColor("#BAE6FD"))
            background = AlistoUiKit.roundedBg(this@buildKindnessAndWaitingBanner, "#082032", 12, "#0EA5E9")
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        })
    }

    // 3. Accepted / On The Way readiness reminder
    if (b.status in listOf("ACCEPTED", "ON_THE_WAY")) {
        wrap.addView(TextView(this).apply {
            text = "Get Ready for Pickup: Once your pro arrives, you have ${rules.freeWaitMinutes} free minutes to meet them. Wait times past ${rules.freeWaitMinutes} minutes incur PHP${"%.2f".format(rules.waitingRatePerMinPhp)}/min."
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FDE68A"))
            background = AlistoUiKit.roundedBg(this@buildKindnessAndWaitingBanner, "#2A1B08", 12, "#B45309")
            setPadding(dp(12), dp(9), dp(12), dp(9))
        })
    }

    return wrap
}

internal fun CustomerMainActivity.openLiveCustomerChatDialog(booking: BookingRecord) {
    val myId = AlistoKernelClient.currentSessionUser?.id.orEmpty()
    val dialogLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(10))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    dialogLayout.addView(TextView(this).apply {
        text = "Eyes on the Road, Hands on the Bars — Your rider may take a moment to reply while navigating traffic safely. They'll read your message at their next safe stop!"
        textSize = 11.5f; setTextColor(Color.parseColor("#FDE68A"))
        background = AlistoUiKit.roundedBg(this@openLiveCustomerChatDialog, "#2A1B08", 10, "#92400E")
        setPadding(dp(10), dp(7), dp(10), dp(7))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    })

    val msgScroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)).apply { bottomMargin = dp(10) }
        background = AlistoUiKit.roundedBg(this@openLiveCustomerChatDialog, "#060B17", 10, "#1E293B")
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    val msgList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    msgScroll.addView(msgList)
    dialogLayout.addView(msgScroll)

    fun renderMessages(messages: List<ChatMessage>) {
        msgList.removeAllViews()
        if (messages.isEmpty()) {
            msgList.addView(TextView(this).apply {
                text = "No messages yet. Say hello or share landmarks with your pro!"
                textSize = 12f; setTextColor(Color.parseColor("#94A3B8")); setPadding(dp(6), dp(12), dp(6), dp(12))
            })
        } else {
            messages.forEach { m ->
                val isMine = m.senderId.isNotBlank() && m.senderId == myId
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = if (isMine) Gravity.END else Gravity.START
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                }
                val bubble = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = AlistoUiKit.roundedBg(
                        this@openLiveCustomerChatDialog,
                        if (isMine) "#064E3B" else "#1E293B",
                        12,
                        if (isMine) "#10B981" else "#334155"
                    )
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                }
                bubble.addView(TextView(this).apply {
                    text = "${if (isMine) "You" else m.senderName} • ${m.sentAt}"
                    textSize = 10f; setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(if (isMine) "#6EE7B7" else "#7DD3FC"))
                })
                bubble.addView(TextView(this).apply {
                    text = m.body
                    textSize = 13f; setTextColor(Color.parseColor("#F8FAFC")); setPadding(0, dp(2), 0, 0)
                })
                row.addView(bubble)
                msgList.addView(row)
            }
            msgScroll.post { msgScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    renderMessages(AlistoKernelClient.chatTranscripts[booking.id].orEmpty())

    val inputRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    val msgInput = AlistoUiKit.inputField(this, "Type a message...").apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
    }
    val sendBtn = AlistoUiKit.pillButton(this, "Send", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, "#6EE7B7", heightDp = 42, textSizeSp = 13f) {
        val body = msgInput.text.toString().trim()
        if (body.isBlank()) return@pillButton
        msgInput.setText("")
        uiScope.launch {
            val res = AlistoKernelClient.sendModeratedChatMessageRpc(booking.id, body)
            res.onSuccess {
                renderMessages(AlistoKernelClient.chatTranscripts[booking.id].orEmpty())
                AlistoChatEngine.notifyLocalListeners(booking.id)
                AlistoKernelClient.chatTranscripts[booking.id]?.lastOrNull()?.id?.takeIf { it.isNotBlank() }?.let {
                    lastSeenChatMsgIds[booking.id] = it
                }
            }.onFailure { err ->
                AlertDialog.Builder(this@openLiveCustomerChatDialog)
                    .setTitle("Moderation Notice").setMessage(err.message).setPositiveButton("OK", null).show()
            }
        }
    }
    inputRow.addView(msgInput)
    inputRow.addView(sendBtn)
    dialogLayout.addView(inputRow)

    val syncStatusTv = TextView(this).apply {
        text = "● Realtime chat connected"
        textSize = 10.5f
        setTextColor(Color.parseColor("#6EE7B7"))
        setPadding(dp(2), dp(6), dp(2), 0)
    }
    dialogLayout.addView(syncStatusTv)

    val transcriptListener: (List<ChatMessage>) -> Unit = { msgs ->
        renderMessages(msgs)
        syncStatusTv.text = "● Live realtime chat (${msgs.size} message${if (msgs.size == 1) "" else "s"})"
        syncStatusTv.setTextColor(Color.parseColor("#6EE7B7"))
        msgs.lastOrNull()?.id?.takeIf { it.isNotBlank() }?.let { lastSeenChatMsgIds[booking.id] = it }
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle("Chat • ${booking.contractorFullName ?: booking.bookingRef}")
        .setView(dialogLayout)
        .setNegativeButton("Close", null)
        .create()

    dialog.setOnShowListener {
        AlistoChatEngine.openChatBookingId = booking.id
        AlistoChatEngine.clearChatNotification(this@openLiveCustomerChatDialog, booking.id)
        AlistoChatEngine.addTranscriptListener(booking.id, transcriptListener)
        AlistoChatEngine.triggerCatchUp(booking.id, forceFull = true) { res ->
            res.onFailure {
                syncStatusTv.text = "● Reconnecting realtime chat..."
                syncStatusTv.setTextColor(Color.parseColor("#FBBF24"))
            }
        }
    }
    dialog.setOnDismissListener {
        if (AlistoChatEngine.openChatBookingId == booking.id) {
            AlistoChatEngine.openChatBookingId = null
        }
        AlistoChatEngine.removeTranscriptListener(booking.id, transcriptListener)
    }
    dialog.show()
}
