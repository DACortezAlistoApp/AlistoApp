package ph.alisto.contractor

import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
    private var realtimePollJob: Job? = null
    private var contractorMapView: AlistoInteractiveMapView? = null
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
                            AlistoBiometricVault.saveEncryptedSession(this@ContractorMainActivity, "contractor", AlistoKernelClient.currentSessionToken ?: "", "${user.fullName} (@${user.username})")
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
                            AlistoBiometricVault.saveEncryptedSession(this@ContractorMainActivity, "contractor", AlistoKernelClient.currentSessionToken ?: "", "${user.fullName} (@${user.username})")
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
        autoAcceptEnabled = AlistoKernelClient.currentContractorProfile?.autoAcceptEnabled ?: false
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
                    setOnClickListener { uiScope.launch { AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "contractor"); routeContractorByAccountState() } }
                })
                scroll.addView(box)
                setContentView(scroll)
            }
            else -> showActiveContractorPortal(user)
        }
    }

    private fun showActiveContractorPortal(user: AuthenticatedUser) {
        AlistoPushCenter.ensureNotificationPermission(this)
        AlistoPushCenter.dispatchUnreadNotifications(this, uiScope)
        startContractorRealtimeLoop()
        val prof = AlistoKernelClient.currentContractorProfile
        val activePass = AlistoKernelClient.currentSubscriptionPass
        val currentShifts = prof?.activeShiftVerticals ?: listOf("skills_marketplace")

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

        // LIVE DEMAND HEAT MAP & OPEN JOB BOARD RADAR
        container.addView(sectionTitle("Live Demand Heat Map & Open Job Radar"))
        container.addView(TextView(this).apply {
            text = "Demand Clusters: ${AlistoKernelClient.heatmapClusters.size} | Open/Active Pins: ${AlistoKernelClient.bookings.count { it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED") }} | Live GPS: ${AlistoKernelClient.liveTelemetryPins.size}"
            setTextColor(Color.parseColor("#00F2FE"))
            textSize = 12f
            setPadding(0, 0, 0, 6)
        })
        val cMap = AlistoInteractiveMapView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (250 * resources.displayMetrics.density).toInt()
            ).apply { setMargins(0, 0, 0, 14) }
        }
        contractorMapView = cMap
        container.addView(cMap)
        cMap.loadInteractiveMap(user.homeLat, user.homeLng, enableTapToPin = false)
        cMap.updateMapLayers(
            bookings = AlistoKernelClient.bookings,
            telemetryPins = AlistoKernelClient.liveTelemetryPins,
            heatClusters = AlistoKernelClient.heatmapClusters
        )

                // SECTION 1: MULTI-VERTICAL DAILY SHIFT SELECTOR & DUAL-DISPATCH MODE
        container.addView(sectionTitle("1. Daily Shift Verticals & Dual-Dispatch Mode"))
        val shiftBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
        }
        shiftBox.addView(TextView(this).apply {
            text = "Choose Active Vertical(s) for Today's Shift:"
            setTextColor(Color.parseColor("#FBBF24"))
            setTypeface(null, Typeface.BOLD)
            textSize = 13f
        })

        val cbSkills = CheckBox(this).apply {
            text = "On-Demand Skills & Errand Marketplace"
            setTextColor(Color.WHITE)
            isChecked = "skills_marketplace" in currentShifts
        }
        val cbDelivery = CheckBox(this).apply {
            text = "Delivery & Logistics (${prof?.vehicleType ?: "motorcycle"} • ${prof?.dispatchRadiusM ?: 1500}m)"
            setTextColor(Color.WHITE)
            isChecked = "delivery_logistics" in currentShifts
        }
        val cbRide = CheckBox(this).apply {
            text = "Ride-Hailing (Sub-400cc Transit)"
            setTextColor(Color.WHITE)
            isChecked = "ride_hailing" in currentShifts
        }
        shiftBox.addView(cbSkills)
        shiftBox.addView(cbDelivery)
        shiftBox.addView(cbRide)

        shiftBox.addView(Button(this).apply {
            text = "SAVE ACTIVE SHIFT VERTICAL SELECTION"
            setBackgroundColor(Color.parseColor("#00F2FE"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val chosen = mutableListOf<String>()
                if (cbSkills.isChecked) chosen.add("skills_marketplace")
                if (cbDelivery.isChecked) chosen.add("delivery_logistics")
                if (cbRide.isChecked) chosen.add("ride_hailing")
                if (chosen.isEmpty()) chosen.add("skills_marketplace")

                uiScope.launch {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(
                        isOnline = true,
                        autoAccept = autoAcceptEnabled,
                        activeShiftVerticals = chosen
                    )
                    Toast.makeText(this@ContractorMainActivity, "Shift Verticals Updated: ${chosen.joinToString(", ")}", Toast.LENGTH_SHORT).show()
                    routeContractorByAccountState()
                }
            }
        })
        container.addView(shiftBox)

        val dispatchModeBtn = Button(this).apply {
            text = if (autoAcceptEnabled) "DISPATCH MODE: AUTO-ACCEPT ON (Nearest ${prof?.dispatchRadiusM ?: 1500}m)" else "DISPATCH MODE: OPEN JOBS BOARD (Manual Bid / Claim)"
            setBackgroundColor(Color.parseColor(if (autoAcceptEnabled) "#10B981" else "#1E293B"))
            setTextColor(Color.parseColor(if (autoAcceptEnabled) "#070D19" else "#00F2FE"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                autoAcceptEnabled = !autoAcceptEnabled
                uiScope.launch {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(
                        isOnline = true,
                        autoAccept = autoAcceptEnabled,
                        activeShiftVerticals = currentShifts
                    )
                    routeContractorByAccountState()
                }
            }
        }
        container.addView(dispatchModeBtn)

        // SECTION 2: HYBRID WALLET, PAYMENT GATEWAY MODE & SUBSCRIPTION PASSES
        val payInfo = AlistoKernelClient.currentPaymentModeInfo
        val commPct = "%.1f".format(AlistoKernelClient.effectiveCommissionRate * 100.0)
        container.addView(sectionTitle("2. Hybrid Wallet & Passes (Rate: $commPct% • Gateway: ${payInfo.paymentMode})"))
        val recentTxLine = AlistoKernelClient.paymentTransactions.firstOrNull()?.let { tx ->
            "\nLatest Payment: ${tx.txRef} • PHP ${"%.2f".format(tx.amountPhp)} [${tx.status}]"
        } ?: ""
        container.addView(TextView(this).apply {
            setPadding(24, 18, 24, 18)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            text = "Prepaid Wallet: PHP ${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)} | Held: PHP ${"%.2f".format(AlistoKernelClient.contractorHeldCredits)}\n" +
                   "Active Pass: ${activePass?.tierCode ?: "STANDARD PAY-AS-YOU-GO (<=12%)"} | Mode: ${payInfo.paymentMode}$recentTxLine"
        })

        val walletBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
            addView(Button(this@ContractorMainActivity).apply {
                text = if (payInfo.paymentMode == "MANUAL") "+PHP 500 Top-Up (Manual Ref)" else "+PHP 500 Top-Up (PayMongo QR Ph)"
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.parseColor("#070D19"))
                setOnClickListener {
                    initiateContractorPaymentFlow("WALLET_TOPUP", 500.0, null, "Prepaid Wallet Top-Up (+PHP 25 Bonus)")
                }
            })
            addView(Button(this@ContractorMainActivity).apply {
                text = if (payInfo.paymentMode == "MANUAL") "Grind Pass (Manual Ref)" else "Grind Pass (PayMongo)"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070D19"))
                setOnClickListener {
                    initiateContractorPaymentFlow("SUBSCRIPTION_PASS", 8000.0, "GRIND_PASS", "Hard-Working Grind Pass (0% 24/7)")
                }
            })
        }
        container.addView(walletBtnRow)

        // SECTION 3: OPEN BIDDING, PRE-BID CUSTOMER REPUTATION & TELEMETRY-GATED LIFECYCLE
        container.addView(sectionTitle("3. Active Shift Job Board (${currentShifts.joinToString(", ")})"))
        val shiftBookings = AlistoKernelClient.bookings.filter { b ->
            b.verticalKey in currentShifts || b.contractorId == user.id
        }
        shiftBookings.forEach { b ->
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
            text = "${b.bookingRef} • [${b.status}] (${b.verticalKey})"
            setTextColor(Color.parseColor("#00F2FE"))
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
        })

        // PRE-BID / PRE-DISPATCH CUSTOMER REPUTATION TRANSPARENCY (Section 5 of Spec)
        card.addView(TextView(this).apply {
            text = "Customer: ${b.clientNickname} • Trust Score: ${"%.0f".format(b.clientTrustScore)}% (UP: ${b.clientThumbsUp} / DOWN: ${b.clientThumbsDown})"
            setTextColor(Color.parseColor("#FBBF24"))
            setTypeface(null, Typeface.BOLD)
            textSize = 12.5f
            setPadding(0, 4, 0, 4)
        })

        card.addView(TextView(this).apply {
            text = "${b.serviceTitle}\nAddress: ${b.pickupAddress} (${b.barangayZone}, ${b.municipality})\nBase Fee: PHP ${"%.2f".format(b.quotedFee)} | 100% Tip: PHP ${"%.2f".format(b.tipAmount)} | Est. Hold ($commPct%): PHP ${"%.2f".format(reqComm)}"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setPadding(0, 2, 0, 10)
        })

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
                val myActiveBid = AlistoKernelClient.bookingBids.find {
                    it.bookingId == b.id && it.contractorId == user.id && it.status == "ACTIVE"
                }

                card.addView(Button(this).apply {
                    text = if (myActiveBid != null) {
                        "UPDATE MY OPEN BID (CURRENT: PHP ${"%.2f".format(myActiveBid.bidAmountPhp)})"
                    } else {
                        "SUBMIT OPEN BID (PRE-BID WALLET CHECK • TELEMETRY OFF)"
                    }
                    setBackgroundColor(Color.parseColor("#38BDF8"))
                    setTextColor(Color.parseColor("#070D19"))
                    setTypeface(null, Typeface.BOLD)
                    setOnClickListener { promptSubmitOpenBid(b) }
                })

                if (myActiveBid != null) {
                    card.addView(Button(this).apply {
                        text = "WITHDRAW MY BID (PHP ${"%.2f".format(myActiveBid.bidAmountPhp)})"
                        setBackgroundColor(Color.parseColor("#7F1D1D"))
                        setTextColor(Color.WHITE)
                        setOnClickListener {
                            uiScope.launch {
                                val res = AlistoKernelClient.withdrawContractorBidRpc(b.id)
                                res.onSuccess {
                                    Toast.makeText(this@ContractorMainActivity, "Bid withdrawn.", Toast.LENGTH_SHORT).show()
                                    routeContractorByAccountState()
                                }
                            }
                        }
                    })
                }

                card.addView(Button(this).apply {
                    text = "INSTANT CLAIM JOB AT QUOTED FEE (LOCK $commPct%: PHP ${"%.2f".format(reqComm)})"
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
                })
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
                        realtimePollJob?.cancel()
        AlistoSpatialEngine.stopHeartbeat()
                        transitionJob(b.id, "COMPLETED")
                    }
                })
            }
            "COMPLETED" -> {
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
                        routeContractorByAccountState()
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


    private fun initiateContractorPaymentFlow(
        purpose: String,
        amountPhp: Double,
        tierCode: String?,
        titleLabel: String
    ) {
        val payInfo = AlistoKernelClient.currentPaymentModeInfo
        if (payInfo.paymentMode == "MANUAL") {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 24, 36, 16)
                setBackgroundColor(Color.parseColor("#0F172A"))
            }
            layout.addView(TextView(this).apply {
                text = "Amount Due: PHP ${"%.2f".format(amountPhp)}\n\n${payInfo.manualInstructions}"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 13f
                setPadding(0, 0, 0, 14)
            })
            val refInput = styledInput("GCash / QR Ph / Clinic Receipt Reference No.")
            layout.addView(labeledField("Payment Reference Number", refInput))

            AlertDialog.Builder(this)
                .setTitle("Manual Payment • $titleLabel")
                .setView(layout)
                .setPositiveButton("Submit for Admin Approval") { _, _ ->
                    val ref = refInput.text.toString().trim()
                    uiScope.launch {
                        val res = AlistoKernelClient.submitManualPaymentRequestRpc(purpose, amountPhp, tierCode, ref)
                        res.onSuccess {
                            Toast.makeText(
                                this@ContractorMainActivity,
                                "Reference submitted! Admin will approve and credit shortly.",
                                Toast.LENGTH_LONG
                            ).show()
                            routeContractorByAccountState()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Submission Error")
                                .setMessage(err.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        // PAYMONGO_TEST or PAYMONGO_LIVE checkout via dynamic Edge Function
        Toast.makeText(this, "Preparing PayMongo (${payInfo.paymentMode}) checkout...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            val res = AlistoKernelClient.initiatePayMongoCheckout(purpose, amountPhp, tierCode)
            res.onSuccess { json ->
                val url = json.optString("checkout_url", "")
                val txRef = json.optString("tx_ref", "")
                Toast.makeText(this@ContractorMainActivity, "Opening PayMongo Checkout [$txRef]", Toast.LENGTH_LONG).show()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                routeContractorByAccountState()
            }.onFailure { err ->
                AlertDialog.Builder(this@ContractorMainActivity)
                    .setTitle("PayMongo Checkout Gate")
                    .setMessage(err.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun startContractorRealtimeLoop() {
        realtimePollJob?.cancel()
        realtimePollJob = uiScope.launch {
            while (isActive) {
                delay(15_000L)
                if (AlistoKernelClient.currentSessionToken.isNullOrBlank()) break
                AlistoKernelClient.syncBookingsAndWallet()
                val fired = AlistoPushCenter.dispatchUnreadNotifications(this@ContractorMainActivity, uiScope)
                contractorMapView?.updateMapLayers(
                    bookings = AlistoKernelClient.bookings,
                    telemetryPins = AlistoKernelClient.liveTelemetryPins,
                    heatClusters = AlistoKernelClient.heatmapClusters
                )
                if (fired > 0) {
                    Toast.makeText(this@ContractorMainActivity, "$fired new dispatch alert(s)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AlistoSpatialEngine.stopHeartbeat()
        uiScope.cancel()
    }
}
