package ph.alisto.contractor

import android.os.Build
import android.provider.Settings
import org.json.JSONObject

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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import ph.alisto.kernel.*
import ph.alisto.kernel.AlistoUiKit.dp

class ContractorMainActivity : AppCompatActivity() {

    internal val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var entryMode = "LOGIN"
    internal var activeTab = "DRIVE"
    internal var jobsFilter = "OPEN"
    internal var boardCategory = "OPEN"
    internal var pendingSubCategory = "ALL"
    internal var lastCompletedAtMs: Long = 0L
    internal var cachedWalletEntries: List<WalletLedgerEntry> = emptyList()
    internal val lastSeenChatMsgIds = mutableMapOf<String, String>()
    internal val paidWaitAlertedIds = mutableSetOf<String>()
    private var autoOnlineSyncedThisSession = false
    private var autoAcceptEnabled = false
    private var promptedEarnModeThisSession = false
    private var lastBookingsDigest: String = ""
    private var lastContractorLocSyncMs: Long = 0L
    private var realtimePollJob: Job? = null
    private var contractorMapView: AlistoInteractiveMapView? = null
    private var promptedLocationPerm = false
    private var hasAutoCenteredGps = false
    private var compressedProfileUrl: String? = null
    private var compressedLicenseUrl: String? = null
    private var compressedOrcrUrl: String? = null
    private var compressedVehicleUrl: String? = null

    override fun onPause() {
        super.onPause()
        ContractorOfferBridge.isMainActivityForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("open_tab")?.let { activeTab = it }
        intent.getStringExtra("open_jobs_filter")?.let { jobsFilter = it }
        AlistoKernelClient.currentSessionUser?.let { showActiveContractorPortal(it) }
    }

    override fun onResume() {
        super.onResume()
        ContractorOfferBridge.isMainActivityForeground = true
        val dec = ContractorOfferBridge.lastResultDecision
        if (dec != null && System.currentTimeMillis() - ContractorOfferBridge.lastHandledAtMs < 6000L) {
            ContractorOfferBridge.lastResultDecision = null
            if (dec == "ACCEPT") {
                activeTab = "ACTIVE"
            }
            uiScope.launch {
                AlistoKernelClient.syncBookingsAndWallet()
                AlistoKernelClient.currentSessionUser?.let { showActiveContractorPortal(it) }
            }
        }
    }

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
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastContractorLocSyncMs >= 10_000L) {
                lastContractorLocSyncMs = nowMs
                val uid = AlistoKernelClient.currentSessionUser?.id
                val activeJobId = AlistoKernelClient.bookings.firstOrNull {
                    it.contractorId == uid && it.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")
                }?.id
                uiScope.launch { AlistoKernelClient.updateLiveLocationRpc(lat, lng, activeJobId) }
            }
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

    internal fun routeContractorByAccountState() {
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

    internal fun getEffectiveShiftVerticals(prof: ContractorProfileState?): List<String> {
        val approved = getApprovedVerticals(prof)
        if (approved.isEmpty()) return emptyList()
        val active = prof?.activeShiftVerticals.orEmpty().filter { it in approved }
        return if (active.isNotEmpty()) active else approved
    }

    private fun supportsAutoAssign(shifts: List<String>): Boolean {
        return shifts.any { it == "delivery_logistics" || it == "ride_hailing" }
    }

    internal fun shiftModeLabel(shifts: List<String>): String {
        val hasDel = "delivery_logistics" in shifts
        val hasRide = "ride_hailing" in shifts
        val hasSkills = "skills_marketplace" in shifts
        return when {
            hasDel && hasRide && hasSkills -> "All Services"
            hasDel && hasRide -> "Delivery & Local Ride"
            hasDel -> "Delivery Logistics"
            hasRide -> "Local Ride-Hailing"
            hasSkills -> "Skills & Errands"
            else -> "Select Shift Mode"
        }
    }

    private fun openSwitchShiftDialog(user: AuthenticatedUser) {
        val prof = AlistoKernelClient.currentContractorProfile ?: return
        val approved = getApprovedVerticals(prof)
        if (approved.size <= 1) {
            Toast.makeText(this, "Only one approved vertical is active on your account.", Toast.LENGTH_SHORT).show()
            return
        }
        val rideVerticals = buildList {
            if (prof.offersDelivery) add("delivery_logistics")
            if (prof.offersRideHailing) add("ride_hailing")
        }
        val options = mutableListOf<Pair<String, List<String>>>()
        if (rideVerticals.isNotEmpty()) {
            options.add("Delivery & Local Ride" to rideVerticals)
        }
        if (prof.offersDelivery && prof.offersRideHailing) {
            options.add("Delivery Only" to listOf("delivery_logistics"))
            options.add("Local Ride Only" to listOf("ride_hailing"))
        }
        if (prof.offersSkills) {
            options.add("Skills Marketplace & Errands" to listOf("skills_marketplace"))
        }
        val labels = options.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Switch Active Shift Mode")
            .setItems(labels) { _, which ->
                val (label, chosen) = options[which]
                val canAuto = supportsAutoAssign(chosen)
                val nextAuto = if (canAuto) autoAcceptEnabled else false
                autoAcceptEnabled = nextAuto
                uiScope.launch {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(
                        isOnline = prof.isOnline,
                        autoAccept = nextAuto,
                        activeShiftVerticals = chosen
                    )
                    Toast.makeText(this@ContractorMainActivity, "Shift switched to $label", Toast.LENGTH_SHORT).show()
                    routeContractorByAccountState()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            .setMessage("Choose your active shift mode. You can switch anytime inside your Profile tab.")
            .setCancelable(false)
            .setPositiveButton("Ride (Delivery & Local Ride)") { _, _ ->
                uiScope.launch {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(
                        isOnline = true,
                        autoAccept = autoAcceptEnabled,
                        activeShiftVerticals = rideVerticals
                    )
                    Toast.makeText(this@ContractorMainActivity, "Shift set: Delivery & Local Ride", Toast.LENGTH_SHORT).show()
                    routeContractorByAccountState()
                }
            }
            .setNegativeButton("Skills Marketplace & Errands") { _, _ ->
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

    private fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                Toast.makeText(this, "Open Android Settings > Apps > Alisto Contractor > Display over other apps.", Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun isBookingOpenForBoard(b: BookingRecord, currentShifts: List<String>, myUserId: String): Boolean {
        if (b.verticalKey !in currentShifts) return false
        if (b.contractorId != null) return false
        if (b.status !in listOf("CREATED", "PENDING", "SEARCHING")) return false
        val offeredTo = b.offerContractorId
        if (offeredTo != null && offeredTo != myUserId) return false
        return true
    }

    internal fun showActiveContractorPortal(user: AuthenticatedUser) {
        AlistoPushCenter.ensureNotificationPermission(this)
        AlistoPushCenter.dispatchUnreadNotifications(this, uiScope)
        startContractorRealtimeLoop()
        AlistoComplianceGate.checkOtaUpdate(this, "contractor", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        AlistoComplianceGate.enforceRa10173ConsentIfRequired(this, uiScope, "contractor")
        maybePromptEarnModeToday(user)

        val prof = AlistoKernelClient.currentContractorProfile
        val token = AlistoKernelClient.currentSessionToken
        val effShifts = getEffectiveShiftVerticals(prof)
        if (prof != null && !prof.isOnline && effShifts.isNotEmpty() && !autoOnlineSyncedThisSession) {
            autoOnlineSyncedThisSession = true
            uiScope.launch {
                AlistoKernelClient.toggleContractorDispatchModeRpc(
                    isOnline = true,
                    autoAccept = autoAcceptEnabled && supportsAutoAssign(effShifts),
                    activeShiftVerticals = effShifts
                )
            }
        }
        if (!token.isNullOrBlank()) {
            ContractorDispatchMonitorService.startMonitoring(this, token)
        }

        if (!AlistoLocationTracker.hasPermission(this) && !promptedLocationPerm) {
            promptedLocationPerm = true
            AlistoLocationTracker.requestPermission(this)
        } else if (AlistoLocationTracker.hasPermission(this)) {
            startContractorLiveGps(forceCenter = false)
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        when (activeTab) {
            "ACTIVE" -> contentHost.addView(buildContractorActiveBookingTab(user))
            "JOBS" -> contentHost.addView(buildVerticalDispatchBoardTab(user))
            "WALLET" -> contentHost.addView(buildContractorWalletTab(user))
            "PROFILE" -> contentHost.addView(buildContractorProfileTab(user))
            else -> contentHost.addView(buildContractorDriveMapTab(user))
        }

        val currentShifts = getEffectiveShiftVerticals(prof)
        val myActiveCount = AlistoKernelClient.bookings.count {
            it.contractorId == user.id && it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")
        }
        val openBoardCount = AlistoKernelClient.bookings.count { isBookingOpenForBoard(it, currentShifts, user.id) }
        val badgeTotal = myActiveCount + openBoardCount

        val bottomNav = AlistoUiKit.bottomNavBar(
            this,
            tabs = listOf(
                Triple("DRIVE", "◎", "Drive"),
                Triple("ACTIVE", "★", if (myActiveCount > 0) "Active ●" else "Active"),
                Triple("JOBS", "☰", if (openBoardCount > 0) "Board ($openBoardCount)" else "Board"),
                Triple("WALLET", "₱", "Wallet"),
                Triple("PROFILE", "●", "Profile")
            ),
            activeKey = activeTab,
            accentHex = AlistoUiKit.GOLD_ACCENT
        ) { nextTab ->
            activeTab = nextTab
            showActiveContractorPortal(user)
        }

        shell.addView(contentHost)
        shell.addView(bottomNav)
        setContentView(shell)
        ViewCompat.requestApplyInsets(shell)
    }

    private fun buildContractorDriveMapTab(user: AuthenticatedUser): View {
        val prof = AlistoKernelClient.currentContractorProfile
        val approvedVerticals = getApprovedVerticals(prof)
        val currentShifts = getEffectiveShiftVerticals(prof)
        val isOnline = prof?.isOnline == true
        val canAutoAssign = supportsAutoAssign(currentShifts)
        if (!canAutoAssign && autoAcceptEnabled) {
            autoAcceptEnabled = false
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }

        // Full-bleed interactive map stage
        val mapStage = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
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
        val visibleMapBookings = AlistoKernelClient.bookings.filter {
            it.contractorId == user.id || isBookingOpenForBoard(it, currentShifts, user.id)
        }
        cMap.updateMapLayers(
            bookings = visibleMapBookings,
            telemetryPins = AlistoKernelClient.liveTelemetryPins,
            heatClusters = AlistoKernelClient.heatmapClusters
        )

        // Clean Floating Top HUD: Wallet Pill (left) + Auto-ON/OFF Toggle (upper-right, Delivery & Ride ONLY)
        val topHud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        }

        val walletPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AlistoUiKit.frostedGlassBg(this@ContractorMainActivity, "#D90A1931", 24, "#6638BDF8", alphaFraction = 0.88f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            elevation = dp(6).toFloat()
            setOnClickListener { activeTab = "WALLET"; showActiveContractorPortal(user) }
            addView(View(this@ContractorMainActivity).apply {
                background = AlistoUiKit.roundedBg(
                    this@ContractorMainActivity,
                    if (isOnline) AlistoUiKit.EMERALD else "#64748B",
                    5
                )
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
            })
            addView(TextView(this@ContractorMainActivity).apply {
                text = "₱${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)}"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            })
        }
        topHud.addView(walletPill)
        topHud.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        // Upper-right slot: Auto-ON / Auto-OFF strictly for Delivery & Ride-Hailing (hidden for Skills & Errands)
        if (canAutoAssign) {
            val autoOn = autoAcceptEnabled
            val autoTogglePill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AlistoUiKit.frostedGlassBg(
                    this@ContractorMainActivity,
                    if (autoOn) "#E6064E3B" else "#D90F172A",
                    24,
                    if (autoOn) "#34D399" else "#475569",
                    alphaFraction = 0.92f
                )
                setPadding(dp(14), dp(9), dp(14), dp(9))
                elevation = dp(6).toFloat()
                setOnClickListener {
                    val nextAuto = !autoAcceptEnabled
                    autoAcceptEnabled = nextAuto
                    uiScope.launch {
                        AlistoKernelClient.toggleContractorDispatchModeRpc(
                            isOnline = true,
                            autoAccept = nextAuto,
                            activeShiftVerticals = currentShifts
                        )
                        Toast.makeText(
                            this@ContractorMainActivity,
                            if (nextAuto) "Auto-Assignment ON (Delivery & Ride)" else "Auto-Assignment OFF",
                            Toast.LENGTH_SHORT
                        ).show()
                        routeContractorByAccountState()
                    }
                }
                addView(View(this@ContractorMainActivity).apply {
                    background = AlistoUiKit.roundedBg(
                        this@ContractorMainActivity,
                        if (autoOn) AlistoUiKit.EMERALD else "#94A3B8",
                        5
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(7) }
                })
                addView(TextView(this@ContractorMainActivity).apply {
                    text = if (autoOn) "AUTO: ON" else "AUTO: OFF"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(if (autoOn) "#A7F3D0" else "#CBD5E1"))
                })
            }
            topHud.addView(autoTogglePill)
        }
        mapStage.addView(topHud)

        // Floating My-GPS recenter pill above bottom sheet
        val gpsFab = TextView(this).apply {
            text = "◎ My GPS"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E0F2FE"))
            background = AlistoUiKit.frostedGlassBg(this@ContractorMainActivity, "#D90A1931", 20, "#6638BDF8", alphaFraction = 0.88f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            elevation = dp(6).toFloat()
            setOnClickListener {
                if (!AlistoLocationTracker.hasPermission(this@ContractorMainActivity)) {
                    AlistoLocationTracker.requestPermission(this@ContractorMainActivity)
                } else {
                    val lat = AlistoLocationTracker.lastLat ?: user.homeLat
                    val lng = AlistoLocationTracker.lastLng ?: user.homeLng
                    contractorMapView?.recenter(lat, lng, 16)
                    startContractorLiveGps(forceCenter = true)
                }
            }
        }
        mapStage.addView(
            gpsFab,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(14)
                topMargin = dp(68)
            }
        )
        root.addView(mapStage)

        // Sleek uncluttered bottom driver console sheet
        val sheetWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = AlistoUiKit.topSheetBg(this@ContractorMainActivity, "#F5091224", 26, "#334155")
            elevation = dp(14).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        sheetWrap.addView(AlistoUiKit.dragHandle(this))

        val sheetBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(16))
        }

        // Compact Shift Identity Row (no shift-toggle button clutter!)
        val statusHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val shiftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cdLeft = ((5_000L - (System.currentTimeMillis() - lastCompletedAtMs)) / 1000L).toInt()
        val hasAct = findMyActiveBooking(user.id) != null
        shiftCol.addView(TextView(this).apply {
            text = when {
                hasAct -> "On Active Booking"
                cdLeft > 0 -> "Online • Cooldown (${cdLeft}s)"
                else -> "● Online & Ready"
            }
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (hasAct) "#38BDF8" else AlistoUiKit.EMERALD))
        })
        shiftCol.addView(TextView(this).apply {
            text = "Active Shift: ${shiftModeLabel(currentShifts)}"
            textSize = 12.5f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
        })
        statusHeader.addView(shiftCol)

        val openBoardCount = AlistoKernelClient.bookings.count { isBookingOpenForBoard(it, currentShifts, user.id) }
        statusHeader.addView(AlistoUiKit.badgePill(
            this,
            if (openBoardCount > 0) "$openBoardCount Open" else "Quiet",
            if (openBoardCount > 0) "#1E3A5F" else "#111827",
            if (openBoardCount > 0) AlistoUiKit.GOLD_ACCENT else AlistoUiKit.TEXT_MUTED
        ).apply {
            setOnClickListener { jobsFilter = "OPEN"; activeTab = "JOBS"; showActiveContractorPortal(user) }
        })
        sheetBody.addView(statusHeader)

        // Auto-online active once logged in with shift vertical; Auto-Assign controlled in top-right HUD

        // Only show one-time shift selector if contractor somehow has zero selected shifts
        if (currentShifts.isEmpty()) {
            if (approvedVerticals.isEmpty()) {
                sheetBody.addView(TextView(this).apply {
                    text = "No approved service verticals yet. Contact Admin to activate your vertical."
                    textSize = 12f
                    setTextColor(Color.parseColor("#FCA5A5"))
                    setPadding(0, dp(4), 0, dp(8))
                })
            } else {
                sheetBody.addView(
                    AlistoUiKit.pillButton(
                        this, "Select Active Shift Mode", "#1E293B", AlistoUiKit.GOLD_ACCENT, "#334155", heightDp = 42, textSizeSp = 13f
                    ) { openSwitchShiftDialog(user) }.apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                    }
                )
            }
        }

        // One-tap overlay permission helper (only shown until enabled so pop-ups appear over other apps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            sheetBody.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AlistoUiKit.roundedBg(this@ContractorMainActivity, "#1E1B0C", 12, "#854D0E")
                setPadding(dp(12), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                addView(TextView(this@ContractorMainActivity).apply {
                    text = "Enable pop-ups over other apps so booking offers show immediately."
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#FDE68A"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
                })
                addView(AlistoUiKit.chipButton(this@ContractorMainActivity, "Enable", true, AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP) {
                    openOverlayPermissionSettings()
                })
            })
        }

        // Active assigned job spotlight card
        val myActiveJob = AlistoKernelClient.bookings.firstOrNull {
            it.contractorId == user.id && it.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")
        }
        if (myActiveJob != null) {
            val spotCard = AlistoUiKit.surfaceCard(this, "#0C2542", "#38BDF8", padDp = 14)
            spotCard.addView(TextView(this).apply {
                text = "ACTIVE JOB • ${myActiveJob.bookingRef} (${myActiveJob.status})"
                textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#38BDF8"))
            })
            spotCard.addView(TextView(this).apply {
                text = "${myActiveJob.serviceTitle} • ${myActiveJob.pickupAddress}"
                textSize = 13.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                setPadding(0, dp(4), 0, dp(10))
            })
            spotCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(AlistoUiKit.pillButton(this@ContractorMainActivity, "Navigate", "#064E3B", "#34D399", "#10B981", heightDp = 40, textSizeSp = 12.5f) {
                    AlistoSpatialEngine.launchLocalRoadNavigation(this@ContractorMainActivity, myActiveJob.pickupLat, myActiveJob.pickupLng)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
                addView(AlistoUiKit.pillButton(this@ContractorMainActivity, "Open Active Tab →", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, heightDp = 40, textSizeSp = 12.5f) {
                    activeTab = "ACTIVE"; showActiveContractorPortal(user)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            })
            sheetBody.addView(spotCard)
        } else {
            // Compact 2-tile driver stats strip
            val autoDecided = (prof?.autoOffersAccepted ?: 0) + (prof?.autoOffersRejected ?: 0) + (prof?.autoOffersTimedOut ?: 0)
            val acceptRateStr = if (autoDecided > 0) "${((prof?.autoOffersAccepted ?: 0) * 100) / autoDecided}%" else "100%"
            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            fun miniTile(label: String, value: String, colorHex: String, onTap: (() -> Unit)? = null): LinearLayout {
                return LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = AlistoUiKit.roundedBg(this@ContractorMainActivity, "#0F172A", 12, "#1E293B")
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    if (onTap != null) setOnClickListener { onTap() }
                    addView(TextView(this@ContractorMainActivity).apply {
                        text = label; textSize = 10.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                    })
                    addView(TextView(this@ContractorMainActivity).apply {
                        text = value; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(colorHex))
                    })
                }
            }
            statsRow.addView(
                miniTile("Open Board", "$openBoardCount Available", AlistoUiKit.GOLD_ACCENT) {
                    jobsFilter = "OPEN"; activeTab = "JOBS"; showActiveContractorPortal(user)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } }
            )
            statsRow.addView(
                miniTile("Auto Accept Rate", acceptRateStr, AlistoUiKit.EMERALD) {
                    activeTab = "PROFILE"; showActiveContractorPortal(user)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            )
            sheetBody.addView(statsRow)
        }

        sheetWrap.addView(sheetBody)
        root.addView(sheetWrap)
        return root
    }

    private fun buildContractorJobsTab(user: AuthenticatedUser): View {
        val prof = AlistoKernelClient.currentContractorProfile
        val currentShifts = getEffectiveShiftVerticals(prof)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        // Clean header with live Auto-Sync indicator (no manual refresh button)
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            addView(TextView(this@ContractorMainActivity).apply {
                text = "Dispatch Board"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(AlistoUiKit.badgePill(this@ContractorMainActivity, "● Live Auto-Sync", "#064E3B", "#6EE7B7"))
        })

        val openList = AlistoKernelClient.bookings.filter { isBookingOpenForBoard(it, currentShifts, user.id) }
        val myList = AlistoKernelClient.bookings.filter { it.contractorId == user.id }

        // Segmented filter bar
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = AlistoUiKit.roundedBg(this@ContractorMainActivity, "#0F172A", 14, "#1E293B")
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(AlistoUiKit.pillButton(
                this@ContractorMainActivity,
                "Open Board (${openList.size})",
                if (jobsFilter == "OPEN") AlistoUiKit.GOLD_ACCENT else "#0F172A",
                if (jobsFilter == "OPEN") AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_MUTED,
                heightDp = 40, textSizeSp = 13f
            ) {
                jobsFilter = "OPEN"; showActiveContractorPortal(user)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) } })
            addView(AlistoUiKit.pillButton(
                this@ContractorMainActivity,
                "My Jobs (${myList.size})",
                if (jobsFilter == "MINE") AlistoUiKit.GOLD_ACCENT else "#0F172A",
                if (jobsFilter == "MINE") AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_MUTED,
                heightDp = 40, textSizeSp = 13f
            ) {
                jobsFilter = "MINE"; showActiveContractorPortal(user)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        })

        val shiftBookings = if (jobsFilter == "MINE") myList else openList

        if (shiftBookings.isEmpty()) {
            val emptyCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 22)
            emptyCard.addView(TextView(this).apply {
                text = if (jobsFilter == "MINE") "No active or past jobs yet." else "No open bookings right now."
                textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            })
            emptyCard.addView(TextView(this).apply {
                text = if (jobsFilter == "MINE") {
                    "Request an available job from the Open Board or turn Auto-Assignment ON for Delivery & Ride."
                } else {
                    "New requests for ${shiftModeLabel(currentShifts)} appear here automatically in real time."
                }
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

        appendWalletLedgerSection(container, user)
        scroll.addView(container)
        return scroll
    }

    private fun buildContractorProfileTab(user: AuthenticatedUser): View {
        val prof = AlistoKernelClient.currentContractorProfile
        val approvedVerticals = getApprovedVerticals(prof)
        val currentShifts = getEffectiveShiftVerticals(prof)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        container.addView(TextView(this).apply {
            text = "Pro Profile"; textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(14))
        })

        // Identity & Vehicle card
        val card = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 18)
        card.addView(TextView(this).apply {
            text = user.fullName; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        })
        card.addView(TextView(this).apply {
            text = "@${user.username} • ${user.phone}\n${user.barangayZone}, ${user.municipality}"
            textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(4), 0, dp(12))
        })
        val plateStr = prof?.authorizedPlateNo?.takeIf { it.isNotBlank() }?.let { " • Plate: $it" }.orEmpty()
        val vTypeStr = prof?.vehicleType?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: "Skills Only"
        card.addView(AlistoUiKit.badgePill(this, "$vTypeStr$plateStr • Shift: ${shiftModeLabel(currentShifts)}", "#0C2D48", "#7DD3FC"))
        container.addView(card)

        // Auto-Assignment & Award Scorecard
        val autoRecv = prof?.autoOffersReceived ?: 0
        val autoAcc = prof?.autoOffersAccepted ?: 0
        val autoRej = prof?.autoOffersRejected ?: 0
        val autoTo = prof?.autoOffersTimedOut ?: 0
        val autoDecided = autoAcc + autoRej + autoTo
        val autoRate = if (autoDecided > 0) "${(autoAcc * 100) / autoDecided}%" else "—"
        val boardAcc = prof?.boardAwardsAccepted ?: 0
        val boardRej = (prof?.boardAwardsRejected ?: 0) + (prof?.boardAwardsTimedOut ?: 0)

        val perfCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 16)
        perfCard.addView(TextView(this).apply {
            text = "DISPATCH ACCEPTANCE METRICS"
            textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
            setPadding(0, 0, 0, dp(8))
        })
        perfCard.addView(TextView(this).apply {
            text = "Auto-Assignment Acceptance Rate: $autoRate"
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
        })
        perfCard.addView(TextView(this).apply {
            text = "Auto Offers: $autoRecv received • $autoAcc accepted • $autoRej rejected • $autoTo timed out\nOpen Board Awards: $boardAcc accepted • $boardRej declined/lapsed"
            textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(6), 0, 0)
        })
        container.addView(perfCard)

        // Shift mode switcher (kept neatly in Profile so Drive stays clutter-free)
        if (approvedVerticals.size > 1) {
            container.addView(
                AlistoUiKit.pillButton(
                    this, "Switch Active Shift Mode (${shiftModeLabel(currentShifts)})", "#0F1E3A", "#7DD3FC", "#1D4ED8", heightDp = 46, textSizeSp = 13.5f
                ) { openSwitchShiftDialog(user) }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                }
            )
        }

        // Overlay pop-up permission status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = Settings.canDrawOverlays(this)
            container.addView(
                AlistoUiKit.pillButton(
                    this,
                    if (granted) "Pop-Up Over Other Apps: ENABLED" else "Enable Pop-Up Over Other Apps",
                    if (granted) "#064E3B" else "#2A1B08",
                    if (granted) "#6EE7B7" else "#FDE68A",
                    if (granted) "#10B981" else "#B45309",
                    heightDp = 44, textSizeSp = 13f
                ) { openOverlayPermissionSettings() }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                }
            )
        }

        container.addView(AlistoUiKit.pillButton(this, "Sign Out & Go Offline", "#450A0A", "#FCA5A5", "#7F1D1D", heightDp = 46) {
            uiScope.launch {
                try {
                    AlistoKernelClient.toggleContractorDispatchModeRpc(
                        isOnline = false, autoAccept = false, activeShiftVerticals = currentShifts
                    )
                } catch (_: Exception) {}
                realtimePollJob?.cancel()
                AlistoLocationTracker.stopLiveUpdates()
                AlistoSpatialEngine.stopHeartbeat()
                ContractorDispatchMonitorService.stopMonitoring(this@ContractorMainActivity)
                ContractorDispatchMonitorService.saveSessionToken(this@ContractorMainActivity, null)
                promptedEarnModeThisSession = false
                autoOnlineSyncedThisSession = false
                AlistoKernelClient.signOut()
                showContractorEntryGate()
            }
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
                val requestBtn = AlistoUiKit.pillButton(
                    this@ContractorMainActivity, "Request Booking", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 44, textSizeSp = 13.5f
                ) {
                    uiScope.launch {
                        val res = AlistoKernelClient.requestOpenBookingRpc(b.id)
                        res.onSuccess { awardJson ->
                            val secs = awardJson.optInt("seconds_remaining", 25)
                            val offerPayload = JSONObject().apply {
                                put("seconds_remaining", secs)
                                put("offer", JSONObject().apply {
                                    put("id", b.id)
                                    put("booking_ref", b.bookingRef)
                                    put("offer_source", "BOARD_REQUEST")
                                    put("vertical_key", b.verticalKey)
                                    put("service_title", b.serviceTitle)
                                    put("pickup_address", b.pickupAddress)
                                    put("pickup_note", b.pickupNote)
                                    put("dropoff_address", b.dropoffAddress)
                                    put("dropoff_note", b.dropoffNote)
                                    put("booking_notes", b.bookingNotes)
                                    put("municipality", b.municipality)
                                    put("barangay_zone", b.barangayZone)
                                    put("quoted_fee", b.quotedFee)
                                    put("tip_amount", b.tipAmount)
                                    put("payment_method", b.paymentMethod)
                                    put("client_nickname", b.clientNickname)
                                    put("client_trust_score", b.clientTrustScore)
                                })
                            }
                            startActivity(ContractorOfferBridge.buildIntent(this@ContractorMainActivity, offerPayload))
                        }.onFailure { err ->
                            Toast.makeText(this@ContractorMainActivity, err.message ?: "Booking already awarded.", Toast.LENGTH_LONG).show()
                            AlistoKernelClient.syncBookingsAndWallet()
                            showActiveContractorPortal(user)
                        }
                    }
                }

                if (b.verticalKey == "skills_marketplace") {
                    card.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                        addView(AlistoUiKit.pillButton(
                            this@ContractorMainActivity,
                            if (myActiveBid != null) "Update Bid (₱${"%.0f".format(myActiveBid.bidAmountPhp)})" else "Place Bid",
                            "#0F294A", "#7DD3FC", "#38BDF8", heightDp = 44, textSizeSp = 13f
                        ) { promptSubmitOpenBid(b) }.apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply { rightMargin = dp(8) }
                        })
                        addView(requestBtn.apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
                        })
                    })
                } else {
                    card.addView(requestBtn.apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                    })
                }

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

    internal fun promptLtfrbSafetyRefusal(b: BookingRecord, user: AuthenticatedUser) {
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

    internal fun promptSubmitOpenBid(b: BookingRecord) {
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

    internal fun transitionJob(bookingId: String, nextStatus: String) {
        uiScope.launch {
            val res = AlistoKernelClient.advanceBookingStateRpc(bookingId, nextStatus)
            res.onSuccess {
                if (nextStatus == "COMPLETED") {
                    lastCompletedAtMs = System.currentTimeMillis()
                    ContractorSoundEngine.playCompletedTone(this@ContractorMainActivity)
                } else {
                    ContractorSoundEngine.playStepUpdatedTone(this@ContractorMainActivity)
                }
                Toast.makeText(this@ContractorMainActivity, "Status updated: ${nextStatus.replace("_", " ")}", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@ContractorMainActivity, err.message ?: "Status update failed.", Toast.LENGTH_LONG).show()
            }
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
            var tick = 0
            while (isActive) {
                delay(2_500L)
                tick += 1
                val tok = AlistoKernelClient.currentSessionToken
                if (tok.isNullOrBlank()) break

                // Check for any incoming Auto-Assign or Board Award offer immediately across ALL tabs
                val pendingEnvelope = AlistoKernelClient.fetchPendingContractorOfferRpc(tok)
                if (pendingEnvelope != null) {
                    val offerObj = pendingEnvelope.optJSONObject("offer")
                    val offerBid = offerObj?.optString("id").orEmpty()
                    val recentlyHandled = (ContractorOfferBridge.lastResultBookingId == offerBid &&
                        System.currentTimeMillis() - ContractorOfferBridge.lastHandledAtMs < 5500L)
                    if (offerBid.isNotBlank() && ContractorOfferBridge.activeBookingId != offerBid && !recentlyHandled) {
                        startActivity(ContractorOfferBridge.buildIntent(this@ContractorMainActivity, pendingEnvelope))
                    }
                }

                if (tick % 2 == 0) {
                    AlistoKernelClient.syncBookingsAndWallet()
                    val user = AlistoKernelClient.currentSessionUser
                    val shifts = getEffectiveShiftVerticals(AlistoKernelClient.currentContractorProfile)
                    val mapBookings = if (user != null) {
                        AlistoKernelClient.bookings.filter { it.contractorId == user.id || isBookingOpenForBoard(it, shifts, user.id) }
                    } else {
                        AlistoKernelClient.bookings
                    }
                    contractorMapView?.updateMapLayers(
                        bookings = mapBookings,
                        telemetryPins = AlistoKernelClient.liveTelemetryPins,
                        heatClusters = AlistoKernelClient.heatmapClusters
                    )
                    val digest = AlistoKernelClient.bookings.joinToString("|") {
                        "${it.id}:${it.status}:${it.contractorId}:${it.offerContractorId}:${it.customerGoingOutAt}"
                    }
                    if (user != null) {
                        val activeChatJob = AlistoKernelClient.bookings.firstOrNull {
                            it.contractorId == user.id && it.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")
                        }
                        if (activeChatJob != null) {
                            val chatRes = AlistoKernelClient.fetchBookingMessagesRpc(activeChatJob.id)
                            chatRes.onSuccess { msgs ->
                                val latest = msgs.lastOrNull()
                                if (latest != null && latest.id.isNotBlank()) {
                                    val prevId = lastSeenChatMsgIds[activeChatJob.id]
                                    lastSeenChatMsgIds[activeChatJob.id] = latest.id
                                    if (prevId != null && prevId != latest.id && latest.senderId != user.id) {
                                        ContractorSoundEngine.playStepUpdatedTone(this@ContractorMainActivity)
                                        Toast.makeText(this@ContractorMainActivity, "Message from ${latest.senderName}: ${latest.body}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                    if (user != null && digest != lastBookingsDigest) {
                        lastBookingsDigest = digest
                        if (activeTab == "JOBS" || activeTab == "DRIVE" || activeTab == "ACTIVE") {
                            showActiveContractorPortal(user)
                            break
                        }
                    }
                }
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
