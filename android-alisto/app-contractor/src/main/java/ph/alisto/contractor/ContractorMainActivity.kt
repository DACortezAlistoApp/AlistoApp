package ph.alisto.contractor

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import ph.alisto.kernel.*
import ph.alisto.kernel.AlistoUiKit.dp

class ContractorMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var entryMode = "LOGIN"
    private var activeTab = "DRIVE"
    private var jobsFilter = "OPEN"
    private var autoAcceptEnabled = false
    private var promptedEarnModeThisSession = false
    private var lastBookingsDigest: String = ""
    private var realtimePollJob: Job? = null
    private var contractorMapView: AlistoInteractiveMapView? = null
    private var promptedLocationPerm = false
    private var hasAutoCenteredGps = false
    private var compressedProfileUrl: String? = null
    private var compressedLicenseUrl: String? = null
    private var compressedOrcrUrl: String? = null
    private var compressedVehicleUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlistoUiKit.applyDarkSystemBars(this)
        if (AlistoKernelClient.currentSessionUser != null) {
            routeContractorByAccountState()
        } else if (AlistoBiometricVault.hasSavedSession(this, "contractor")) {
            triggerFrictionlessBiometricEntry()
        } else {
            showContractorEntryGate()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AlistoLocationTracker.REQ_LOCATION_PERMISSION && AlistoLocationTracker.hasPermission(this)) {
            startContractorLiveGps(forceCenter = true)
        }
    }

    private fun startContractorLiveGps(forceCenter: Boolean) {
        if (!AlistoLocationTracker.hasPermission(this)) return
        AlistoLocationTracker.startLiveUpdates(this) { lat, lng, acc ->
            val shouldFly = forceCenter || !hasAutoCenteredGps
            contractorMapView?.updateUserLocation(lat, lng, acc, flyTo = shouldFly)
            if (shouldFly) hasAutoCenteredGps = true
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
        AlistoLocationTracker.stopLiveUpdates()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(36), dp(22), dp(36))
        }

        container.addView(
            AlistoUiKit.authBrandHero(
                this,
                "ALISTO PRO",
                "Contractor & Rider Command • Skills, Errands & Local Rides",
                AlistoUiKit.AMBER
            )
        )

        if (AlistoBiometricVault.hasSavedSession(this, "contractor")) {
            val savedLabel = AlistoBiometricVault.getSavedUserLabel(this, "contractor")
            container.addView(
                AlistoUiKit.pillButton(
                    this, "Quick Unlock • $savedLabel", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP
                ) { triggerFrictionlessBiometricEntry() }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(16) }
                }
            )
        }

        val formCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 18)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = AlistoUiKit.roundedBg(this@ContractorMainActivity, AlistoUiKit.BG_INPUT, 22, AlistoUiKit.BORDER)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }

            addView(AlistoUiKit.chipButton(this@ContractorMainActivity, "Log In", entryMode == "LOGIN", AlistoUiKit.AMBER) {
                entryMode = "LOGIN"; showContractorEntryGate()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })

            addView(AlistoUiKit.chipButton(this@ContractorMainActivity, "Apply as Pro", entryMode == "SIGNUP", AlistoUiKit.AMBER) {
                entryMode = "SIGNUP"; showContractorEntryGate()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        }
        formCard.addView(tabRow)

        if (entryMode == "LOGIN") {
            val idInput = AlistoUiKit.inputField(this, "Username (e.g., marco), phone, or email")
            formCard.addView(AlistoUiKit.labeledField(this, "Contractor Login", idInput))

            val pwInput = AlistoUiKit.inputField(
                this, "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            formCard.addView(AlistoUiKit.labeledField(this, "Password", pwInput, bottomMarginDp = 18))

            formCard.addView(
                AlistoUiKit.pillButton(this, "Sign In", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP) {
                    uiScope.launch {
                        val res = AlistoKernelClient.authenticateUserRpc(
                            idInput.text.toString(), pwInput.text.toString(), "contractor"
                        )
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(
                                this@ContractorMainActivity, "contractor",
                                AlistoKernelClient.currentSessionToken ?: "",
                                "${user.fullName} (@${user.username})"
                            )
                            activeTab = "DRIVE"
                            routeContractorByAccountState()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Login Failed").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
            )
        } else {
            val fnInput = AlistoUiKit.inputField(this, "First Name")
            formCard.addView(AlistoUiKit.labeledField(this, "First Name", fnInput))
            val lnInput = AlistoUiKit.inputField(this, "Last Name")
            formCard.addView(AlistoUiKit.labeledField(this, "Last Name", lnInput))
            val phoneInput = AlistoUiKit.inputField(this, "+63917XXXXXXX", InputType.TYPE_CLASS_PHONE)
            formCard.addView(AlistoUiKit.labeledField(this, "Mobile Number (KYC Call)", phoneInput))
            val emailInput = AlistoUiKit.inputField(this, "contractor@alisto.ph", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            formCard.addView(AlistoUiKit.labeledField(this, "Email Address", emailInput))
            val pwInput = AlistoUiKit.inputField(
                this, "Create permanent password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            formCard.addView(AlistoUiKit.labeledField(this, "Password", pwInput, bottomMarginDp = 18))

            formCard.addView(
                AlistoUiKit.pillButton(this, "Submit Application", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP) {
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
                            AlistoBiometricVault.saveEncryptedSession(
                                this@ContractorMainActivity, "contractor",
                                AlistoKernelClient.currentSessionToken ?: "",
                                "${user.fullName} (@${user.username})"
                            )
                            routeContractorByAccountState()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Application Error").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
            )
        }

        container.addView(formCard)
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun routeContractorByAccountState() {
        val user = AlistoKernelClient.currentSessionUser ?: return showContractorEntryGate()
        autoAcceptEnabled = AlistoKernelClient.currentContractorProfile?.autoAcceptEnabled ?: false
        when (user.accountStatus) {
            "PENDING_REVIEW", "RESUBMISSION_REQUIRED", "SUSPENDED" -> {
                val scroll = ScrollView(this).apply {
                    setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
                    isFillViewport = true
                }
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(22), dp(48), dp(22), dp(36))
                }
                val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.AMBER, padDp = 22)
                card.addView(AlistoUiKit.badgePill(this, user.accountStatus, "#451A03", AlistoUiKit.AMBER))
                card.addView(TextView(this).apply {
                    text = "Verification In Progress"
                    textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                    setPadding(0, dp(12), 0, dp(6))
                })
                card.addView(TextView(this).apply {
                    text = "${user.fullName} (@${user.username})\n\nOur operations team is verifying your KYC documents. Tap Refresh once activated."
                    textSize = 13.5f
                    setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                    setPadding(0, 0, 0, dp(18))
                })
                card.addView(
                    AlistoUiKit.pillButton(this, "Refresh Verification Status", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP) {
                        uiScope.launch {
                            AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "contractor")
                            routeContractorByAccountState()
                        }
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(10) }
                    }
                )
                card.addView(
                    AlistoUiKit.pillButton(this, "Sign Out", AlistoUiKit.BG_INPUT, AlistoUiKit.TEXT_MUTED, AlistoUiKit.BORDER_LIGHT, heightDp = 42) {
                        promptedEarnModeThisSession = false; AlistoKernelClient.signOut()
                        showContractorEntryGate()
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                )
                box.addView(card)
                scroll.addView(box)
                setContentView(scroll)
            }
            else -> showActiveContractorPortal(user)
        }
    }

    private fun getApprovedVerticals(prof: ContractorProfileState?): List<String> {
        if (prof == null) return emptyList()
        val list = mutableListOf<String>()
        if (prof.offersSkills) list.add("skills_marketplace")
        if (prof.offersDelivery) list.add("delivery_logistics")
        if (prof.offersRideHailing) list.add("ride_hailing")
        return list
    }

    private fun getEffectiveShiftVerticals(prof: ContractorProfileState?): List<String> {
        val approved = getApprovedVerticals(prof)
        if (approved.isEmpty()) return emptyList()
        val active = prof?.activeShiftVerticals.orEmpty().filter { it in approved }
        return if (active.isNotEmpty()) active else approved
    }

    private fun maybePromptEarnModeToday(user: AuthenticatedUser) {
        if (promptedEarnModeThisSession) return
        val prof = AlistoKernelClient.currentContractorProfile ?: return
        val hasRide = prof.offersDelivery || prof.offersRideHailing
        val hasSkills = prof.offersSkills
        if (!hasRide || !hasSkills) {
            promptedEarnModeThisSession = true
            return
        }
        promptedEarnModeThisSession = true
        val rideVerticals = buildList {
            if (prof.offersDelivery) add("delivery_logistics")
            if (prof.offersRideHailing) add("ride_hailing")
        }
        val skillsVerticals = listOf("skills_marketplace")

        AlertDialog.Builder(this)
            .setTitle("How would you like to earn today?")
            .setMessage("Choose your active earning mode for this shift. You can switch anytime on the Drive tab.")
            .setCancelable(false)
            .setPositiveButton("Ride (Delivery & Local Ride)") { _, _ ->
                uiScope.launch {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(
                        isOnline = true,
                        autoAccept = autoAcceptEnabled,
                        activeShiftVerticals = rideVerticals
                    )
                    Toast.makeText(this@ContractorMainActivity, "Shift set: Ride (Delivery & Local Ride)", Toast.LENGTH_SHORT).show()
                    routeContractorByAccountState()
                }
            }
            .setNegativeButton("Skills Marketplace (Skills & Errands)") { _, _ ->
                autoAcceptEnabled = false
                uiScope.launch {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(
                        isOnline = true,
                        autoAccept = false,
                        activeShiftVerticals = skillsVerticals
                    )
                    Toast.makeText(this@ContractorMainActivity, "Shift set: Skills Marketplace", Toast.LENGTH_SHORT).show()
                    routeContractorByAccountState()
                }
            }
            .show()
    }

    private fun showActiveContractorPortal(user: AuthenticatedUser) {
        AlistoPushCenter.ensureNotificationPermission(this)
        AlistoPushCenter.dispatchUnreadNotifications(this, uiScope)
        startContractorRealtimeLoop()
        AlistoComplianceGate.checkOtaUpdate(this, "contractor", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        AlistoComplianceGate.enforceRa10173ConsentIfRequired(this, uiScope, "contractor")
        maybePromptEarnModeToday(user)
        if (!AlistoLocationTracker.hasPermission(this) && !promptedLocationPerm) {
            promptedLocationPerm = true
            AlistoLocationTracker.requestPermission(this)
        } else if (AlistoLocationTracker.hasPermission(this)) {
            startContractorLiveGps(forceCenter = false)
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }
        val contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        when (activeTab) {
            "JOBS" -> contentHost.addView(buildContractorJobsTab(user))
            "WALLET" -> contentHost.addView(buildContractorWalletTab(user))
            "PROFILE" -> contentHost.addView(buildContractorProfileTab(user))
            else -> contentHost.addView(buildContractorDriveMapTab(user))
        }

        val prof = AlistoKernelClient.currentContractorProfile
        val currentShifts = getEffectiveShiftVerticals(prof)
        val openCount = AlistoKernelClient.bookings.count {
            (it.verticalKey in currentShifts || it.contractorId == user.id) &&
                it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")
        }

        val bottomNav = AlistoUiKit.bottomNavBar(
            this,
            tabs = listOf(
                Triple("DRIVE", "◎", "Drive"),
                Triple("JOBS", "☰", if (openCount > 0) "Jobs ($openCount)" else "Jobs"),
                Triple("WALLET", "₱", "Wallet"),
                Triple("PROFILE", "●", "Profile")
            ),
            activeKey = activeTab,
            accentHex = AlistoUiKit.AMBER
        ) { nextTab ->
            activeTab = nextTab
            showActiveContractorPortal(user)
        }

        shell.addView(contentHost)
        shell.addView(bottomNav)
        setContentView(shell)
    }

    private fun buildContractorDriveMapTab(user: AuthenticatedUser): View {
        val prof = AlistoKernelClient.currentContractorProfile
        val approvedVerticals = getApprovedVerticals(prof)
        val currentShifts = getEffectiveShiftVerticals(prof)
        val isOnline = prof?.isOnline == true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }

        // Upper full-bleed demand heat map and live job radar
        val mapStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.56f)
        }
        val cMap = AlistoInteractiveMapView(this)
        contractorMapView = cMap
        mapStage.addView(
            cMap,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        val initLat = AlistoLocationTracker.lastLat ?: user.homeLat
        val initLng = AlistoLocationTracker.lastLng ?: user.homeLng
        cMap.loadInteractiveMap(initLat, initLng, enableTapToPin = false)
        AlistoLocationTracker.lastLat?.let { lat ->
            AlistoLocationTracker.lastLng?.let { lng ->
                cMap.updateUserLocation(lat, lng, AlistoLocationTracker.lastAccuracyM, flyTo = false)
            }
        }
        cMap.updateMapLayers(
            bookings = AlistoKernelClient.bookings,
            telemetryPins = AlistoKernelClient.liveTelemetryPins,
            heatClusters = AlistoKernelClient.heatmapClusters
        )

        // Floating top HUD: Wallet balance, GPS stream badge, Sync
        val topHud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        }
        val walletHudPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AlistoUiKit.roundedBg(this@ContractorMainActivity, "#E60D1424", 24, AlistoUiKit.BORDER_LIGHT)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dp(4).toFloat()
            setOnClickListener { activeTab = "WALLET"; showActiveContractorPortal(user) }
            addView(TextView(this@ContractorMainActivity).apply {
                text = "PHP ${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)}"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
            })
        }
        topHud.addView(walletHudPill, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })

        val gpsLive = AlistoSpatialEngine.isHeartbeatStreaming
        val gpsPill = TextView(this).apply {
            text = if (gpsLive) "● GPS LIVE" else "○ STANDBY"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (gpsLive) AlistoUiKit.EMERALD else AlistoUiKit.TEXT_MUTED))
            background = AlistoUiKit.roundedBg(
                this@ContractorMainActivity, "#E60D1424", 20,
                if (gpsLive) AlistoUiKit.EMERALD else AlistoUiKit.BORDER_LIGHT
            )
            setPadding(dp(11), dp(8), dp(11), dp(8))
            elevation = dp(4).toFloat()
        }
        topHud.addView(gpsPill)
        topHud.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        topHud.addView(AlistoUiKit.chipButton(this, "Sync", true, "#E60D1424", AlistoUiKit.AMBER) {
            uiScope.launch {
                AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "contractor")
                routeContractorByAccountState()
            }
        }.apply { elevation = dp(4).toFloat() })
        mapStage.addView(topHud)

        // Bottom-left radar telemetry pill (tap to recenter)
        val radarStats = TextView(this).apply {
            val openPins = AlistoKernelClient.bookings.count { it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED") }
            text = "Zones: ${AlistoKernelClient.heatmapClusters.size} • Open Pins: $openPins"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.CYAN))
            background = AlistoUiKit.roundedBg(this@ContractorMainActivity, "#E60D1424", 18, AlistoUiKit.BORDER_LIGHT)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            elevation = dp(4).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                leftMargin = dp(14)
                bottomMargin = dp(12)
            }
            setOnClickListener {
                if (!AlistoLocationTracker.hasPermission(this@ContractorMainActivity)) {
                    AlistoLocationTracker.requestPermission(this@ContractorMainActivity)
                } else {
                    val lat = AlistoLocationTracker.lastLat ?: user.homeLat
                    val lng = AlistoLocationTracker.lastLng ?: user.homeLng
                    contractorMapView?.recenter(lat, lng, 15)
                    startContractorLiveGps(forceCenter = true)
                }
            }
        }
        mapStage.addView(radarStats)
        root.addView(mapStage)

        // Lyft-driver rounded bottom sheet
        val sheetWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.topSheetBg(this@ContractorMainActivity, AlistoUiKit.BG_SURFACE, 24, AlistoUiKit.BORDER)
            elevation = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.44f)
        }
        sheetWrap.addView(AlistoUiKit.dragHandle(this))

        val sheetScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val sheetBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(18))
        }

        // Primary Go Online + Auto-Accept control bar
        sheetBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            addView(
                AlistoUiKit.pillButton(
                    this@ContractorMainActivity,
                    if (isOnline) "● ONLINE — PAUSE" else "GO ONLINE",
                    if (isOnline) AlistoUiKit.EMERALD else AlistoUiKit.AMBER,
                    AlistoUiKit.BG_DEEP, heightDp = 48, textSizeSp = 14f
                ) {
                    val nextOnline = !isOnline
                    uiScope.launch {
                        AlistoKernelClient.toggleContractorDispatchModeRpc(
                            isOnline = nextOnline, autoAccept = autoAcceptEnabled, activeShiftVerticals = currentShifts
                        )
                        routeContractorByAccountState()
                    }
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply { rightMargin = dp(8) } }
            )
            addView(
                AlistoUiKit.pillButton(
                    this@ContractorMainActivity,
                    if (autoAcceptEnabled) "Auto: ON" else "Open Board",
                    if (autoAcceptEnabled) "#0C4A6E" else AlistoUiKit.BG_CARD,
                    if (autoAcceptEnabled) AlistoUiKit.CYAN else AlistoUiKit.TEXT_MUTED,
                    if (autoAcceptEnabled) AlistoUiKit.CYAN else AlistoUiKit.BORDER_LIGHT,
                    heightDp = 48, textSizeSp = 12.5f
                ) {
                    autoAcceptEnabled = !autoAcceptEnabled
                    uiScope.launch {
                        AlistoKernelClient.toggleContractorDispatchModeRpc(
                            isOnline = true, autoAccept = autoAcceptEnabled, activeShiftVerticals = currentShifts
                        )
                        routeContractorByAccountState()
                    }
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f) }
            )
        })

        // Active assigned job spotlight card
        val myActiveJob = AlistoKernelClient.bookings.firstOrNull {
            it.contractorId == user.id && it.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")
        }
        if (myActiveJob != null) {
            val spotCard = AlistoUiKit.surfaceCard(this, "#0C2942", AlistoUiKit.CYAN, padDp = 14)
            spotCard.addView(TextView(this).apply {
                text = "ACTIVE JOB • ${myActiveJob.bookingRef} (${myActiveJob.status})"
                textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.CYAN))
            })
            val spotLines = buildString {
                append("${myActiveJob.serviceTitle} • ${myActiveJob.pickupAddress}")
                if (myActiveJob.pickupNote.isNotBlank()) append("\nPickup Note: ${myActiveJob.pickupNote}")
                if (myActiveJob.bookingNotes.isNotBlank()) append("\nSpecial Note: ${myActiveJob.bookingNotes}")
            }
            spotCard.addView(TextView(this).apply {
                text = spotLines
                textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                setPadding(0, dp(4), 0, dp(10))
            })
            spotCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(AlistoUiKit.pillButton(this@ContractorMainActivity, "Navigate", "#064E3B", "#34D399", "#10B981", heightDp = 40, textSizeSp = 12.5f) {
                    AlistoSpatialEngine.launchLocalRoadNavigation(this@ContractorMainActivity, myActiveJob.pickupLat, myActiveJob.pickupLng)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
                addView(AlistoUiKit.pillButton(this@ContractorMainActivity, "Open Job →", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP, heightDp = 40, textSizeSp = 12.5f) {
                    jobsFilter = "MINE"; activeTab = "JOBS"; showActiveContractorPortal(user)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            })
            sheetBody.addView(spotCard)
        }

        // Shift verticals quick-toggle chips
        sheetBody.addView(TextView(this).apply {
            text = "Active Shift Verticals (Tap to toggle)"
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            setPadding(dp(2), 0, 0, dp(8))
        })

        val shiftRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val verticalsSpec = listOf(
            "skills_marketplace" to "Skills",
            "delivery_logistics" to "Delivery",
            "ride_hailing" to "Ride-Hailing"
        ).filter { (vKey, _) -> vKey in approvedVerticals }
        if (verticalsSpec.isEmpty()) {
            shiftRow.addView(TextView(this).apply {
                text = "No approved service verticals yet. Contact Admin to activate your vertical."
                textSize = 12f
                setTextColor(Color.parseColor("#FCA5A5"))
            })
        } else {
            verticalsSpec.forEachIndexed { idx, (vKey, label) ->
                shiftRow.addView(
                    AlistoUiKit.chipButton(this, label, vKey in currentShifts, AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP) {
                        val updated = currentShifts.toMutableList()
                        if (vKey in updated) updated.remove(vKey) else updated.add(vKey)
                        if (updated.isEmpty()) updated.add(vKey)
                        val allowAuto = autoAcceptEnabled && updated.any { it != "skills_marketplace" }
                        uiScope.launch {
                            AlistoKernelClient.toggleContractorDispatchModeRpc(
                                isOnline = isOnline, autoAccept = allowAuto, activeShiftVerticals = updated
                            )
                            Toast.makeText(this@ContractorMainActivity, "Shift updated: ${updated.joinToString(", ")}", Toast.LENGTH_SHORT).show()
                            routeContractorByAccountState()
                        }
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (idx < verticalsSpec.lastIndex) rightMargin = dp(8)
                        }
                    }
                )
            }
        }
        sheetBody.addView(shiftRow)

        sheetBody.addView(TextView(this).apply {
            text = "Auto-Accept Rings: 1km → 1.5km → 2km → 2.5km  •  Vehicle: ${prof?.vehicleType ?: "motorcycle"} (${prof?.authorizedPlateNo ?: "Unbound"})"
            textSize = 11.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_DIM))
        })

        sheetScroll.addView(sheetBody)
        sheetWrap.addView(sheetScroll)
        root.addView(sheetWrap)
        return root
    }

    private fun buildContractorJobsTab(user: AuthenticatedUser): View {
        val prof = AlistoKernelClient.currentContractorProfile
        val currentShifts = getEffectiveShiftVerticals(prof)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(TextView(this@ContractorMainActivity).apply {
                text = "Job Board"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(AlistoUiKit.chipButton(this@ContractorMainActivity, "Refresh", true, AlistoUiKit.BG_CARD, AlistoUiKit.AMBER) {
                uiScope.launch {
                    AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "contractor")
                    routeContractorByAccountState()
                }
            })
        })

        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(AlistoUiKit.chipButton(this@ContractorMainActivity, "Open Requests", jobsFilter == "OPEN", AlistoUiKit.AMBER) {
                jobsFilter = "OPEN"; showActiveContractorPortal(user)
            }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) } })
            addView(AlistoUiKit.chipButton(this@ContractorMainActivity, "My Jobs", jobsFilter == "MINE", AlistoUiKit.AMBER) {
                jobsFilter = "MINE"; showActiveContractorPortal(user)
            })
        })

        val shiftBookings = AlistoKernelClient.bookings.filter { b ->
            if (jobsFilter == "MINE") b.contractorId == user.id
            else b.verticalKey in currentShifts && b.status in listOf("CREATED", "PENDING", "SEARCHING")
        }

        if (shiftBookings.isEmpty()) {
            val emptyCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 22)
            emptyCard.addView(TextView(this).apply {
                text = if (jobsFilter == "MINE") "No assigned jobs yet." else "No open requests in your active verticals."
                textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            })
            emptyCard.addView(TextView(this).apply {
                text = "Keep your shift verticals active on the Drive tab to receive live bids and auto-accept matches."
                textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(6), 0, 0)
            })
            container.addView(emptyCard)
        } else {
            shiftBookings.forEach { b -> container.addView(buildContractorJobCard(b, user)) }
        }

        scroll.addView(container)
        return scroll
    }

    private fun buildContractorWalletTab(user: AuthenticatedUser): View {
        val activePass = AlistoKernelClient.currentSubscriptionPass
        val payInfo = AlistoKernelClient.currentPaymentModeInfo
        val commPct = "%.1f".format(AlistoKernelClient.effectiveCommissionRate * 100.0)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(TextView(this).apply {
            text = "Wallet & Passes"; textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(14))
        })

        val heroCard = AlistoUiKit.surfaceCard(this, "#0E1D34", AlistoUiKit.CYAN, padDp = 20)
        heroCard.addView(TextView(this).apply {
            text = "AVAILABLE PREPAID CREDITS"; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.CYAN))
        })
        heroCard.addView(TextView(this).apply {
            text = "PHP ${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)}"
            textSize = 30f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            setPadding(0, dp(4), 0, dp(10))
        })

        heroCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, "Held: PHP ${"%.2f".format(AlistoKernelClient.contractorHeldCredits)}", AlistoUiKit.BG_DEEP, AlistoUiKit.AMBER).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, "Comm: $commPct%", AlistoUiKit.BG_DEEP, AlistoUiKit.EMERALD).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, payInfo.paymentMode, AlistoUiKit.BG_DEEP, AlistoUiKit.CYAN))
        })
        heroCard.addView(TextView(this).apply {
            text = "Active Plan: ${activePass?.tierCode ?: "Standard Pay-As-You-Go (≤12%)"}"
            textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(10), 0, 0)
        })
        container.addView(heroCard)

        container.addView(AlistoUiKit.pillButton(
            this,
            if (payInfo.paymentMode == "MANUAL") "+PHP 500 Top-Up (Manual Ref)" else "+PHP 500 Top-Up (PayMongo QR Ph)",
            AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, heightDp = 48
        ) { initiateContractorPaymentFlow("WALLET_TOPUP", 500.0, null, "Prepaid Wallet Top-Up (+PHP 25 Bonus)") }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        })

        container.addView(AlistoUiKit.pillButton(
            this,
            if (payInfo.paymentMode == "MANUAL") "Grind Pass • 0% Comm (Manual Ref)" else "Grind Pass • 0% Comm (PayMongo)",
            AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP, heightDp = 48
        ) { initiateContractorPaymentFlow("SUBSCRIPTION_PASS", 8000.0, "GRIND_PASS", "Hard-Working Grind Pass (0% 24/7)") }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) }
        })

        container.addView(TextView(this).apply {
            text = "Recent Payment Activity"; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(8))
        })

        if (AlistoKernelClient.paymentTransactions.isEmpty()) {
            val c = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
            c.addView(TextView(this).apply { text = "No payment transactions recorded yet."; textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)) })
            container.addView(c)
        } else {
            AlistoKernelClient.paymentTransactions.take(10).forEach { tx ->
                val c = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
                val (sBg, sFg) = AlistoUiKit.statusColors(tx.status)
                c.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@ContractorMainActivity).apply {
                        text = "${tx.txRef}\n${tx.purpose} • PHP ${"%.2f".format(tx.amountPhp)}"
                        textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(AlistoUiKit.badgePill(this@ContractorMainActivity, tx.status, sBg, sFg))
                })
                container.addView(c)
            }
        }

        scroll.addView(container)
        return scroll
    }

    private fun buildContractorProfileTab(user: AuthenticatedUser): View {
        val prof = AlistoKernelClient.currentContractorProfile
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(TextView(this).apply {
            text = "Contractor Profile"; textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(14))
        })

        val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 18)
        card.addView(TextView(this).apply { text = user.fullName; textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)) })
        card.addView(TextView(this).apply {
            text = "@${user.username} • ${user.phone}\n${user.barangayZone}, ${user.municipality}"
            textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(4), 0, dp(12))
        })

        val plateText = if (prof?.ltfrbCompliant == true && !prof.authorizedPlateNo.isNullOrBlank()) {
            "LTFRB Plate: ${prof.authorizedPlateNo}"
        } else {
            "Vehicle: ${prof?.vehicleType ?: "Skills Only"}"
        }
        card.addView(AlistoUiKit.badgePill(this, "$plateText • ${prof?.dispatchRadiusM ?: 1000}m start", "#0C4A6E", AlistoUiKit.CYAN))
        container.addView(card)

        container.addView(AlistoUiKit.pillButton(this, "Sync Profile & Jobs", AlistoUiKit.BG_CARD, AlistoUiKit.AMBER, AlistoUiKit.BORDER_LIGHT, heightDp = 46) {
            uiScope.launch {
                AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "contractor")
                Toast.makeText(this@ContractorMainActivity, "Synced", Toast.LENGTH_SHORT).show()
                routeContractorByAccountState()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })

        container.addView(AlistoUiKit.pillButton(this, "Sign Out & Lock", "#450A0A", "#FCA5A5", "#7F1D1D", heightDp = 46) {
            realtimePollJob?.cancel(); AlistoLocationTracker.stopLiveUpdates(); AlistoSpatialEngine.stopHeartbeat(); promptedEarnModeThisSession = false; AlistoKernelClient.signOut(); showContractorEntryGate()
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) })

        scroll.addView(container)
        return scroll
    }

    private fun buildContractorJobCard(b: BookingRecord, user: AuthenticatedUser): View {
        val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 16)
        val reqComm = b.quotedFee * AlistoKernelClient.effectiveCommissionRate
        val commPct = "%.1f".format(AlistoKernelClient.effectiveCommissionRate * 100.0)
        val (sBg, sFg) = AlistoUiKit.statusColors(b.status)

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addView(TextView(this@ContractorMainActivity).apply {
                text = "${b.bookingRef} • ${b.verticalKey.replace("_", " ")}"
                textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, b.status, sBg, sFg))
        })

        card.addView(TextView(this).apply { text = b.serviceTitle; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)) })
        val routeText = buildString {
            append("Pickup: ${b.pickupAddress} (${b.barangayZone}, ${b.municipality})")
            if (b.pickupNote.isNotBlank()) append("\n  Note: ${b.pickupNote}")
            if (b.dropoffAddress.isNotBlank() && b.dropoffAddress != b.pickupAddress) {
                append("\nDrop-Off: ${b.dropoffAddress}")
                if (b.dropoffNote.isNotBlank()) append("\n  Note: ${b.dropoffNote}")
            }
        }
        card.addView(TextView(this).apply {
            text = routeText
            textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(4), 0, dp(8))
        })
        if (b.bookingNotes.isNotBlank() || !b.allowAutoAssign) {
            card.addView(TextView(this).apply {
                text = "OPEN BOARD • SPECIAL REQUEST: ${b.bookingNotes.ifBlank { "Manual contractor review required" }}"
                textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.AMBER))
                background = AlistoUiKit.roundedBg(this@ContractorMainActivity, "#2A1B08", 10, "#78350F")
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            })
        }
        card.addView(TextView(this).apply {
            text = "Customer: ${b.clientNickname} • Trust ${"%.0f".format(b.clientTrustScore)}% (UP ${b.clientThumbsUp} / DOWN ${b.clientThumbsDown})"
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.AMBER)); setPadding(0, 0, 0, dp(8))
        })

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, "Fee: PHP ${"%.2f".format(b.quotedFee)}", AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, "Tip: PHP ${"%.0f".format(b.tipAmount)}", AlistoUiKit.BG_INPUT, AlistoUiKit.AMBER).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, "Hold $commPct%: PHP ${"%.2f".format(reqComm)}", AlistoUiKit.BG_INPUT, AlistoUiKit.TEXT_MUTED))
        })

        when (b.status) {
            "CREATED", "PENDING", "SEARCHING" -> {
                val myActiveBid = AlistoKernelClient.bookingBids.find {
                    it.bookingId == b.id && it.contractorId == user.id && it.status == "ACTIVE"
                }
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                    addView(AlistoUiKit.pillButton(
                        this@ContractorMainActivity,
                        if (myActiveBid != null) "Update Bid (PHP ${"%.0f".format(myActiveBid.bidAmountPhp)})" else "Submit Bid",
                        AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP, heightDp = 42, textSizeSp = 13f
                    ) { promptSubmitOpenBid(b) }.apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
                    })
                    addView(AlistoUiKit.pillButton(this@ContractorMainActivity, "Claim Now", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP, heightDp = 42, textSizeSp = 13f) {
                        uiScope.launch {
                            val res = AlistoKernelClient.claimBookingRpc(b.id)
                            res.onSuccess { jobsFilter = "MINE"; routeContractorByAccountState() }
                                .onFailure { err ->
                                    AlertDialog.Builder(this@ContractorMainActivity)
                                        .setTitle("Wallet Check").setMessage(err.message).setPositiveButton("OK", null).show()
                                }
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                })

                if (myActiveBid != null) {
                    card.addView(AlistoUiKit.pillButton(this, "Withdraw Bid", "#450A0A", "#FCA5A5", "#7F1D1D", heightDp = 38, textSizeSp = 12f) {
                        uiScope.launch {
                            val res = AlistoKernelClient.withdrawContractorBidRpc(b.id)
                            res.onSuccess {
                                Toast.makeText(this@ContractorMainActivity, "Bid withdrawn", Toast.LENGTH_SHORT).show()
                                routeContractorByAccountState()
                            }
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
                }
            }
            "ACCEPTED" -> {
                card.addView(AlistoUiKit.pillButton(this, "Start En Route (Begin Live GPS)", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP, heightDp = 44) {
                    AlistoSpatialEngine.startTenSecondHeartbeat(this@ContractorMainActivity, uiScope, b.id, user.id)
                    transitionJob(b.id, "ON_THE_WAY")
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
            }
            "ON_THE_WAY" -> {
                card.addView(AlistoUiKit.pillButton(this, "Mark Arrived at Pickup / Site", AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP, heightDp = 44) {
                    transitionJob(b.id, "ARRIVED")
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
            }
            "ARRIVED" -> {
                card.addView(AlistoUiKit.pillButton(this, "Start Job Execution", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP, heightDp = 44) {
                    transitionJob(b.id, "ONGOING")
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
            }
            "ONGOING", "IN_PROGRESS" -> {
                card.addView(AlistoUiKit.pillButton(this, "Complete & Settle Job", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, heightDp = 44) {
                    AlistoSpatialEngine.stopHeartbeat()
                    transitionJob(b.id, "COMPLETED")
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
            }
            "COMPLETED" -> {
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                    addView(AlistoUiKit.pillButton(this@ContractorMainActivity, "Rate Up (Good Client)", "#064E3B", "#34D399", "#10B981", heightDp = 40, textSizeSp = 12.5f) {
                        uiScope.launch {
                            AlistoKernelClient.submitContractorThumbReviewRpc(b.id, "UP", "Smooth payment & clear instructions")
                            Toast.makeText(this@ContractorMainActivity, "Thumbs-Up recorded!", Toast.LENGTH_SHORT).show()
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
                    addView(AlistoUiKit.pillButton(this@ContractorMainActivity, "Rate Down (Issue)", "#450A0A", "#FCA5A5", "#7F1D1D", heightDp = 40, textSizeSp = 12.5f) {
                        uiScope.launch {
                            val res = AlistoKernelClient.submitContractorThumbReviewRpc(b.id, "DOWN", "Delayed coordination / difficult pickup")
                            res.onSuccess { json ->
                                val gate = json.optBoolean("trust_deposit_required", false)
                                Toast.makeText(this@ContractorMainActivity, "Logged (Deposit Gate: $gate)", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                })
            }
        }

        // Secondary utility row: turn-by-turn local-road nav + LTFRB safety refusal
        val utilRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val hasDistinctDropoff = b.dropoffAddress.isNotBlank() && b.dropoffAddress != b.pickupAddress &&
            (b.dropoffLat != b.pickupLat || b.dropoffLng != b.pickupLng)
        utilRow.addView(AlistoUiKit.pillButton(
            this, if (hasDistinctDropoff) "Nav Pickup" else "Navigate (avoid=ht)",
            AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD, AlistoUiKit.BORDER_LIGHT, heightDp = 38, textSizeSp = 12f
        ) {
            AlistoSpatialEngine.launchLocalRoadNavigation(this@ContractorMainActivity, b.pickupLat, b.pickupLng)
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })

        if (hasDistinctDropoff) {
            utilRow.addView(AlistoUiKit.pillButton(
                this, "Nav Drop-Off", AlistoUiKit.BG_INPUT, AlistoUiKit.CYAN, AlistoUiKit.BORDER_LIGHT, heightDp = 38, textSizeSp = 12f
            ) {
                AlistoSpatialEngine.launchLocalRoadNavigation(this@ContractorMainActivity, b.dropoffLat, b.dropoffLng)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) } })
        }

        if (b.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED") && (b.contractorId == user.id || user.role == "super_admin")) {
            utilRow.addView(AlistoUiKit.pillButton(this, "Safety Refusal", "#451A03", AlistoUiKit.AMBER, "#78350F", heightDp = 38, textSizeSp = 12f) {
                promptLtfrbSafetyRefusal(b, user)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) } })
        }
        card.addView(utilRow)
        return card
    }

    private fun promptLtfrbSafetyRefusal(b: BookingRecord, user: AuthenticatedUser) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(10))
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_SURFACE))
        }
        val categories = listOf(
            "INTOXICATED_PASSENGER", "NO_HELMET_REFUSAL", "UNSAFE_BEHAVIOR_THREAT",
            "OVERWEIGHT_OR_PROHIBITED_CARGO", "MINOR_UNACCOMPANIED", "OTHER_SAFETY_RISK"
        )
        val catSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ContractorMainActivity, android.R.layout.simple_spinner_dropdown_item, categories)
            background = AlistoUiKit.roundedBg(this@ContractorMainActivity, AlistoUiKit.BG_INPUT, 10, AlistoUiKit.BORDER_LIGHT)
        }
        layout.addView(AlistoUiKit.labeledField(this, "LTFRB Safety Refusal Category", catSpinner))
        val notesInput = AlistoUiKit.inputField(this, "Document safety reason (releases hold penalty-free)...")
        layout.addView(AlistoUiKit.labeledField(this, "Incident Details", notesInput))

        AlertDialog.Builder(this)
            .setTitle("Safety Refusal • ${b.bookingRef}").setView(layout)
            .setPositiveButton("Log & Release Hold") { _, _ ->
                val chosenCat = catSpinner.selectedItem.toString()
                val noteText = notesInput.text.toString().ifBlank { "Safety refusal logged by contractor" }
                AlistoSpatialEngine.stopHeartbeat()
                uiScope.launch {
                    val res = AlistoKernelClient.logSafetyRefusalRpc(b.id, chosenCat, noteText, user.homeLat, user.homeLng)
                    res.onSuccess {
                        Toast.makeText(this@ContractorMainActivity, "Safety Refusal logged. Hold released.", Toast.LENGTH_LONG).show()
                        routeContractorByAccountState()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@ContractorMainActivity)
                            .setTitle("Safety Refusal Error").setMessage(err.message).setPositiveButton("OK", null).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun promptSubmitOpenBid(b: BookingRecord) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(8))
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_SURFACE))
        }
        val bidInput = AlistoUiKit.inputField(
            this, b.quotedFee.toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            b.quotedFee.toString()
        )
        layout.addView(AlistoUiKit.labeledField(this, "Your Offer (PHP)", bidInput))

        AlertDialog.Builder(this)
            .setTitle("Submit Offer • ${b.bookingRef}")
            .setMessage("Validates commission coverage (0% for Active Pass holders).")
            .setView(layout)
            .setPositiveButton("Send Offer") { _, _ ->
                val amt = bidInput.text.toString().toDoubleOrNull() ?: b.quotedFee
                uiScope.launch {
                    val res = AlistoKernelClient.submitContractorBidRpc(b.id, amt, 20, "Open bid by verified contractor")
                    res.onSuccess {
                        Toast.makeText(this@ContractorMainActivity, "Offer of PHP $amt sent!", Toast.LENGTH_LONG).show()
                        routeContractorByAccountState()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@ContractorMainActivity)
                            .setTitle("Wallet Coverage Check").setMessage(err.message).setPositiveButton("OK", null).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun transitionJob(bookingId: String, nextStatus: String) {
        uiScope.launch {
            AlistoKernelClient.advanceBookingStateRpc(bookingId, nextStatus)
            routeContractorByAccountState()
        }
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
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(10))
                setBackgroundColor(Color.parseColor(AlistoUiKit.BG_SURFACE))
            }
            layout.addView(TextView(this).apply {
                text = "Amount Due: PHP ${"%.2f".format(amountPhp)}\n\n${payInfo.manualInstructions}"
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); textSize = 13f; setPadding(0, 0, 0, dp(12))
            })
            val refInput = AlistoUiKit.inputField(this, "GCash / QR Ph / Clinic Reference No.")
            layout.addView(AlistoUiKit.labeledField(this, "Payment Reference Number", refInput))

            AlertDialog.Builder(this)
                .setTitle("Manual Top-Up • $titleLabel").setView(layout)
                .setPositiveButton("Submit for Approval") { _, _ ->
                    val ref = refInput.text.toString().trim()
                    uiScope.launch {
                        val res = AlistoKernelClient.submitManualPaymentRequestRpc(purpose, amountPhp, tierCode, ref)
                        res.onSuccess {
                            Toast.makeText(this@ContractorMainActivity, "Reference submitted for Admin approval!", Toast.LENGTH_LONG).show()
                            routeContractorByAccountState()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Submission Error").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }.setNegativeButton("Cancel", null).show()
            return
        }

        Toast.makeText(this, "Opening PayMongo (${payInfo.paymentMode})...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            val res = AlistoKernelClient.initiatePayMongoCheckout(purpose, amountPhp, tierCode)
            res.onSuccess { json ->
                val url = json.optString("checkout_url", "")
                val txRef = json.optString("tx_ref", "")
                Toast.makeText(this@ContractorMainActivity, "Checkout [$txRef]", Toast.LENGTH_LONG).show()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                routeContractorByAccountState()
            }.onFailure { err ->
                AlertDialog.Builder(this@ContractorMainActivity)
                    .setTitle("PayMongo Checkout").setMessage(err.message).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun startContractorRealtimeLoop() {
        realtimePollJob?.cancel()
        realtimePollJob = uiScope.launch {
            while (isActive) {
                delay(8_000L)
                if (AlistoKernelClient.currentSessionToken.isNullOrBlank()) break
                AlistoKernelClient.syncBookingsAndWallet()
                val fired = AlistoPushCenter.dispatchUnreadNotifications(this@ContractorMainActivity, uiScope)
                contractorMapView?.updateMapLayers(
                    bookings = AlistoKernelClient.bookings,
                    telemetryPins = AlistoKernelClient.liveTelemetryPins,
                    heatClusters = AlistoKernelClient.heatmapClusters
                )
                val user = AlistoKernelClient.currentSessionUser
                val digest = AlistoKernelClient.bookings.joinToString("|") { "${it.id}:${it.status}:${it.contractorId}" }
                if (user != null && digest != lastBookingsDigest) {
                    lastBookingsDigest = digest
                    if (activeTab == "JOBS" || activeTab == "DRIVE") {
                        showActiveContractorPortal(user)
                        break
                    }
                }
                if (fired > 0) Toast.makeText(this@ContractorMainActivity, "$fired new dispatch alert(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        realtimePollJob?.cancel()
        AlistoLocationTracker.stopLiveUpdates()
        AlistoSpatialEngine.stopHeartbeat()
        uiScope.cancel()
    }
}
