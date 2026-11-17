package ph.alisto.contractor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import ph.alisto.kernel.AlistoBrandHeaderView
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.BookingRecord

class ContractorMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AlistoKernelClient.currentSessionUser == null) {
            showLoginGate()
        } else {
            showAuthenticatedContractorPortal()
        }
    }

    private fun showLoginGate() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070A12")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 64, 42, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "ALISTO CONTRACTOR",
                "Engine 01 Technician & Operator Login Gate"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 32) }
            }
        )

        val idInput = styledInput("Username, Mobile Phone (+63...), or Email")
        container.addView(labeledField("Contractor Login Identifier (public.users)", idInput))

        val pwInput = styledInput("Password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(labeledField("Account Password", pwInput))

        val loginBtn = Button(this).apply {
            text = "SIGN IN TO ALISTO CONTRACTOR APP"
            setBackgroundColor(Color.parseColor("#FBBF24"))
            setTextColor(Color.parseColor("#070A12"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val idStr = idInput.text.toString()
                val pwStr = pwInput.text.toString()
                isEnabled = false
                text = "VERIFYING CONTRACTOR CREDENTIALS..."
                uiScope.launch {
                    val res = AlistoKernelClient.authenticateUserRpc(idStr, pwStr, "contractor")
                    isEnabled = true
                    text = "SIGN IN TO ALISTO CONTRACTOR APP"
                    res.onSuccess {
                        showAuthenticatedContractorPortal()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@ContractorMainActivity)
                            .setTitle("Authentication Denied")
                            .setMessage(err.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
        container.addView(loginBtn)

        scroll.addView(container)
        setContentView(scroll)
    }

    private fun showAuthenticatedContractorPortal() {
        val user = AlistoKernelClient.currentSessionUser ?: return showLoginGate()

        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070D19")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "ALISTO CONTRACTOR",
                "${user.fullName} (@${user.username})"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
            }
        )

        val sessionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
            addView(Button(this@ContractorMainActivity).apply {
                text = "Sync Dispatch & Wallet"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    uiScope.launch {
                        AlistoKernelClient.syncBookingsAndWallet()
                        showAuthenticatedContractorPortal()
                    }
                }
            })
            addView(Button(this@ContractorMainActivity).apply {
                text = "Sign Out"
                setBackgroundColor(Color.parseColor("#7F1D1D"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AlistoKernelClient.signOut()
                    showLoginGate()
                }
            })
        }
        container.addView(sessionBar)

        val kycBadgeText = TextView(this).apply {
            setPadding(20, 14, 20, 14)
            val verified = AlistoKernelClient.contractorKycVerified && AlistoKernelClient.contractorLguEndorsed
            setBackgroundColor(Color.parseColor(if (verified) "#064E3B" else "#7F1D1D"))
            setTextColor(Color.parseColor(if (verified) "#34D399" else "#FCA5A5"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            text = if (verified) {
                "ENGINE 01 VERIFIED: Barangay KYC + LGU/PESO Endorsed (${user.barangayZone}, ${user.municipality})"
            } else {
                "ENGINE 01 HOLD: Pending Barangay KYC / LGU Endorsement"
            }
        }
        container.addView(kycBadgeText)

        val commPct = "%.1f".format(AlistoKernelClient.effectiveCommissionRate * 100.0)
        container.addView(sectionTitle("Engine 03: Prepaid Service Credit Wallet (Rate: $commPct%)"))
        val walletSummaryText = TextView(this).apply {
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Available Prepaid Credits: PHP ${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)} (Non-Withdrawable)\n" +
                   "Locked Commission Holds: PHP ${"%.2f".format(AlistoKernelClient.contractorHeldCredits)}"
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
                    showAuthenticatedContractorPortal()
                }
            }
        }
        container.addView(topUpBtn)

        container.addView(sectionTitle("Engine 04: Barangay Job Board & Active State Machine"))
        AlistoKernelClient.bookings.forEach { b ->
            container.addView(buildContractorJobCard(b))
        }

        container.addView(sectionTitle("Engine 03: My Immutable Append-Only Credit Ledger"))
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
            container.addView(row)
        }

        rootScroll.addView(container)
        setContentView(rootScroll)
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

        val reqComm = b.quotedFee * AlistoKernelClient.effectiveCommissionRate
        val commPct = "%.1f".format(AlistoKernelClient.effectiveCommissionRate * 100.0)

        card.addView(TextView(this).apply {
            text = "${b.bookingRef} • [${b.status}]"
            setTextColor(Color.parseColor("#00F2FE"))
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
        })
        card.addView(TextView(this).apply {
            text = "${b.serviceTitle}\nZone: ${b.barangayZone}, ${b.municipality}\nAddress: ${b.pickupAddress}\nQuoted Fee: PHP ${"%.2f".format(b.quotedFee)} | Tip (100% Yours): PHP ${"%.2f".format(b.tipAmount)} | $commPct% Hold: PHP ${"%.2f".format(reqComm)}"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 6, 0, 12)
        })

        val actionBtn = Button(this).apply {
            setBackgroundColor(Color.parseColor("#FBBF24"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
        }

        when (b.status) {
            "SEARCHING" -> {
                actionBtn.text = "CLAIM JOB (LOCK $commPct% HOLD: PHP ${"%.2f".format(reqComm)})"
                actionBtn.setOnClickListener {
                    uiScope.launch {
                        val res = AlistoKernelClient.claimBookingRpc(b.id)
                        res.onSuccess {
                            Toast.makeText(this@ContractorMainActivity, "Job Claimed & GPS Telemetry Logged!", Toast.LENGTH_SHORT).show()
                            showAuthenticatedContractorPortal()
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
                actionBtn.text = "MARK ARRIVED AT CLIENT SITE (LOG GPS)"
                actionBtn.setOnClickListener { transitionJob(b.id, "ARRIVED") }
                card.addView(actionBtn)
            }
            "ARRIVED" -> {
                actionBtn.text = "START WORK (IN_PROGRESS)"
                actionBtn.setOnClickListener { transitionJob(b.id, "IN_PROGRESS") }
                card.addView(actionBtn)
            }
            "IN_PROGRESS" -> {
                actionBtn.text = "COMPLETE JOB & SETTLE COMMISSION"
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
            showAuthenticatedContractorPortal()
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

        val msgInput = styledInput("Message client (or test 'pay me cash direct')...")
        dialogLayout.addView(msgInput)

        AlertDialog.Builder(this)
            .setTitle("Contractor Chat • ${booking.bookingRef}")
            .setView(dialogLayout)
            .setPositiveButton("Send") { _, _ ->
                val body = msgInput.text.toString()
                if (body.isNotBlank()) {
                    uiScope.launch {
                        val res = AlistoKernelClient.sendModeratedChatMessageRpc(booking.id, body)
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

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#00F2FE"))
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 22, 0, 10)
    }

    private fun styledInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#64748B"))
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#1E293B"))
        setPadding(20, 16, 20, 16)
    }

    private fun labeledField(label: String, field: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, 16)
        addView(TextView(this@ContractorMainActivity).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 0, 0, 4)
        })
        addView(field)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}
