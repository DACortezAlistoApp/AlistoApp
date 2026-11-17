package ph.alisto.contractor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.BookingRecord

class ContractorMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var walletSummaryText: TextView
    private lateinit var kycBadgeText: TextView
    private lateinit var dispatchListLayout: LinearLayout
    private lateinit var ledgerListLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070D19"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        container.addView(TextView(this).apply {
            text = "ALISTO CONTRACTOR APP"
            setTextColor(Color.parseColor("#FFB300"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = "Alisto Network PH • LGU/PESO Technician & Sub-400cc Operator Console\nArchitect: D. A. Cortez"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 4, 0, 16)
        })

        kycBadgeText = TextView(this).apply {
            setPadding(20, 14, 20, 14)
            setBackgroundColor(Color.parseColor("#064E3B"))
            setTextColor(Color.parseColor("#34D399"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        container.addView(kycBadgeText)

        container.addView(sectionTitle("Engine 03: Closed-Loop Prepaid Service Credit Wallet"))
        walletSummaryText = TextView(this).apply {
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        container.addView(walletSummaryText)

        val topUpBtn = Button(this).apply {
            text = "+ TOP UP PHP 500 PREPAID CREDITS (PAYMONGO QR PH)"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                uiScope.launch {
                    AlistoKernelClient.topUpCreditsQrPhRpc(500.0)
                    Toast.makeText(
                        this@ContractorMainActivity,
                        "PHP 500.00 Prepaid Credits minted via PayMongo QR Ph!",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderContractorScreen()
                }
            }
        }
        container.addView(topUpBtn)

        container.addView(sectionTitle("Engine 04: Barangay Job Board & Active State Machine"))
        dispatchListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(dispatchListLayout)

        container.addView(sectionTitle("Engine 03: Immutable Append-Only Credit Ledger"))
        ledgerListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(ledgerListLayout)

        rootScroll.addView(container)
        setContentView(rootScroll)

        renderContractorScreen()
    }

    private fun renderContractorScreen() {
        kycBadgeText.text = if (AlistoKernelClient.contractorKycVerified && AlistoKernelClient.contractorLguEndorsed) {
            "ENGINE 01 VERIFIED: Barangay KYC + Angeles City LGU/PESO Endorsed • Sub-400cc Non-Expressway Routing Active"
        } else {
            "ENGINE 01 HOLD: Pending Barangay KYC / LGU Endorsement"
        }

        walletSummaryText.text =
            "Available Prepaid Credits: PHP ${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)} (Non-Withdrawable)\n" +
            "Locked 12% Commission Holds: PHP ${"%.2f".format(AlistoKernelClient.contractorHeldCredits)}"

        dispatchListLayout.removeAllViews()
        AlistoKernelClient.bookings.forEach { b ->
            dispatchListLayout.addView(buildContractorJobCard(b))
        }

        ledgerListLayout.removeAllViews()
        AlistoKernelClient.ledgerHistory.forEach { entry ->
            val row = TextView(this).apply {
                text = "[${entry.entryType}] ${entry.amount} • Avail: PHP ${"%.2f".format(entry.balanceAfter)} | Held: PHP ${"%.2f".format(entry.heldAfter)}\n${entry.notes}"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
                setPadding(20, 14, 20, 14)
                setBackgroundColor(Color.parseColor("#0F172A"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }
            }
            ledgerListLayout.addView(row)
        }
    }

    private fun buildContractorJobCard(b: BookingRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        val reqComm = b.quotedFee * 0.12

        card.addView(TextView(this).apply {
            text = "${b.bookingRef} • [${b.status}]"
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
        })
        card.addView(TextView(this).apply {
            text = "${b.serviceTitle}\nZone: ${b.barangayZone}, ${b.municipality}\nAddress: ${b.pickupAddress}\nQuoted Fee: PHP ${"%.2f".format(b.quotedFee)} | Tip (100% Yours): PHP ${"%.2f".format(b.tipAmount)} | 12% Hold: PHP ${"%.2f".format(reqComm)}"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 6, 0, 12)
        })

        val actionBtn = Button(this).apply {
            setBackgroundColor(Color.parseColor("#FFB300"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
        }

        when (b.status) {
            "SEARCHING" -> {
                actionBtn.text = "CLAIM JOB (LOCK 12% HOLD: PHP ${"%.2f".format(reqComm)})"
                actionBtn.setOnClickListener {
                    uiScope.launch {
                        val res = AlistoKernelClient.claimBookingRpc(b.id, "33333333-3333-3333-3333-333333333333")
                        res.onSuccess {
                            Toast.makeText(this@ContractorMainActivity, "Job Claimed! 12% Commission Hold Locked.", Toast.LENGTH_SHORT).show()
                            renderContractorScreen()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Cannot Claim Job")
                                .setMessage(err.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
                card.addView(actionBtn)
            }
            "ACCEPTED" -> {
                actionBtn.text = "MARK ARRIVED AT CLIENT SITE"
                actionBtn.setOnClickListener { transitionJob(b.id, "ARRIVED") }
                card.addView(actionBtn)
            }
            "ARRIVED" -> {
                actionBtn.text = "START WORK (IN_PROGRESS)"
                actionBtn.setOnClickListener { transitionJob(b.id, "IN_PROGRESS") }
                card.addView(actionBtn)
            }
            "IN_PROGRESS" -> {
                actionBtn.text = "COMPLETE JOB & SETTLE 12% COMMISSION"
                actionBtn.setOnClickListener { transitionJob(b.id, "COMPLETED") }
                card.addView(actionBtn)
            }
        }

        if (b.status in listOf("ACCEPTED", "ARRIVED", "IN_PROGRESS")) {
            val chatBtn = Button(this).apply {
                text = "OPEN TRANSACTIONAL CLIENT CHAT"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener { openContractorChatDialog(b) }
            }
            card.addView(chatBtn)
        }

        return card
    }

    private fun transitionJob(bookingId: String, nextStatus: String) {
        uiScope.launch {
            AlistoKernelClient.advanceBookingStateRpc(bookingId, nextStatus)
            renderContractorScreen()
        }
    }

    private fun openContractorChatDialog(booking: BookingRecord) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val transcriptView = TextView(this).apply {
            val msgs = AlistoKernelClient.chatTranscripts[booking.id] ?: emptyList()
            text = if (msgs.isEmpty()) {
                "Anti-Circumvention Moderation Trigger Active.\nAttempting to solicit off-platform cash deals ('cancel the booking', 'pay me cash direct') is blocked and audited."
            } else {
                msgs.joinToString("\n\n") { "${it.senderName}: ${it.body}" }
            }
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, 0, 0, 20)
        }
        dialogLayout.addView(transcriptView)

        val msgInput = EditText(this).apply {
            hint = "Message client (or test 'pay me cash direct')..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(20, 16, 20, 16)
        }
        dialogLayout.addView(msgInput)

        AlertDialog.Builder(this)
            .setTitle("Contractor Chat • ${booking.bookingRef}")
            .setView(dialogLayout)
            .setPositiveButton("Send") { _, _ ->
                val body = msgInput.text.toString()
                if (body.isNotBlank()) {
                    uiScope.launch {
                        val res = AlistoKernelClient.sendModeratedChatMessageRpc(
                            bookingId = booking.id,
                            senderLabel = "Contractor",
                            messageBody = body
                        )
                        res.onSuccess {
                            Toast.makeText(this@ContractorMainActivity, "Message sent.", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Platform Safety Policy Violation")
                                .setMessage(err.message)
                                .setPositiveButton("Understood", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 22, 0, 10)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}
