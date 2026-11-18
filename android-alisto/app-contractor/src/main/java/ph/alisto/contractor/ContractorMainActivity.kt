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
import ph.alisto.kernel.*

class ContractorMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var entryMode = "LOGIN"
    private var autoAcceptEnabled = false
    private var compressedProfileUrl: String? = null
    private var compressedLicenseUrl: String? = null
    private var compressedOrcrUrl: String? = null
    private var compressedVehicleUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AlistoKernelClient.currentSessionUser != null) {
            routeContractorByAccountState()
        } else if (AlistoBiometricVault.hasSavedSession(this, "contractor")) {
            triggerFrictionlessBiometricEntry()
        } else {
            showContractorEntryGate()
        }
    }

    private fun triggerFrictionlessBiometricEntry() {
        AlistoBiometricVault.promptBiometricUnlock(
            activity = this,
            portalRole = "contractor",
            onUnlockedUserId = { savedUid ->
                uiScope.launch {
                    val res = AlistoKernelClient.fetchAppBootStateRpc(savedUid, "contractor")
                    res.onSuccess { routeContractorByAccountState() }
                        .onFailure { showContractorEntryGate() }
                }
            },
            onFallbackOrError = { showContractorEntryGate() }
        )
    }

    private fun showContractorEntryGate() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070A12")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 54, 42, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "ALISTO CONTRACTOR",
                "Nationwide Roaming • Dual-Dispatch • Biometric Keystore"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 20) }
            }
        )

        if (AlistoBiometricVault.hasSavedSession(this, "contractor")) {
            container.addView(Button(this).apply {
                text = "BIOMETRIC QUICK UNLOCK (${AlistoBiometricVault.getSavedUserLabel(this@ContractorMainActivity, "contractor")})"
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener { triggerFrictionlessBiometricEntry() }
            })
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 20)
            addView(Button(this@ContractorMainActivity).apply {
                text = "LOG IN"
                setBackgroundColor(Color.parseColor(if (entryMode == "LOGIN") "#FBBF24" else "#1E293B"))
                setTextColor(Color.parseColor(if (entryMode == "LOGIN") "#070A12" else "#FFFFFF"))
                setOnClickListener { entryMode = "LOGIN"; showContractorEntryGate() }
            })
            addView(Button(this@ContractorMainActivity).apply {
                text = "CONTRACTOR SIGN UP"
                setBackgroundColor(Color.parseColor(if (entryMode == "SIGNUP") "#FBBF24" else "#1E293B"))
                setTextColor(Color.parseColor(if (entryMode == "SIGNUP") "#070A12" else "#FFFFFF"))
                setOnClickListener { entryMode = "SIGNUP"; showContractorEntryGate() }
            })
        }
        container.addView(tabRow)

        if (entryMode == "LOGIN") {
            val idInput = styledInput("Username (e.g., marco), Phone, or Email")
            container.addView(labeledField("Contractor Login Identifier", idInput))
            val pwInput = styledInput("Permanent Non-Expiring Password").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            container.addView(labeledField("Password", pwInput))

            container.addView(Button(this).apply {
                text = "SIGN IN & BIND ANDROID BIOMETRIC KEYSTORE"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    uiScope.launch {
                        val res = AlistoKernelClient.authenticateUserRpc(idInput.text.toString(), pwInput.text.toString(), "contractor")
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(this@ContractorMainActivity, "contractor", user.id, "${user.fullName} (@${user.username})")
                            routeContractorByAccountState()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity).setTitle("Login Failed").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }
            })
        } else {
            val fnInput = styledInput("First Name")
            container.addView(labeledField("First Name", fnInput))
            val lnInput = styledInput("Last Name")
            container.addView(labeledField("Last Name", lnInput))
            val phoneInput = styledInput("+63917XXXXXXX")
            container.addView(labeledField("Mobile Number (Admin Call Verification)", phoneInput))
            val emailInput = styledInput("contractor@alisto.ph")
            container.addView(labeledField("Email Address", emailInput))
            val pwInput = styledInput("Permanent Password").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            container.addView(labeledField("Permanent Password", pwInput))

            container.addView(Button(this).apply {
                text = "SUBMIT CONTRACTOR REGISTRATION (PENDING_REVIEW GATE)"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    uiScope.launch {
                        val res = AlistoKernelClient.registerContractorPortalRpc(
                            firstName = fnInput.text.toString().trim(),
                            lastName = lnInput.text.toString().trim(),
                            birthDate = "1990-08-15",
                            phone = phoneInput.text.toString().trim(),
                            email = emailInput.text.toString().trim(),
                            password = pwInput.text.toString().trim(),
                            municipality = "Angeles City",
                            barangayZone = "Malabanias",
                            addressText = "Angeles City",
                            homeLat = 15.1450,
                            homeLng = 120.5887,
                            offersSkills = true,
                            skillTags = listOf("Plumbing", "Aircon Servicing"),
                            offersDelivery = true,
                            offersRideHailing = false,
                            vehicleType = "motorcycle",
                            profilePhotoUrl = compressedProfileUrl,
                            licenseDocUrl = compressedLicenseUrl,
                            orcrDocUrl = compressedOrcrUrl,
                            vehiclePhotoUrl = compressedVehicleUrl
                        )
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(this@ContractorMainActivity, "contractor", user.id, "${user.fullName} (@${user.username})")
                            routeContractorByAccountState()
                        }
                    }
                }
            })
        }
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun routeContractorByAccountState() {
        val user = AlistoKernelClient.currentSessionUser ?: return showContractorEntryGate()
        when (user.accountStatus) {
            "PENDING_REVIEW", "RESUBMISSION_REQUIRED", "SUSPENDED" -> {
                val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070A12")) }
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(42, 64, 42, 64) }
                box.addView(TextView(this).apply {
                    text = "ACCOUNT GATE: ${user.accountStatus}\n${user.fullName} (@${user.username})\nAwaiting Admin KYC Activation."
                    setTextColor(Color.parseColor("#FBBF24"))
                    textSize = 15f
                })
                box.addView(Button(this).apply {
                    text = "Refresh Boot State"
                    setOnClickListener { uiScope.launch { AlistoKernelClient.fetchAppBootStateRpc(user.id, "contractor"); routeContractorByAccountState() } }
                })
                scroll.addView(box)
                setContentView(scroll)
            }
            else -> showActiveContractorPortal(user)
        }
    }

    private fun showActiveContractorPortal(user: AuthenticatedUser) {
        val prof = AlistoKernelClient.currentContractorProfile
        val activePass = AlistoKernelClient.currentSubscriptionPass

        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070D19")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        val heartbeatBadge = if (AlistoSpatialEngine.isHeartbeatStreaming) "10s GPS HEARTBEAT: ACTIVE" else "GPS TELEMETRY: OFF (0 WRITES)"
        container.addView(
            AlistoBrandHeaderView(
                this,
                "${user.fullName} (@${user.username})",
                "$heartbeatBadge • Nationwide (avoid=ht)"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }
            }
        )

        // DUAL-DISPATCH ENGINE TOGGLE (Auto-Accept Mode vs. Open Jobs Board Mode)
        container.addView(sectionTitle("1. Dual-Dispatch Mode & Nationwide Local-Road Navigation"))
        val dispatchModeBtn = Button(this).apply {
            text = if (autoAcceptEnabled) "DISPATCH MODE: AUTO-ACCEPT ON (Nearest ${prof?.dispatchRadiusM ?: 1500}m)" else "DISPATCH MODE: OPEN JOBS BOARD (Manual Bid / Claim)"
            setBackgroundColor(Color.parseColor(if (autoAcceptEnabled) "#10B981" else "#1E293B"))
            setTextColor(Color.parseColor(if (autoAcceptEnabled) "#070D19" else "#00F2FE"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                autoAcceptEnabled = !autoAcceptEnabled
                uiScope.launch {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(isOnline = true, autoAccept = autoAcceptEnabled)
                    showActiveContractorPortal(user)
                }
            }
        }
        container.addView(dispatchModeBtn)

        // HYBRID WALLET & SUBSCRIPTION PASSES
        val commPct = "%.1f".format(AlistoKernelClient.effectiveCommissionRate * 100.0)
        container.addView(sectionTitle("2. Hybrid Wallet & Subscription Passes (Effective Rate: $commPct%)"))
        container.addView(TextView(this).apply {
            setPadding(24, 18, 24, 18)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            text = "Prepaid Wallet: PHP ${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)} | Held: PHP ${"%.2f".format(AlistoKernelClient.contractorHeldCredits)}\n" +
                   "Active Pass: ${activePass?.tierCode ?: "STANDARD PAY-AS-YOU-GO (<=12%)"}"
        })

        val walletBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
            addView(Button(this@ContractorMainActivity).apply {
                text = "+PHP 500 Top-Up (+₱25 Bonus)"
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.parseColor("#070D19"))
                setOnClickListener {
                    uiScope.launch {
                        AlistoKernelClient.topUpCreditsQrPhRpc(500.0)
                        showActiveContractorPortal(AlistoKernelClient.currentSessionUser ?: user)
                    }
                }
            })
            addView(Button(this@ContractorMainActivity).apply {
                text = "Activate Grind Pass (0% 24/7)"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070D19"))
                setOnClickListener {
                    uiScope.launch {
                        AlistoKernelClient.purchaseSubscriptionPassRpc("GRIND_PASS")
                        routeContractorByAccountState()
                    }
                }
            })
        }
        container.addView(walletBtnRow)

        // OPEN BIDDING, TELEMETRY-GATED LIFECYCLE & TWO-WAY REPUTATION
        container.addView(sectionTitle("3. Open Bidding Board, 10s Telemetry Gate & Customer Thumbs Rating"))
        AlistoKernelClient.bookings.forEach { b ->
            container.addView(buildContractorJobCard(b, user))
        }

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun buildContractorJobCard(b: BookingRecord, user: AuthenticatedUser): View {
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
            text = "${b.serviceTitle}\nAddress: ${b.pickupAddress} (${b.barangayZone}, ${b.municipality})\nBase: PHP ${"%.2f".format(b.quotedFee)} | 100% Tip: PHP ${"%.2f".format(b.tipAmount)} | Est. Hold ($commPct%): PHP ${"%.2f".format(reqComm)}"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setPadding(0, 6, 0, 10)
        })

        // One-Tap Local-Road Navigation (avoid=ht)
        card.addView(Button(this).apply {
            text = "ONE-TAP GOOGLE MAPS NAVIGATION (AVOID=HT • NO EXPRESSWAYS)"
            setBackgroundColor(Color.parseColor("#064E3B"))
            setTextColor(Color.parseColor("#34D399"))
            setOnClickListener {
                AlistoSpatialEngine.launchLocalRoadNavigation(this@ContractorMainActivity, 15.1455, 120.5892)
            }
        })

        when (b.status) {
            "CREATED", "PENDING", "SEARCHING" -> {
                val bidBtn = Button(this).apply {
                    text = "SUBMIT OPEN BID (PRE-BID WALLET CHECK • TELEMETRY OFF)"
                    setBackgroundColor(Color.parseColor("#38BDF8"))
                    setTextColor(Color.parseColor("#070D19"))
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener { promptSubmitOpenBid(b) }
                }
                card.addView(bidBtn)

                val claimBtn = Button(this).apply {
                    text = "INSTANT CLAIM JOB (LOCK $commPct%: PHP ${"%.2f".format(reqComm)})"
                    setBackgroundColor(Color.parseColor("#FBBF24"))
                    setTextColor(Color.parseColor("#070D19"))
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener {
                        uiScope.launch {
                            val res = AlistoKernelClient.claimBookingRpc(b.id)
                            res.onSuccess { routeContractorByAccountState() }
                                .onFailure { err ->
                                    AlertDialog.Builder(this@ContractorMainActivity).setTitle("Wallet Check").setMessage(err.message).setPositiveButton("OK", null).show()
                                }
                        }
                    }
                }
                card.addView(claimBtn)
            }
            "ACCEPTED" -> {
                card.addView(Button(this).apply {
                    text = "TAP ON_THE_WAY (START 10-SEC BILATERAL GPS HEARTBEAT)"
                    setBackgroundColor(Color.parseColor("#FBBF24"))
                    setTextColor(Color.parseColor("#070D19"))
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener {
                        AlistoSpatialEngine.startTenSecondHeartbeat(this@ContractorMainActivity, uiScope, b.id, user.id)
                        transitionJob(b.id, "ON_THE_WAY")
                    }
                })
            }
            "ON_THE_WAY" -> {
                card.addView(Button(this).apply {
                    text = "MARK ARRIVED AT PICKUP / SITE (10S GPS STREAMING)"
                    setBackgroundColor(Color.parseColor("#00F2FE"))
                    setTextColor(Color.parseColor("#070D19"))
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener { transitionJob(b.id, "ARRIVED") }
                })
            }
            "ARRIVED" -> {
                card.addView(Button(this).apply {
                    text = "START EXECUTION (ONGOING • 10S GPS HEARTBEAT ACTIVE)"
                    setBackgroundColor(Color.parseColor("#FBBF24"))
                    setTextColor(Color.parseColor("#070D19"))
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener { transitionJob(b.id, "ONGOING") }
                })
            }
            "ONGOING", "IN_PROGRESS" -> {
                card.addView(Button(this).apply {
                    text = "COMPLETE P2P SETTLEMENT & STOP GPS HEARTBEAT"
                    setBackgroundColor(Color.parseColor("#10B981"))
                    setTextColor(Color.parseColor("#070D19"))
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener {
                        AlistoSpatialEngine.stopHeartbeat()
                        transitionJob(b.id, "COMPLETED")
                    }
                })
            }
            "COMPLETED" -> {
                // Section 5: Two-Way Reputation — Thumbs Up / Thumbs Down for Customer
                val thumbRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 0)
                    addView(Button(this@ContractorMainActivity).apply {
                        text = "RATE CUSTOMER: THUMB UP"
                        setBackgroundColor(Color.parseColor("#064E3B"))
                        setTextColor(Color.parseColor("#34D399"))
                        setOnClickListener {
                            uiScope.launch {
                                AlistoKernelClient.submitContractorThumbReviewRpc(b.id, "UP", "Smooth P2P payment & clear instructions")
                                Toast.makeText(this@ContractorMainActivity, "Customer Thumbs-Up Recorded!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                    addView(Button(this@ContractorMainActivity).apply {
                        text = "RATE CUSTOMER: THUMB DOWN"
                        setBackgroundColor(Color.parseColor("#7F1D1D"))
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            uiScope.launch {
                                val res = AlistoKernelClient.submitContractorThumbReviewRpc(b.id, "DOWN", "Delayed coordination / difficult pickup")
                                res.onSuccess { json ->
                                    val gate = json.optBoolean("trust_deposit_required", false)
                                    Toast.makeText(this@ContractorMainActivity, "Thumbs-Down Logged (Customer Deposit Gate: $gate)", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    })
                }
                card.addView(thumbRow)
            }
        }
        return card
    }

    private fun promptSubmitOpenBid(b: BookingRecord) {
        val bidInput = styledInput(b.quotedFee.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle("Submit Open Bid • ${b.bookingRef}")
            .setMessage("Pre-Bid Wallet Check validates <=12% commission coverage (0% for Active Pass holders).")
            .setView(bidInput)
            .setPositiveButton("Submit Bid") { _, _ ->
                val amt = bidInput.text.toString().toDoubleOrNull() ?: b.quotedFee
                uiScope.launch {
                    val res = AlistoKernelClient.submitContractorBidRpc(b.id, amt, 20, "Open bid by verified contractor")
                    res.onSuccess {
                        Toast.makeText(this@ContractorMainActivity, "Bid of PHP $amt broadcast to customer!", Toast.LENGTH_LONG).show()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@ContractorMainActivity).setTitle("Pre-Bid Wallet Gate").setMessage(err.message).setPositiveButton("OK", null).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun transitionJob(bookingId: String, nextStatus: String) {
        uiScope.launch {
            AlistoKernelClient.advanceBookingStateRpc(bookingId, nextStatus)
            routeContractorByAccountState()
        }
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#00F2FE"))
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 20, 0, 8)
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
        setPadding(0, 0, 0, 14)
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
        AlistoSpatialEngine.stopHeartbeat()
        uiScope.cancel()
    }
}
