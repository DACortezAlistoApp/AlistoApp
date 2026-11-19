package ph.alisto.customer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import ph.alisto.kernel.*

class CustomerMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectedVerticalKey = "skills_marketplace"
    private var entryMode = "LOGIN"
    private var compressedProfilePhotoDataUrl: String? = null
    private val sequentialWaypoints = mutableListOf<JSONObject>()
    private var realtimePollJob: Job? = null
    private var customerMapView: AlistoInteractiveMapView? = null
    private var mapTapMode: String = "WAYPOINT"
    private var activeOriginLat: Double = 15.1450
    private var activeOriginLng: Double = 120.5887

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AlistoKernelClient.currentSessionUser != null) {
            showAuthenticatedCustomerPortal()
        } else if (AlistoBiometricVault.hasSavedSession(this, "customer")) {
            triggerFrictionlessBiometricEntry()
        } else {
            showEntryPortalGate()
        }
    }

    private fun triggerFrictionlessBiometricEntry() {
        AlistoBiometricVault.promptBiometricUnlock(
            activity = this,
            portalRole = "customer",
            onUnlockedUserId = { savedUid ->
                uiScope.launch {
                    val res = AlistoKernelClient.fetchAppBootStateRpc(savedUid, "customer")
                    res.onSuccess { showAuthenticatedCustomerPortal() }
                        .onFailure { showEntryPortalGate() }
                }
            },
            onFallbackOrError = { showEntryPortalGate() }
        )
    }

    private fun showEntryPortalGate() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070A12")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 54, 42, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "ALISTO APP • CUSTOMER",
                "Nationwide Local-Road (avoid=ht) • Biometric Keystore"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 20) }
            }
        )

        if (AlistoBiometricVault.hasSavedSession(this, "customer")) {
            container.addView(Button(this).apply {
                text = "BIOMETRIC QUICK UNLOCK (${AlistoBiometricVault.getSavedUserLabel(this@CustomerMainActivity, "customer")})"
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener { triggerFrictionlessBiometricEntry() }
            })
        }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 20)
            addView(Button(this@CustomerMainActivity).apply {
                text = "LOG IN"
                setBackgroundColor(Color.parseColor(if (entryMode == "LOGIN") "#00F2FE" else "#1E293B"))
                setTextColor(Color.parseColor(if (entryMode == "LOGIN") "#070A12" else "#FFFFFF"))
                setOnClickListener { entryMode = "LOGIN"; showEntryPortalGate() }
            })
            addView(Button(this@CustomerMainActivity).apply {
                text = "CUSTOMER SIGN UP"
                setBackgroundColor(Color.parseColor(if (entryMode == "SIGNUP") "#00F2FE" else "#1E293B"))
                setTextColor(Color.parseColor(if (entryMode == "SIGNUP") "#070A12" else "#FFFFFF"))
                setOnClickListener { entryMode = "SIGNUP"; showEntryPortalGate() }
            })
        }
        container.addView(tabRow)

        if (entryMode == "LOGIN") {
            val idInput = styledInput("Username, Mobile Phone (+63...), or Email")
            container.addView(labeledField("Customer Login Identifier", idInput))

            val pwInput = styledInput("Permanent Non-Expiring Password").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            container.addView(labeledField("Password", pwInput))

            container.addView(Button(this).apply {
                text = "SIGN IN & BIND ANDROID BIOMETRIC KEYSTORE"
                setBackgroundColor(Color.parseColor("#00F2FE"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    uiScope.launch {
                        val res = AlistoKernelClient.authenticateUserRpc(idInput.text.toString(), pwInput.text.toString(), "customer")
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(this@CustomerMainActivity, "customer", AlistoKernelClient.currentSessionToken ?: "", "${user.nickname} (@${user.username})")
                            showAuthenticatedCustomerPortal()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity).setTitle("Login Denied").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }
            })
        } else {
            val fullNameInput = styledInput("Legal Full Name (e.g., Clarisse Dizon)")
            container.addView(labeledField("Full Name (Mandatory)", fullNameInput))
            val nicknameInput = styledInput("Preferred Nickname (e.g., Ate Clarisse)")
            container.addView(labeledField("Preferred Nickname for Contractor Greetings", nicknameInput))
            val phoneInput = styledInput("+63917XXXXXXX")
            container.addView(labeledField("Mobile Phone (Must remain active for contractor coordination calls)", phoneInput))
            val emailInput = styledInput("customer@email.com")
            container.addView(labeledField("Email Address", emailInput))
            val pwInput = styledInput("Create Permanent Password").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            container.addView(labeledField("Permanent Password", pwInput))
            val addrInput = styledInput("House No., Street, Barangay, Municipality")
            container.addView(labeledField("Registered Address & Map Pin", addrInput))

            var homeLat = 15.1450
            var homeLng = 120.5887
            val gpsCoordText = TextView(this).apply {
                text = "Reverse-Geocoded Pin: ${AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, homeLat, homeLng)}"
                setTextColor(Color.parseColor("#34D399"))
                textSize = 12f
            }
            container.addView(gpsCoordText)

            container.addView(Button(this).apply {
                text = "USE CURRENT GPS LOCATION (REVERSE-GEOCODE PIN)"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.parseColor("#00F2FE"))
                setOnClickListener {
                    homeLat = 15.1450 + (Math.random() * 0.009)
                    homeLng = 120.5887 + (Math.random() * 0.009)
                    val rev = AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, homeLat, homeLng)
                    addrInput.setText(rev)
                    gpsCoordText.text = "Snapped & Reverse-Geocoded: $rev"
                }
            })

            container.addView(Button(this).apply {
                text = "COMPLETE SIGN-UP & BIND BIOMETRIC KEYSTORE"
                setBackgroundColor(Color.parseColor("#00F2FE"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    uiScope.launch {
                        val res = AlistoKernelClient.registerCustomerPortalRpc(
                            fullName = fullNameInput.text.toString().trim(),
                            nickname = nicknameInput.text.toString().trim(),
                            phone = phoneInput.text.toString().trim(),
                            email = emailInput.text.toString().trim(),
                            password = pwInput.text.toString().trim(),
                            municipality = "Angeles City",
                            barangayZone = "Malabanias",
                            addressText = addrInput.text.toString().ifBlank { "Angeles City" },
                            homeLat = homeLat,
                            homeLng = homeLng,
                            profilePhotoUrl = compressedProfilePhotoDataUrl
                        )
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(this@CustomerMainActivity, "customer", AlistoKernelClient.currentSessionToken ?: "", "${user.nickname} (@${user.username})")
                            showAuthenticatedCustomerPortal()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity).setTitle("Sign-Up Error").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }
            })
        }
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun showAuthenticatedCustomerPortal() {
        val user = AlistoKernelClient.currentSessionUser ?: return showEntryPortalGate()
        AlistoPushCenter.ensureNotificationPermission(this)
        AlistoPushCenter.dispatchUnreadNotifications(this, uiScope)
        activeOriginLat = user.homeLat
        activeOriginLng = user.homeLng
        startCustomerRealtimeLoop()
        AlistoComplianceGate.checkOtaUpdate(this, "customer", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        AlistoComplianceGate.enforceRa10173ConsentIfRequired(this, uiScope, "customer")
        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070D19")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "Hi, ${user.nickname}! (@${user.username})",
                "Trust Score: ${"%.0f".format(user.customerTrustScore)}% (UP: ${user.thumbsUpCount} / DOWN: ${user.thumbsDownCount})"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }
            }
        )

        val sessionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
            addView(Button(this@CustomerMainActivity).apply {
                text = "Refresh Bids & Status"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    uiScope.launch {
                        AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "customer")
                        showAuthenticatedCustomerPortal()
                    }
                }
            })
            addView(Button(this@CustomerMainActivity).apply {
                text = "Nav Home (avoid=ht)"
                setBackgroundColor(Color.parseColor("#064E3B"))
                setTextColor(Color.parseColor("#34D399"))
                setOnClickListener {
                    AlistoSpatialEngine.launchLocalRoadNavigation(this@CustomerMainActivity, user.homeLat, user.homeLng)
                }
            })
            addView(Button(this@CustomerMainActivity).apply {
                text = "Lock"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener { realtimePollJob?.cancel(); AlistoKernelClient.signOut(); showEntryPortalGate() }
            })
        }
        container.addView(sessionBar)

        container.addView(sectionTitle("Live Tracking & Tap-to-Pin Map (avoid=ht)"))
        val mapModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
            val btnWp = Button(this@CustomerMainActivity)
            val btnOrig = Button(this@CustomerMainActivity)
            fun styleMapBtns() {
                btnWp.text = "Tap Map: +Waypoint"
                btnWp.setBackgroundColor(Color.parseColor(if (mapTapMode == "WAYPOINT") "#FBBF24" else "#1E293B"))
                btnWp.setTextColor(Color.parseColor(if (mapTapMode == "WAYPOINT") "#070D19" else "#FFFFFF"))
                btnOrig.text = "Tap Map: Set Origin Pin"
                btnOrig.setBackgroundColor(Color.parseColor(if (mapTapMode == "ORIGIN") "#10B981" else "#1E293B"))
                btnOrig.setTextColor(Color.parseColor(if (mapTapMode == "ORIGIN") "#070D19" else "#FFFFFF"))
            }
            styleMapBtns()
            btnWp.setOnClickListener { mapTapMode = "WAYPOINT"; styleMapBtns() }
            btnOrig.setOnClickListener { mapTapMode = "ORIGIN"; styleMapBtns() }
            addView(btnWp)
            addView(btnOrig)
        }
        container.addView(mapModeRow)

        val liveTrackingBadge = TextView(this).apply {
            val pins = AlistoKernelClient.liveTelemetryPins.size
            text = if (pins > 0) "LIVE TRACKING: $pins contractor(s) streaming 10s GPS • Tap map to pin stops" else "Tap anywhere on the map to pin origin or numbered errand stops"
            setTextColor(Color.parseColor("#00F2FE"))
            textSize = 12f
            setPadding(0, 0, 0, 6)
        }
        container.addView(liveTrackingBadge)

        val mapContainer = AlistoInteractiveMapView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (270 * resources.displayMetrics.density).toInt()
            ).apply { setMargins(0, 0, 0, 14) }
        }
        customerMapView = mapContainer
        container.addView(mapContainer)

                container.addView(sectionTitle("1. Select Service Vertical & Subtype"))
        val subtypeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("ON_SITE_SKILL", "ERRAND_PURCHASING", "POINT_TO_POINT")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        container.addView(labeledField("Workflow Subtype (On-Site Pin vs. Multi-Stop Pabili/Errand Waypoints)", subtypeSpinner))

        val titleInput = styledInput("e.g., Split-Type Aircon Cleaning OR Multi-Stop Pabili Errand")
        container.addView(labeledField("Service / Errand Title", titleInput))

        val pickupInput = styledInput(user.addressText.ifBlank { "Origin / Job Site Pin (${user.barangayZone})" })
        container.addView(labeledField("Origin / Job Site Pin (Reverse-Geocoded)", pickupInput))

        val waypointsStatus = TextView(this).apply {
            text = if (sequentialWaypoints.isEmpty()) "Numbered Waypoints: None (Single Job Site Pin)" else "Waypoints: ${sequentialWaypoints.size}"
            setTextColor(Color.parseColor("#00F2FE"))
            textSize = 12f
            setPadding(0, 4, 0, 6)
        }
        container.addView(waypointsStatus)

        fun refreshCustomerMapLayers() {
            customerMapView?.updateMapLayers(
                originLat = activeOriginLat,
                originLng = activeOriginLng,
                originAddress = pickupInput.text.toString().ifBlank { user.addressText },
                waypoints = sequentialWaypoints,
                bookings = AlistoKernelClient.bookings.filter { user.role == "super_admin" || it.clientId == user.id },
                telemetryPins = AlistoKernelClient.liveTelemetryPins
            )
        }
        customerMapView?.loadInteractiveMap(activeOriginLat, activeOriginLng, enableTapToPin = true) { lat, lng ->
            val rev = AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, lat, lng)
            if (mapTapMode == "ORIGIN") {
                activeOriginLat = lat
                activeOriginLng = lng
                pickupInput.setText(rev)
                Toast.makeText(this@CustomerMainActivity, "Origin pinned: $rev", Toast.LENGTH_SHORT).show()
            } else {
                val stopNum = sequentialWaypoints.size + 1
                sequentialWaypoints.add(JSONObject().apply {
                    put("stop_number", stopNum)
                    put("lat", lat)
                    put("lng", lng)
                    put("address", rev)
                })
                waypointsStatus.text = "Sequential Waypoints: " + sequentialWaypoints.joinToString(" -> ") { "#${it.getInt("stop_number")}: ${it.getString("address")}" }
                Toast.makeText(this@CustomerMainActivity, "Stop #$stopNum pinned: $rev", Toast.LENGTH_SHORT).show()
            }
            refreshCustomerMapLayers()
        }
        refreshCustomerMapLayers()

        container.addView(Button(this).apply {
            text = "+ DROP NUMBERED WAYPOINT PIN (#${sequentialWaypoints.size + 1}) & REVERSE-GEOCODE"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#FBBF24"))
            setOnClickListener {
                val stopNum = sequentialWaypoints.size + 1
                val wLat = user.homeLat + (stopNum * 0.003)
                val wLng = user.homeLng + (stopNum * 0.003)
                val addr = AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, wLat, wLng)
                sequentialWaypoints.add(JSONObject().apply {
                    put("stop_number", stopNum)
                    put("lat", wLat)
                    put("lng", wLng)
                    put("address", addr)
                })
                waypointsStatus.text = "Sequential Waypoints: " + sequentialWaypoints.joinToString(" -> ") { "#${it.getInt("stop_number")}: ${it.getString("address")}" }
            }
        })

        val feeInput = styledInput("500").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Service / Labor Fee Offer (PHP — Pabili goods are settled via Chat/GCash)", feeInput))

        val errandCostInput = styledInput("0").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Est. Pabili Goods Reimbursement (PHP — <₱500 adds +₱10–₱15 GCash Buffer; price shifts resolved via Chat)", errandCostInput))

        val deadheadKmInput = styledInput("0").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Out-of-Zone Return Distance (km — Optional ₱2.00/km Deadhead Offset, 100% to Rider)", deadheadKmInput))

        val tipInput = styledInput("50").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Optional Tip (PHP — 100% Protected for Contractor/Rider)", tipInput))

        container.addView(Button(this).apply {
            text = "BROADCAST BOOKING / OPEN BIDDING"
            setBackgroundColor(Color.parseColor("#00F2FE"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val subtype = subtypeSpinner.selectedItem.toString()
                val title = titleInput.text.toString().ifBlank { "On-Demand Service / Errand" }
                val addr = pickupInput.text.toString().ifBlank { user.addressText }
                val fee = feeInput.text.toString().toDoubleOrNull() ?: 500.0
                val errandCost = errandCostInput.text.toString().toDoubleOrNull() ?: 0.0
                val deadheadKm = deadheadKmInput.text.toString().toDoubleOrNull() ?: 0.0
                val tip = tipInput.text.toString().toDoubleOrNull() ?: 0.0
                val wpArr = JSONArray().apply { sequentialWaypoints.forEach { put(it) } }

                uiScope.launch {
                    val res = AlistoKernelClient.createBookingV2Rpc(
                        verticalKey = selectedVerticalKey,
                        jobSubtype = subtype,
                        municipality = user.municipality,
                        barangayZone = user.barangayZone,
                        skillCategory = "General Service / Errand",
                        serviceTitle = title,
                        pickupAddress = addr,
                        dropoffAddress = addr,
                        waypointsJson = wpArr,
                        quotedFee = fee,
                        tipAmount = tip,
                        errandItemCostPhp = errandCost,
                        deadheadReturnKm = deadheadKm,
                        pickupLat = activeOriginLat,
                        pickupLng = activeOriginLng
                    )
                    res.onSuccess { json ->
                        val buf = json.optDouble("gcash_cashout_buffer_php", 0.0)
                        val dh = json.optDouble("deadhead_surcharge_php", 0.0)
                        sequentialWaypoints.clear()
                        Toast.makeText(
                            this@CustomerMainActivity,
                            "Broadcast Live! GCash Buffer: PHP $buf | Deadhead Offset: PHP $dh",
                            Toast.LENGTH_LONG
                        ).show()
                        showAuthenticatedCustomerPortal()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@CustomerMainActivity)
                            .setTitle("Booking / Trust Deposit Gate")
                            .setMessage(err.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        })

        container.addView(sectionTitle("2. My Bookings, Incoming Open Bids & Post-Completion CSAT"))
        AlistoKernelClient.bookings.filter { user.role == "super_admin" || it.clientId == user.id }.forEach { b ->
            container.addView(buildCustomerBookingCard(b))
        }

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun buildCustomerBookingCard(b: BookingRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        card.addView(TextView(this).apply {
            text = "${b.bookingRef} • [${b.status}] — ${b.serviceTitle}"
            setTextColor(Color.parseColor("#FBBF24"))
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
        })

        val contractorRepLine = if (b.contractorFullName != null) {
            val vipTag = if (b.contractorVipBadge) " [★ VIP GRIND]" else ""
            "\nAssigned Contractor: ${b.contractorFullName}$vipTag • ★ ${"%.2f".format(b.contractorRatingAvg)} (${b.contractorRatingCount} reviews)"
        } else {
            "\nStatus: Open for Contractor Bidding / Dispatch"
        }

        card.addView(TextView(this).apply {
            text = "Address: ${b.pickupAddress}\nService Fee: PHP ${"%.2f".format(b.quotedFee)} | Tip (100% Contractor): PHP ${"%.2f".format(b.tipAmount)}$contractorRepLine"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setPadding(0, 4, 0, 10)
        })

        // INCOMING OPEN BIDS REVIEW & ACCEPTANCE (For PENDING / SEARCHING Bookings)
        if (b.status in listOf("PENDING", "SEARCHING", "CREATED")) {
            val matchingBids = AlistoKernelClient.bookingBids.filter { it.bookingId == b.id && it.status == "ACTIVE" }
            if (matchingBids.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = "INCOMING CONTRACTOR BIDS (${matchingBids.size}):"
                    setTextColor(Color.parseColor("#00F2FE"))
                    setTypeface(null, Typeface.BOLD)
                    textSize = 12.5f
                    setPadding(0, 6, 0, 6)
                })
                matchingBids.forEach { bid ->
                    val bidBox = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(18, 14, 18, 14)
                        setBackgroundColor(Color.parseColor("#0F172A"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 0, 10) }
                    }
                    val vipStr = if (bid.contractorVipBadge) " [★ VIP]" else ""
                    val highlightsStr = if (bid.reviewHighlights.isNotEmpty()) "\nHighlights: ${bid.reviewHighlights.joinToString(" • ")}" else ""
                    bidBox.addView(TextView(this).apply {
                        text = "${bid.contractorName} (@${bid.contractorUsername})$vipStr\n" +
                               "Reputation: ★ ${"%.2f".format(bid.contractorRatingAvg)} (${bid.contractorRatingCount} reviews)$highlightsStr\n" +
                               "Bid Offer: PHP ${"%.2f".format(bid.bidAmountPhp)} • Est. Arrival: ${bid.estimatedEtaMin} mins"
                        setTextColor(Color.parseColor("#E2E8F0"))
                        textSize = 12f
                    })
                    bidBox.addView(Button(this).apply {
                        text = "ACCEPT THIS BID (LOCK PHP ${"%.2f".format(bid.bidAmountPhp)})"
                        setBackgroundColor(Color.parseColor("#10B981"))
                        setTextColor(Color.parseColor("#070D19"))
                        setTypeface(null, Typeface.BOLD)
                        setOnClickListener {
                            uiScope.launch {
                                val res = AlistoKernelClient.acceptContractorBidRpc(b.id, bid.id)
                                res.onSuccess {
                                    Toast.makeText(this@CustomerMainActivity, "Bid Accepted! Contractor assigned.", Toast.LENGTH_LONG).show()
                                    showAuthenticatedCustomerPortal()
                                }.onFailure { err ->
                                    AlertDialog.Builder(this@CustomerMainActivity).setTitle("Bid Acceptance Error").setMessage(err.message).setPositiveButton("OK", null).show()
                                }
                            }
                        }
                    })
                    card.addView(bidBox)
                }
            }
        }

        // POST-COMPLETION 1-5 STAR RATING & CATEGORY TAGS (Engine 07)
        if (b.status == "COMPLETED" && b.contractorId != null) {
            card.addView(Button(this).apply {
                text = "★ RATE CONTRACTOR (1–5 STARS & FEEDBACK TAGS)"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070D19"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener { openCustomerStarRatingDialog(b) }
            })
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
            addView(Button(this@CustomerMainActivity).apply {
                text = "Chat (Engine 11)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener { openChatDialog(b) }
            })
            if (b.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")) {
                addView(Button(this@CustomerMainActivity).apply {
                    text = "50/50 Mutual Cancel"
                    setBackgroundColor(Color.parseColor("#7F1D1D"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        uiScope.launch {
                            AlistoKernelClient.executeMutualOrNoShowCancelRpc(b.id, "MUTUAL_CONFIRMED", "Customer & Contractor mutual cancel")
                            showAuthenticatedCustomerPortal()
                        }
                    }
                })
                addView(Button(this@CustomerMainActivity).apply {
                    text = "30m No-Show"
                    setBackgroundColor(Color.parseColor("#451A03"))
                    setTextColor(Color.parseColor("#FBBF24"))
                    setOnClickListener {
                        uiScope.launch {
                            AlistoKernelClient.executeMutualOrNoShowCancelRpc(b.id, "CONTRACTOR_NO_SHOW_30MIN", "Contractor >30m no-show")
                            showAuthenticatedCustomerPortal()
                        }
                    }
                })
            }
        }
        card.addView(btnRow)
        return card
    }

    private fun openCustomerStarRatingDialog(b: BookingRecord) {
        val contractorId = b.contractorId ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 16)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val starSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("5 Stars (★★★★★ - Excellent)", "4 Stars (★★★★☆ - Good)", "3 Stars (★★★☆☆ - Fair)", "2 Stars (★★☆☆☆ - Poor)", "1 Star (★☆☆☆☆ - Unsatisfactory)")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        layout.addView(labeledField("Select Star Rating (1 to 5 Stars)", starSpinner))

        val tagOptions = listOf("Punctual Arrival", "Quality Workmanship", "Fair & Transparent Pricing", "Polite & Professional")
        val tagBoxes = tagOptions.map { tag ->
            CheckBox(this).apply {
                text = tag
                setTextColor(Color.WHITE)
                isChecked = true
            }
        }
        tagBoxes.forEach { layout.addView(it) }

        val commentInput = styledInput("Write optional review comment...")
        layout.addView(labeledField("Review Comments", commentInput))

        AlertDialog.Builder(this)
            .setTitle("Rate Contractor • ${b.bookingRef}")
            .setView(layout)
            .setPositiveButton("Submit CSAT Rating") { _, _ ->
                val stars = 5 - starSpinner.selectedItemPosition
                val selectedTags = tagBoxes.filter { it.isChecked }.map { it.text.toString() }
                val comments = commentInput.text.toString().ifBlank { "Great service!" }
                uiScope.launch {
                    val res = AlistoKernelClient.submitCustomerStarRatingRpc(b.id, contractorId, stars, selectedTags, comments)
                    res.onSuccess {
                        Toast.makeText(this@CustomerMainActivity, "Thank you! $stars★ CSAT rating recorded.", Toast.LENGTH_LONG).show()
                        showAuthenticatedCustomerPortal()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openChatDialog(booking: BookingRecord) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val msgInput = styledInput("Coordinate bid details or resolve pabili item price shifts...")
        dialogLayout.addView(msgInput)

        AlertDialog.Builder(this)
            .setTitle("Engine 11 Chat • ${booking.bookingRef}")
            .setView(dialogLayout)
            .setPositiveButton("Send") { _, _ ->
                val body = msgInput.text.toString()
                if (body.isNotBlank()) {
                    uiScope.launch {
                        val res = AlistoKernelClient.sendModeratedChatMessageRpc(booking.id, body)
                        res.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity).setTitle("Moderation Block").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#FBBF24"))
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
        addView(TextView(this@CustomerMainActivity).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 0, 0, 4)
        })
        addView(field)
    }

    private fun startCustomerRealtimeLoop() {
        realtimePollJob?.cancel()
        realtimePollJob = uiScope.launch {
            while (isActive) {
                delay(15_000L)
                if (AlistoKernelClient.currentSessionToken.isNullOrBlank()) break
                AlistoKernelClient.syncBookingsAndWallet()
                val fired = AlistoPushCenter.dispatchUnreadNotifications(this@CustomerMainActivity, uiScope)
                val u = AlistoKernelClient.currentSessionUser
                if (u != null) {
                    customerMapView?.updateMapLayers(
                        originLat = activeOriginLat,
                        originLng = activeOriginLng,
                        originAddress = u.addressText,
                        waypoints = sequentialWaypoints,
                        bookings = AlistoKernelClient.bookings.filter { u.role == "super_admin" || it.clientId == u.id },
                        telemetryPins = AlistoKernelClient.liveTelemetryPins
                    )
                }
                if (fired > 0) {
                    Toast.makeText(this@CustomerMainActivity, "$fired new live update(s) received", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        realtimePollJob?.cancel()
        uiScope.cancel()
    }
}
