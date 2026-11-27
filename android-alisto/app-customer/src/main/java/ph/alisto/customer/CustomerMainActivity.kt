package ph.alisto.customer

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.util.Calendar
import android.graphics.Typeface
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import ph.alisto.kernel.*
import ph.alisto.kernel.AlistoUiKit.dp

class CustomerMainActivity : AppCompatActivity() {

    internal val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val arrivedAlertedBookingIds = mutableSetOf<String>()
    internal val lastSeenChatMsgIds = mutableMapOf<String, String>()
    private var activeMapCardMinimized: Boolean = false
    private var entryMode = "LOGIN"
    private var activeTab = "BOOK"          // BOOK, ACTIVITY, ACCOUNT
    private var bookingStep = "LAUNCHER"    // LAUNCHER -> PICKUP_MAP
    private var activityFilter = "ACTIVE"   // ACTIVE, ALL
    private var sheetExpanded = false
    private var sheetWeightRatio = 0.50f
    private var showAdvancedOptions = false

    private var selectedVerticalKey = "skills_marketplace"
    private var lastVerticalsDigest: String = ""
    private var exitDialogShowing: Boolean = false
    private var selectedVehicleType = "Motorcycle"
    private var selectedPaymentMethod = "CASH"
    private var activeDeadheadInput: EditText? = null
    private var activeDeadheadNoticeView: TextView? = null
    private var activeClarkNoticeView: TextView? = null
    private var refreshVehicleUiCallback: (() -> Unit)? = null
    private var lastRemoteNoticeKey: String = ""
    private val lastBookingStatuses = mutableMapOf<String, String>()
    private var lastCustomerLocSyncMs: Long = 0L
    private var selectedJobSubtype = "ON_SITE_SKILL"
    private var selectedServiceLabel = "Request Skills & Trades"
    private var selectedSkillCategory = "General Service"

    private var compressedProfilePhotoDataUrl: String? = null
    private val sequentialWaypoints = mutableListOf<JSONObject>()
    private var realtimePollJob: Job? = null
    private var chatHeartbeatJob: Job? = null
    private var pendingOpenChatBookingId: String? = null
    private var pickupSearchJob: Job? = null
    private var dropoffSearchJob: Job? = null
    private var reverseGeoJob: Job? = null
    private var routeCalcJob: Job? = null
    private var activeRoutePoints: List<Pair<Double, Double>> = emptyList()
    private var activeRouteDistanceKm: Double = 0.0
    private var activeRouteDurationMin: Double = 0.0
    private var activeCalculatedFare: Double? = null
    private var userEditedFeeManually = false
    private var suppressFeeWatcher = false
    private var activeFeeInput: EditText? = null
    private var activeQuoteBanner: TextView? = null

    private var customerMapView: AlistoInteractiveMapView? = null
    private var activePickupField: EditText? = null
    private var suppressAutocomplete = false

    private var activeOriginLat: Double = 15.1450
    private var activeOriginLng: Double = 120.5887
    private var activeDropoffLat: Double? = null
    private var activeDropoffLng: Double? = null
    private var originPinnedManually = false
    private var promptedLocationPerm = false
    private var hasAutoCenteredGps = false

    // Active crosshair drop-pin state (null, "PICKUP", "DROPOFF", or "STOP")
    private var dropPinTarget: String? = null
    private var pendingPinLat: Double = 15.1450
    private var pendingPinLng: Double = 120.5887
    private var pendingPinAddress: String = ""

    private var draftTitle: String = ""
    private var draftPickupAddress: String = ""
    private var draftPickupNote: String = ""
    private var draftDropoffAddress: String = ""
    private var draftDropoffNote: String = ""
    private var draftBookingNotes: String = ""
    private var draftOfferFee: String = ""
    private var draftTip: String = ""
    private var draftErrandGoods: String = "0"
    private var draftDeadheadKm: String = "0"
    private var isSubmittingBooking: Boolean = false
    private var pendingBookingIdempotencyKey: String? = null
    private var pendingBookingSignature: String = ""
    private var isSubmittingP2pTransfer: Boolean = false
    private var selectedScheduledStartIso: String? = null
    private var selectedScheduledLabel: String = ""

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("alisto_active_tab", activeTab)
        outState.putString("alisto_booking_step", bookingStep)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(AlistoUiKit.BG_DEEP)))
        savedInstanceState?.let { st ->
            activeTab = st.getString("alisto_active_tab", activeTab) ?: activeTab
            bookingStep = st.getString("alisto_booking_step", bookingStep) ?: bookingStep
        }
        registerBackExitConfirmation()
        AlistoUiKit.applyDarkSystemBars(this)
        // Resume directly if the session is still live in memory after switching apps.
        if (AlistoKernelClient.currentSessionUser != null) {
            showAuthenticatedCustomerPortal()
        } else {
            showAnimatedLaunchSplash()
        }
    }

    private fun showAnimatedLaunchSplash() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val logoView = ImageView(this).apply {
            setImageResource(R.drawable.ic_alisto_app_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(134), dp(134)).apply { bottomMargin = dp(18) }
        }
        centerCol.addView(logoView)
        centerCol.addView(TextView(this).apply {
            text = "ALISTO"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.14f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            gravity = Gravity.CENTER
        })
        centerCol.addView(TextView(this).apply {
            text = "Local Pros • Delivery • Rides"
            textSize = 13f
            setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(18))
        })
        centerCol.addView(ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        })
        root.addView(centerCol)
        setContentView(root)

        val pulse = ObjectAnimator.ofPropertyValuesHolder(
            logoView,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.90f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.90f, 1.08f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.80f, 1.0f)
        ).apply {
            duration = 850L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        uiScope.launch {
            delay(4000L)
            pulse.cancel()
            if (isFinishing || isDestroyed) return@launch
            if (AlistoKernelClient.currentSessionUser != null) {
                showAuthenticatedCustomerPortal()
            } else if (AlistoBiometricVault.hasSavedSession(this@CustomerMainActivity, "customer")) {
                triggerFrictionlessBiometricEntry()
            } else {
                showEntryPortalGate()
            }
        }
    }

    // Intercepts hardware back and Android gesture back so accidental swipes do not kill the app.
    private fun registerBackExitConfirmation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (AlistoKernelClient.currentSessionUser != null) {
                    if (activeTab == "BOOK" && bookingStep != "LAUNCHER") {
                        bookingStep = "LAUNCHER"
                        showAuthenticatedCustomerPortal()
                        return
                    }
                    if (activeTab != "BOOK") {
                        activeTab = "BOOK"
                        bookingStep = "LAUNCHER"
                        showAuthenticatedCustomerPortal()
                        return
                    }
                }
                if (exitDialogShowing) return
                exitDialogShowing = true
                AlertDialog.Builder(this@CustomerMainActivity)
                    .setTitle("Exit Alisto?")
                    .setMessage("Are you sure you want to close the app?")
                    .setPositiveButton("Exit") { _, _ ->
                        exitDialogShowing = false
                        finish()
                    }
                    .setNegativeButton("Stay") { _, _ ->
                        exitDialogShowing = false
                    }
                    .setOnDismissListener { exitDialogShowing = false }
                    .show()
            }
        })
    }

    private fun timeOfDayGreeting(nickname: String): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val prefix = when {
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$prefix, ${nickname.ifBlank { "Friend" }}"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleChatLaunchIntent(intent)
    }

    private fun handleChatLaunchIntent(incoming: Intent?) {
        val bid = incoming?.getStringExtra(AlistoChatEngine.EXTRA_OPEN_CHAT_BOOKING_ID)?.takeIf { it.isNotBlank() } ?: return
        incoming.removeExtra(AlistoChatEngine.EXTRA_OPEN_CHAT_BOOKING_ID)
        pendingOpenChatBookingId = bid
        maybeOpenPendingChatDialog()
    }

    private fun maybeOpenPendingChatDialog() {
        val bid = pendingOpenChatBookingId ?: return
        val booking = AlistoKernelClient.bookings.firstOrNull { it.id == bid } ?: return
        pendingOpenChatBookingId = null
        openLiveCustomerChatDialog(booking)
    }

    override fun onResume() {
        super.onResume()
        handleChatLaunchIntent(intent)
        val resumeUser = AlistoKernelClient.currentSessionUser
        if (resumeUser != null) {
            if (AlistoLocationTracker.ensureExplicitGpsGate(this, "Alisto Customer")) {
                startLiveGpsOnMap(forceCenter = false, snapOriginToGps = false)
            }
            syncCustomerChatEngineWatcher(resumeUser)
            maybeOpenPendingChatDialog()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AlistoLocationTracker.REQ_LOCATION_PERMISSION) {
            if (AlistoLocationTracker.ensureExplicitGpsGate(this, "Alisto Customer")) {
                startLiveGpsOnMap(forceCenter = true, snapOriginToGps = !originPinnedManually)
            }
        }
    }

    private fun triggerFrictionlessBiometricEntry() {
        AlistoBiometricVault.promptBiometricUnlock(
            activity = this,
            portalRole = "customer",
            onUnlockedUserId = { savedUid ->
                uiScope.launch {
                    val res = AlistoKernelClient.fetchAppBootStateRpc(savedUid, "customer")
                    res.onSuccess {
                        activeTab = "BOOK"
                        bookingStep = "LAUNCHER"
                        showAuthenticatedCustomerPortal()
                    }.onFailure { showEntryPortalGate() }
                }
            },
            onFallbackOrError = { showEntryPortalGate() }
        )
    }

    private fun showEntryPortalGate() {
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
                "ALISTO",
                "Verified local trades, multi-stop errands & mobility",
                AlistoUiKit.CYAN
            )
        )

        if (AlistoBiometricVault.hasSavedSession(this, "customer")) {
            val savedLabel = AlistoBiometricVault.getSavedUserLabel(this, "customer")
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
            background = AlistoUiKit.roundedBg(this@CustomerMainActivity, AlistoUiKit.BG_INPUT, 22, AlistoUiKit.BORDER)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }

            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Log In", entryMode == "LOGIN", AlistoUiKit.CYAN) {
                entryMode = "LOGIN"; showEntryPortalGate()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })

            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Create Account", entryMode == "SIGNUP", AlistoUiKit.CYAN) {
                entryMode = "SIGNUP"; showEntryPortalGate()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        }
        formCard.addView(tabRow)

        if (entryMode == "LOGIN") {
            val idInput = AlistoUiKit.inputField(this, "Username, mobile (+63...), or email")
            formCard.addView(AlistoUiKit.labeledField(this, "Account Identifier", idInput))
            val pwInput = AlistoUiKit.inputField(
                this, "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            formCard.addView(AlistoUiKit.labeledField(this, "Password", pwInput, bottomMarginDp = 18))

            formCard.addView(
                AlistoUiKit.pillButton(this, "Sign In", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A") {
                    uiScope.launch {
                        val res = AlistoKernelClient.authenticateUserRpc(
                            idInput.text.toString(), pwInput.text.toString(), "customer"
                        )
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(
                                this@CustomerMainActivity, "customer",
                                AlistoKernelClient.currentSessionToken ?: "",
                                "${user.nickname} (@${user.username})"
                            )
                            activeTab = "BOOK"
                            bookingStep = "LAUNCHER"
                            showAuthenticatedCustomerPortal()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity)
                                .setTitle("Sign In Failed").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
            )
        } else {
            val fullNameInput = AlistoUiKit.inputField(this, "e.g., Clarisse Dizon")
            formCard.addView(AlistoUiKit.labeledField(this, "Full Name", fullNameInput))
            val nicknameInput = AlistoUiKit.inputField(this, "e.g., Clarisse")
            formCard.addView(AlistoUiKit.labeledField(this, "Preferred Nickname", nicknameInput))
            val phoneInput = AlistoUiKit.inputField(this, "+63917XXXXXXX", InputType.TYPE_CLASS_PHONE)
            formCard.addView(AlistoUiKit.labeledField(this, "Mobile Number", phoneInput))
            val emailInput = AlistoUiKit.inputField(this, "name@email.com", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            formCard.addView(AlistoUiKit.labeledField(this, "Email Address", emailInput))
            val pwInput = AlistoUiKit.inputField(
                this, "Create permanent password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            formCard.addView(AlistoUiKit.labeledField(this, "Password", pwInput))
            val addrInput = AlistoUiKit.inputField(this, "House No., Street, Barangay, City")
            formCard.addView(AlistoUiKit.labeledField(this, "Home Address", addrInput, bottomMarginDp = 8))

            var homeLat = 15.1450
            var homeLng = 120.5887
            val gpsCoordText = TextView(this).apply {
                text = "Pin: ${AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, homeLat, homeLng)}"
                setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
                textSize = 12f
                setPadding(dp(2), 0, 0, dp(10))
            }
            formCard.addView(gpsCoordText)

            formCard.addView(
                AlistoUiKit.pillButton(
                    this, "Use My Current GPS Location", AlistoUiKit.BG_INPUT, AlistoUiKit.CYAN,
                    AlistoUiKit.BORDER_LIGHT, heightDp = 42, textSizeSp = 13f
                ) {
                    if (!AlistoLocationTracker.hasPermission(this)) {
                        AlistoLocationTracker.requestPermission(this)
                    } else {
                        val fix = AlistoLocationTracker.getBestLastKnown(this)
                        if (fix != null) {
                            homeLat = fix.latitude
                            homeLng = fix.longitude
                            val rev = AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, homeLat, homeLng)
                            addrInput.setText(rev)
                            gpsCoordText.text = "GPS Fix: $rev"
                        } else {
                            Toast.makeText(this, "Waiting for GPS lock. Ensure Location is turned on.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(14) }
                }
            )

            formCard.addView(
                AlistoUiKit.pillButton(this, "Create Account", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A") {
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
                            AlistoBiometricVault.saveEncryptedSession(
                                this@CustomerMainActivity, "customer",
                                AlistoKernelClient.currentSessionToken ?: "",
                                "${user.nickname} (@${user.username})"
                            )
                            activeTab = "BOOK"
                            bookingStep = "LAUNCHER"
                            showAuthenticatedCustomerPortal()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity)
                                .setTitle("Sign-Up Error").setMessage(err.message).setPositiveButton("OK", null).show()
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

    internal fun showAuthenticatedCustomerPortal() {
        val user = AlistoKernelClient.currentSessionUser ?: return showEntryPortalGate()
        AlistoPushCenter.ensureNotificationPermission(this)
        AlistoPushCenter.dispatchUnreadNotifications(this, uiScope)

        if (draftPickupAddress.isBlank()) {
            activeOriginLat = AlistoLocationTracker.lastLat ?: user.homeLat
            activeOriginLng = AlistoLocationTracker.lastLng ?: user.homeLng
            draftPickupAddress = user.addressText.ifBlank { "${user.barangayZone}, ${user.municipality}" }
        }
        AlistoChatEngine.registerHost(this, CustomerMainActivity::class.java.name) {
            CustomerDoorbellEngine.playIncomingChatChime(this)
        }
        uiScope.launch { AlistoKernelClient.fetchActiveHighDemandZonesRpc() }
        startCustomerRealtimeLoop()
        handleChatLaunchIntent(intent)
        AlistoComplianceGate.checkOtaUpdate(this, "customer", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        AlistoComplianceGate.enforceRa10173ConsentIfRequired(this, uiScope, "customer")

        if (AlistoLocationTracker.ensureExplicitGpsGate(this, "Alisto Customer")) {
            startLiveGpsOnMap(forceCenter = false, snapOriginToGps = false)
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }
        val contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        when (activeTab) {
            "ACTIVITY" -> contentHost.addView(buildCustomerActivityTab(user))
            "WALLET" -> contentHost.addView(buildCustomerWalletTab(user))
            "ACCOUNT" -> contentHost.addView(buildCustomerAccountTab(user))
            else -> {
                if (bookingStep == "LAUNCHER") contentHost.addView(buildServiceLauncherScreen(user))
                else contentHost.addView(buildCustomerPickupMapScreen(user))
            }
        }

        val myActiveCount = AlistoKernelClient.bookings.count {
            (user.role == "super_admin" || it.clientId == user.id) &&
                it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")
        }
        val bottomNav = AlistoUiKit.bottomNavBar(
            this,
            tabs = listOf(
                Triple("BOOK", "◎", "Home"),
                Triple("ACTIVITY", "☰", if (myActiveCount > 0) "Activity ($myActiveCount)" else "Activity"),
                Triple("WALLET", "₱", "Wallet"),
                Triple("ACCOUNT", "●", "Account")
            ),
            activeKey = activeTab,
            accentHex = AlistoUiKit.CYAN
        ) { nextTab ->
            activeTab = nextTab
            showAuthenticatedCustomerPortal()
        }

        shell.addView(contentHost)
        shell.addView(bottomNav)
        setContentView(shell)
    }

    private fun buildServiceLauncherScreen(user: AuthenticatedUser): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }

        val mapStage = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val mapContainer = AlistoInteractiveMapView(this)
        customerMapView = mapContainer
        mapStage.addView(
            mapContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val initLat = AlistoLocationTracker.lastLat ?: user.homeLat
        val initLng = AlistoLocationTracker.lastLng ?: user.homeLng
        mapContainer.loadInteractiveMap(initLat, initLng, enableTapToPin = false)
        AlistoLocationTracker.lastLat?.let { lat ->
            AlistoLocationTracker.lastLng?.let { lng ->
                mapContainer.updateUserLocation(lat, lng, AlistoLocationTracker.lastAccuracyM, flyTo = false)
            }
        }
        refreshCustomerMapLayers(user)

        val topHud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        }
        topHud.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AlistoUiKit.frostedGlassBg(this@CustomerMainActivity, "#C40D3B8A", 24, "#997DD3FC", alphaFraction = 0.78f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }

            addView(View(this@CustomerMainActivity).apply {
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, AlistoUiKit.EMERALD, 6)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
            })
            addView(TextView(this@CustomerMainActivity).apply {
                text = "${timeOfDayGreeting(user.nickname)} • ${user.municipality}"
                textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); maxLines = 1
            })
        })
        topHud.addView(AlistoUiKit.chipButton(this, "My GPS", true, "#C40D3B8A", AlistoUiKit.GOLD_SOFT) {
            if (!AlistoLocationTracker.hasPermission(this)) AlistoLocationTracker.requestPermission(this)
            else startLiveGpsOnMap(forceCenter = true, snapOriginToGps = false)
        }.apply { elevation = dp(4).toFloat() })
        mapStage.addView(topHud)
        root.addView(mapStage)

        val activeBookings = AlistoKernelClient.bookings.filter {
            (user.role == "super_admin" || it.clientId == user.id) &&
                it.status !in listOf("COMPLETED", "COMPLETED_WITH_DISPUTE", "CANCELLED", "EXPIRED")
        }
        val hasActive = activeBookings.isNotEmpty()

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = AlistoUiKit.topSheetBg(this@CustomerMainActivity, AlistoUiKit.BG_SURFACE, 20, AlistoUiKit.BORDER)
            elevation = dp(12).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }
        sheet.addView(AlistoUiKit.dragHandle(this))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (!hasActive) {
            // Wrap tightly to enabled verticals; cap at 58% height with internal scroll if taller.
            root.post {
                if (root.height > 0) {
                    val maxSheetHeight = (root.height * 0.58f).toInt()
                    if (sheet.height > maxSheetHeight) {
                        (sheet.layoutParams as? FrameLayout.LayoutParams)?.height = maxSheetHeight
                        scroll.layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                        sheet.requestLayout()
                    }
                }
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(12))
        }

        if (hasActive) {
            val topB = activeBookings.first()
            val bidCount = AlistoKernelClient.bookingBids.count { it.bookingId == topB.id && it.status == "ACTIVE" }
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#0C2942", 12, AlistoUiKit.CYAN)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                addView(LinearLayout(this@CustomerMainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { activeTab = "ACTIVITY"; showAuthenticatedCustomerPortal() }
                    addView(TextView(this@CustomerMainActivity).apply {
                        text = "${topB.bookingRef} • ${topB.status.replace("_", " ")}" + if (bidCount > 0) " • $bidCount offer(s)" else ""
                        textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.CYAN))
                    })
                    if (!topB.contractorFullName.isNullOrBlank()) {
                        addView(TextView(this@CustomerMainActivity).apply {
                            text = "Pro: ${topB.contractorFullName} (Tap Profile)"
                            textSize = 11.5f; setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
                            setOnClickListener { showAssignedContractorProfileModal(topB) }
                        })
                    }
                })
                if (topB.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")) {
                    addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Chat", true, "#064E3B", "#6EE7B7") {
                        openLiveCustomerChatDialog(topB)
                    }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) } })
                }
                addView(AlistoUiKit.chipButton(this@CustomerMainActivity, if (activeMapCardMinimized) "More" else "Map", false, "#1E293B", "#E2E8F0") {
                    activeMapCardMinimized = !activeMapCardMinimized
                    showAuthenticatedCustomerPortal()
                })
            })
            if (!activeMapCardMinimized) {
                body.addView(buildKindnessAndWaitingBanner(topB, compactForMap = true))
            }
        } else {
            body.addView(TextView(this).apply {
                text = "What do you need today?"
                textSize = 21f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            })
            body.addView(TextView(this).apply {
                text = "Choose a service to set your pickup, destination, and notes."
                textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(3), 0, dp(14))
            })

            data class LauncherOption(
                val title: String, val subtitle: String, val badge: String, val accent: String,
                val vKey: String, val sub: String, val cat: String
            )
            val options = listOf(
                LauncherOption("Request Delivery", "Send packages, food, or documents point-to-point", "DELIVERY", AlistoUiKit.AMBER, "delivery_logistics", "POINT_TO_POINT", "Parcel & Delivery"),
                LauncherOption("Request Errands (Pabili)", "Multi-stop store pickups, groceries & personal errands", "ERRANDS", AlistoUiKit.CYAN, "delivery_logistics", "ERRAND_PURCHASING", "Errands & Pabili"),
                LauncherOption("Request Skills & Trades", "Book verified electricians, plumbers, aircon techs & shops", "SKILLS", AlistoUiKit.EMERALD, "skills_marketplace", "ON_SITE_SKILL", "General Service"),
                LauncherOption("Request a Local Ride", "Point-to-point sub-400cc local road mobility", "MOBILITY", "#38BDF8", "ride_hailing", "POINT_TO_POINT", "Local Ride")
            )

            // Hide any service vertical paused by SuperAdmin; it reappears automatically when re-enabled.
            val activeOptions = options.filter { AlistoKernelClient.isVerticalEnabled(it.vKey) }
            if (activeOptions.isEmpty()) {
                val emptyCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER_LIGHT, padDp = 16)
                emptyCard.addView(TextView(this).apply {
                    text = "Services Temporarily Paused"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(AlistoUiKit.AMBER))
                })
                emptyCard.addView(TextView(this).apply {
                    text = "No service verticals are active right now. Available services will appear here automatically once reactivated."
                    textSize = 12.5f
                    setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                    setPadding(0, dp(4), 0, 0)
                })
                body.addView(emptyCard)
            }

            activeOptions.forEach { opt ->
                val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER_LIGHT, padDp = 15)
                card.setOnClickListener {
                    selectedVerticalKey = opt.vKey
                    selectedJobSubtype = opt.sub
                    selectedServiceLabel = opt.title
                    selectedSkillCategory = opt.cat
                    dropPinTarget = null
                    activeDropoffLat = null
                    activeDropoffLng = null
                    draftDropoffAddress = ""
                    sequentialWaypoints.clear()
                    activeRoutePoints = emptyList()
                    activeRouteDistanceKm = 0.0
                    activeRouteDurationMin = 0.0
                    activeCalculatedFare = null
                    userEditedFeeManually = false
                    draftOfferFee = ""
                    bookingStep = "PICKUP_MAP"
                    sheetExpanded = false
                    showAuthenticatedCustomerPortal()
                }
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(this@CustomerMainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
                        addView(AlistoUiKit.badgePill(this@CustomerMainActivity, opt.badge, AlistoUiKit.BG_INPUT, opt.accent).apply {
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                        })
                        addView(TextView(this@CustomerMainActivity).apply {
                            text = opt.title; textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                        })
                        addView(TextView(this@CustomerMainActivity).apply {
                            text = opt.subtitle; textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(2), 0, 0)
                        })
                    })
                    addView(TextView(this@CustomerMainActivity).apply {
                        text = "→"; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(opt.accent))
                    })
                })
                body.addView(card)
            }
        }

        scroll.addView(body)
        sheet.addView(scroll)
        root.addView(sheet)
        return root
    }

    private fun buildCustomerPickupMapScreen(user: AuthenticatedUser): View {
        val needsDropoff = selectedVerticalKey != "skills_marketplace"
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }

        val mapStage = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val mapContainer = AlistoInteractiveMapView(this)
        customerMapView = mapContainer
        mapStage.addView(
            mapContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // Floating Top Bar: Back + Service Badge + Current GPS
        val topHud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        topHud.addView(AlistoUiKit.chipButton(this, "← Services", true, "#C40D3B8A", AlistoUiKit.TEXT_PRIMARY) {
            dropPinTarget = null
            bookingStep = "LAUNCHER"
            showAuthenticatedCustomerPortal()
        }.apply {
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        })
        topHud.addView(TextView(this).apply {
            text = selectedServiceLabel; textSize = 12.5f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
            background = AlistoUiKit.frostedGlassBg(this@CustomerMainActivity, "#C40D3B8A", 20, "#CCFACC15", alphaFraction = 0.78f)
            setPadding(dp(12), dp(8), dp(12), dp(8)); elevation = dp(4).toFloat(); maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        })
        topHud.addView(AlistoUiKit.chipButton(this, "My GPS", true, "#C40D3B8A", AlistoUiKit.GOLD_SOFT) {
            snapPickupToCurrentGps()
        }.apply { elevation = dp(4).toFloat() })
        mapStage.addView(topHud)

        // Floating Crosshair Drop-Pin Confirmation Bar (visible only while dragging pin)
        var applySheetRatio: ((Float) -> Unit)? = null
        val dropPinPreviewText = TextView(this).apply {
            text = "Drag map to position pin..."
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        }
        lateinit var confirmPinBtn: Button
        val dropPinBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = AlistoUiKit.frostedGlassBg(this@CustomerMainActivity, "#D40C357D", 16, "#CCFACC15", alphaFraction = 0.84f)
            setPadding(dp(12), dp(10), dp(10), dp(10))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            ).apply {
                leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(10)
            }
            addView(dropPinPreviewText)
            confirmPinBtn = AlistoUiKit.pillButton(this@CustomerMainActivity, "Confirm Pin", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, heightDp = 38, textSizeSp = 12f) {}
            addView(confirmPinBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "X", true, AlistoUiKit.BG_CARD, AlistoUiKit.ROSE) {
                dropPinTarget = null
                customerMapView?.setDropPinMode(false)
                visibility = View.GONE
                applySheetRatio?.invoke(0.50f)
            })
        }
        mapStage.addView(dropPinBar)
        root.addView(mapStage)

        // Rounded Frosted Bottom Sheet floating over full-bleed map (Draggable & Collapsible)
        val sheetWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = AlistoUiKit.topSheetBg(this@CustomerMainActivity, AlistoUiKit.BG_SURFACE, 24, AlistoUiKit.BORDER)
            elevation = dp(12).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * sheetWeightRatio.coerceIn(0.14f, 0.76f)).toInt(),
                Gravity.BOTTOM
            )
        }
        val sheetDragHint = TextView(this).apply {
            text = if (sheetWeightRatio <= 0.22f) "▲ Drag or tap to expand booking details" else "Drag handle to resize map"
            textSize = 10.5f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        applySheetRatio = { rawRatio: Float ->
            val clamped = rawRatio.coerceIn(0.14f, 0.76f)
            sheetWeightRatio = clamped
            sheetExpanded = clamped > 0.58f
            val totalH = (root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
            val sheetH = (totalH * clamped).toInt().coerceAtLeast(dp(64))
            (sheetWrap.layoutParams as? FrameLayout.LayoutParams)?.height = sheetH
            (dropPinBar.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = sheetH + dp(10)
            sheetDragHint.text = if (clamped <= 0.22f) "▲ Drag or tap to expand booking details" else "Drag handle to resize map"
            sheetWrap.requestLayout()
            dropPinBar.requestLayout()
        }
        root.post { applySheetRatio?.invoke(sheetWeightRatio) }
        val handleBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(AlistoUiKit.dragHandle(this@CustomerMainActivity))
            addView(sheetDragHint)
        }
        var dragStartRawY = 0f
        var dragStartRatio = sheetWeightRatio
        var movedEnough = false
        handleBar.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = ev.rawY
                    dragStartRatio = sheetWeightRatio
                    movedEnough = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - dragStartRawY
                    if (kotlin.math.abs(dy) > dp(6)) movedEnough = true
                    val totalH = (root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
                    applySheetRatio?.invoke(dragStartRatio - (dy / totalH))
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!movedEnough) {
                        val next = when {
                            sheetWeightRatio <= 0.24f -> 0.50f
                            sheetWeightRatio >= 0.62f -> 0.16f
                            else -> 0.72f
                        }
                        applySheetRatio?.invoke(next)
                    }
                    true
                }
                else -> false
            }
        }
        sheetWrap.addView(handleBar)

        val sheetScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val sheetBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(22))
        }

        // 0. VEHICLE CLASS SELECTOR (Motorcycle / Tricycle / E-Bike) with Clark Freeport Zone Lock
        activeClarkNoticeView = null
        refreshVehicleUiCallback = null
        if (needsDropoff) {
            val vehicleCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 12)
            vehicleCard.addView(TextView(this).apply {
                text = if (selectedVerticalKey == "ride_hailing") "Select Vehicle (Local Ride • Sub-400cc)" else "Select Vehicle (Delivery / Errands)"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.CYAN))
                setPadding(0, 0, 0, dp(8))
            })
            val vehicleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val clarkNotice = TextView(this).apply {
                text = "Clark Freeport Zone Regulation: Tricycles and E-Bikes are not allowed inside Clark. Motorcycle has been selected automatically."
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
                setPadding(dp(2), dp(8), dp(2), 0)
                visibility = View.GONE
            }
            activeClarkNoticeView = clarkNotice

            val vehicleOptions = listOf("Motorcycle", "Tricycle", "E-Bike")
            val vehicleBtns = mutableListOf<Pair<String, TextView>>()
            fun refreshVehicleButtons() {
                val clarkLocked = isClarkRestrictedTrip()
                if (clarkLocked && selectedVehicleType != "Motorcycle") {
                    selectedVehicleType = "Motorcycle"
                    userEditedFeeManually = false
                }
                clarkNotice.visibility = if (clarkLocked) View.VISIBLE else View.GONE
                vehicleBtns.forEach { (vName, btn) ->
                    val restricted = clarkLocked && vName != "Motorcycle"
                    val active = selectedVehicleType == vName
                    btn.alpha = if (restricted) 0.36f else 1.0f
                    btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                    btn.setTextColor(Color.parseColor(if (active) AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_PRIMARY))
                    btn.background = AlistoUiKit.frostedGlassBg(
                        this@CustomerMainActivity,
                        fillHex = if (active) AlistoUiKit.GOLD_ACCENT else "#A6164CA8",
                        radiusDp = 18,
                        strokeHex = if (active) "#E6FEF08A" else AlistoUiKit.BORDER,
                        alphaFraction = if (active) 0.90f else 0.72f
                    )
                }
            }
            refreshVehicleUiCallback = { refreshVehicleButtons() }

            vehicleOptions.forEachIndexed { idx, vName ->
                val btn = TextView(this).apply {
                    text = vName
                    gravity = Gravity.CENTER
                    textSize = 12.5f
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx < vehicleOptions.lastIndex) rightMargin = dp(8)
                    }
                    setOnClickListener {
                        if (isClarkRestrictedTrip() && vName != "Motorcycle") {
                            Toast.makeText(
                                this@CustomerMainActivity,
                                "Tricycles and E-Bikes are not permitted inside Clark Freeport Zone. Please use Motorcycle.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@setOnClickListener
                        }
                        selectedVehicleType = vName
                        userEditedFeeManually = false
                        refreshVehicleButtons()
                        applyFareFromSuperAdminRules()
                    }
                }
                vehicleBtns.add(vName to btn)
                vehicleRow.addView(btn)
            }
            refreshVehicleButtons()
            vehicleCard.addView(vehicleRow)
            vehicleCard.addView(clarkNotice)
            sheetBody.addView(vehicleCard)
        }

        // 1. PICKUP / JOB SITE CARD (Live Autocomplete + Drop Pin + GPS + Pickup Note)
        val pickupCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
        val pickupTitle = if (needsDropoff) "● Pickup Location" else "● Job Site Location"
        val pickupInput = AlistoUiKit.inputField(this, "Type landmark, subdivision, or street...", InputType.TYPE_CLASS_TEXT, draftPickupAddress)
        activePickupField = pickupInput
        val pickupSuggestionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(6))
        }

        val pickupNoteInput = AlistoUiKit.inputField(
            this, "Pickup note (gate color, bldg/unit, landmark)...", InputType.TYPE_CLASS_TEXT, draftPickupNote
        )

        // Drop-Off references (declared ahead so Drop-Pin confirm can write to either)
        var dropoffInput: EditText? = null
        var dropoffNoteInput: EditText? = null
        var waypointsStatusRef: TextView? = null

        fun enterDropPinMode(target: String, label: String, colorHex: String, startLat: Double, startLng: Double) {
            dropPinTarget = target
            pendingPinLat = startLat
            pendingPinLng = startLng
            dropPinPreviewText.text = "$label — move map, then tap Confirm"
            dropPinBar.visibility = View.VISIBLE
            applySheetRatio?.invoke(0.15f)
            customerMapView?.recenter(startLat, startLng, 16)
            customerMapView?.setDropPinMode(true, label, colorHex) { lat, lng ->
                pendingPinLat = lat
                pendingPinLng = lng
                dropPinPreviewText.text = "Locating pin..."
                reverseGeoJob?.cancel()
        routeCalcJob?.cancel()
                reverseGeoJob = uiScope.launch {
                    delay(250L)
                    val addr = withContext(Dispatchers.IO) {
                        AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, lat, lng)
                    }
                    pendingPinAddress = addr
                    dropPinPreviewText.text = addr
                }
            }
        }

        confirmPinBtn.setOnClickListener {
            val target = dropPinTarget ?: return@setOnClickListener
            val lat = pendingPinLat
            val lng = pendingPinLng
            val addr = pendingPinAddress.ifBlank {
                AlistoSpatialEngine.reverseGeocodePin(this, lat, lng)
            }
            suppressAutocomplete = true
            when (target) {
                "PICKUP" -> {
                    activeOriginLat = lat
                    activeOriginLng = lng
                    originPinnedManually = true
                    draftPickupAddress = addr
                    pickupInput.setText(addr)
                    pickupSuggestionsBox.visibility = View.GONE
                    Toast.makeText(this, "Pickup pinned: $addr", Toast.LENGTH_SHORT).show()
                }
                "DROPOFF" -> {
                    activeDropoffLat = lat
                    activeDropoffLng = lng
                    draftDropoffAddress = addr
                    dropoffInput?.setText(addr)
                    Toast.makeText(this, "Drop-off pinned: $addr", Toast.LENGTH_SHORT).show()
                }
                "STOP" -> {
                    val stopNum = sequentialWaypoints.size + 1
                    sequentialWaypoints.add(JSONObject().apply {
                        put("stop_number", stopNum); put("lat", lat); put("lng", lng); put("address", addr)
                    })
                    waypointsStatusRef?.text = "Extra Stops (${sequentialWaypoints.size}): " + sequentialWaypoints.joinToString(" → ") {
                        "#${it.getInt("stop_number")} ${it.getString("address")}"
                    }
                    waypointsStatusRef?.setTextColor(Color.parseColor(AlistoUiKit.AMBER))
                    Toast.makeText(this, "Stop #$stopNum pinned", Toast.LENGTH_SHORT).show()
                }
            }
            suppressAutocomplete = false
            dropPinTarget = null
            customerMapView?.setDropPinMode(false)
            dropPinBar.visibility = View.GONE
            applySheetRatio?.invoke(0.50f)
            recalculateRouteAndFare(user)
        }

        attachInlineAutocomplete(
            input = pickupInput,
            suggestionsBox = pickupSuggestionsBox,
            isPickup = true
        ) { picked ->
            suppressAutocomplete = true
            activeOriginLat = picked.lat
            activeOriginLng = picked.lng
            originPinnedManually = true
            draftPickupAddress = "${picked.title} — ${picked.subtitle}"
            pickupInput.setText(draftPickupAddress)
            pickupInput.setSelection(pickupInput.text.length)
            suppressAutocomplete = false
            pickupSuggestionsBox.visibility = View.GONE
            customerMapView?.recenter(picked.lat, picked.lng, 16)
            recalculateRouteAndFare(user)
        }

        pickupCard.addView(AlistoUiKit.labeledField(this, pickupTitle, pickupInput, 4))
        pickupCard.addView(pickupSuggestionsBox)
        pickupCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Use Current GPS", true, AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD) {
                snapPickupToCurrentGps()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Drop Pin on Map", true, AlistoUiKit.BG_INPUT, AlistoUiKit.CYAN) {
                enterDropPinMode("PICKUP", "Set Pickup Pin", AlistoUiKit.EMERALD, activeOriginLat, activeOriginLng)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        })
        pickupCard.addView(AlistoUiKit.labeledField(this, "Pickup / Job Site Note (Optional)", pickupNoteInput, 2))
        sheetBody.addView(pickupCard)

        // 2. DROP-OFF / DESTINATION CARD (Delivery, Errands, Ride)
        if (needsDropoff) {
            val dropCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
            val dInput = AlistoUiKit.inputField(this, "Type drop-off landmark or address...", InputType.TYPE_CLASS_TEXT, draftDropoffAddress)
            dropoffInput = dInput
            val dropSuggestionsBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(0, dp(4), 0, dp(6))
            }
            val dNoteInput = AlistoUiKit.inputField(
                this, "Drop-off note (recipient name, gate, instructions)...", InputType.TYPE_CLASS_TEXT, draftDropoffNote
            )
            dropoffNoteInput = dNoteInput

            attachInlineAutocomplete(
                input = dInput,
                suggestionsBox = dropSuggestionsBox,
                isPickup = false
            ) { picked ->
                suppressAutocomplete = true
                activeDropoffLat = picked.lat
                activeDropoffLng = picked.lng
                draftDropoffAddress = "${picked.title} — ${picked.subtitle}"
                dInput.setText(draftDropoffAddress)
                dInput.setSelection(dInput.text.length)
                suppressAutocomplete = false
                dropSuggestionsBox.visibility = View.GONE
                customerMapView?.recenter(picked.lat, picked.lng, 16)
                recalculateRouteAndFare(user)
            }

            dropCard.addView(AlistoUiKit.labeledField(this, "◆ Drop-Off / Destination", dInput, 4))
            dropCard.addView(dropSuggestionsBox)
            dropCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Drop Pin on Map", true, AlistoUiKit.BG_INPUT, AlistoUiKit.ROSE) {
                    enterDropPinMode(
                        "DROPOFF", "Set Drop-Off Pin", "#F43F5E",
                        activeDropoffLat ?: activeOriginLat, activeDropoffLng ?: activeOriginLng
                    )
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
                addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "+ Pin Extra Stop", true, AlistoUiKit.BG_INPUT, AlistoUiKit.AMBER) {
                    enterDropPinMode("STOP", "Add Stop #${sequentialWaypoints.size + 1}", AlistoUiKit.AMBER, activeOriginLat, activeOriginLng)
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            })

            val wpStatus = TextView(this).apply {
                text = if (sequentialWaypoints.isEmpty()) "No extra intermediate stops added"
                else "Extra Stops (${sequentialWaypoints.size}): " + sequentialWaypoints.joinToString(" → ") {
                    "#${it.getInt("stop_number")} ${it.getString("address")}"
                }
                textSize = 11.5f
                setTextColor(Color.parseColor(if (sequentialWaypoints.isEmpty()) AlistoUiKit.TEXT_DIM else AlistoUiKit.AMBER))
                setPadding(dp(2), 0, dp(2), dp(8))
            }
            waypointsStatusRef = wpStatus
            dropCard.addView(wpStatus)
            dropCard.addView(AlistoUiKit.labeledField(this, "Drop-Off Location Note (Optional)", dNoteInput, 2))
            sheetBody.addView(dropCard)
        }

        // 3. SERVICE SUMMARY & SPECIAL BOOKING NOTE CARD
        val detailsCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
        val titleHint = when {
            selectedJobSubtype == "ERRAND_PURCHASING" -> "Items to buy or errands to run..."
            selectedVerticalKey == "delivery_logistics" -> "Package summary (e.g., documents, food box)..."
            selectedVerticalKey == "ride_hailing" -> "Short trip summary (e.g., work commute)..."
            else -> "Describe work needed (e.g., Aircon cleaning, Plumbing)"
        }
        val titleInput = AlistoUiKit.inputField(this, titleHint, InputType.TYPE_CLASS_TEXT, draftTitle)
        detailsCard.addView(AlistoUiKit.labeledField(this, "Request Summary", titleInput, 10))

        val bookingNoteInput = AlistoUiKit.inputField(
            this, "e.g., Prefer NMAX/PCX motorcycle, bring helmet/tools...", InputType.TYPE_CLASS_TEXT, draftBookingNotes
        )
        detailsCard.addView(AlistoUiKit.labeledField(this, "Special Booking Note (Optional)", bookingNoteInput, 4))
        detailsCard.addView(TextView(this).apply {
            text = "Note: Bookings with special notes skip auto-assign and post directly to Open Bookings so matching contractors can review and accept."
            textSize = 11f
            setTextColor(Color.parseColor(AlistoUiKit.AMBER))
            setPadding(dp(2), 0, dp(2), 0)
        })
        sheetBody.addView(detailsCard)

        // 4. FARE, TIP & ADVANCED OPTIONS
        val quoteBanner = TextView(this).apply {
            text = if (needsDropoff) "Set Pickup & Drop-Off to calculate road route & fare (PHP 0.00 pending)" else "Loading Super Admin call-out rate..."
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
            background = AlistoUiKit.frostedGlassBg(this@CustomerMainActivity, "#CC092D6B", 12, "#CCFACC15", alphaFraction = 0.84f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        activeQuoteBanner = quoteBanner
        sheetBody.addView(quoteBanner)
        val deadheadNotice = TextView(this).apply {
            textSize = 11.5f
            setTextColor(Color.parseColor("#FEF08A"))
            background = AlistoUiKit.frostedGlassBg(this@CustomerMainActivity, "#CC1E3A8A", 10, "#CCFACC15", alphaFraction = 0.85f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        activeDeadheadNoticeView = deadheadNotice
        sheetBody.addView(deadheadNotice)
        val feeInput = AlistoUiKit.inputField(this, "0.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, draftOfferFee)
        activeFeeInput = feeInput
        feeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressFeeWatcher) return
                draftOfferFee = s?.toString().orEmpty()
                userEditedFeeManually = draftOfferFee.isNotBlank()
            }
        })
        val tipInput = AlistoUiKit.inputField(this, "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, draftTip)
        sheetBody.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Offer / Fare (PHP)", feeInput, 4).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
            })
            addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Tip (100% to Pro)", tipInput, 4).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })

        val errandCostInput = AlistoUiKit.inputField(this, "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, draftErrandGoods)
        errandCostInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draftErrandGoods = s?.toString().orEmpty()
                applyFareFromSuperAdminRules()
            }
        })
        val deadheadKmInput = AlistoUiKit.inputField(this, "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, draftDeadheadKm)
        activeDeadheadInput = deadheadKmInput
        deadheadKmInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                draftDeadheadKm = s?.toString().orEmpty()
                applyFareFromSuperAdminRules()
            }
        })
        sheetBody.addView(TextView(this).apply {
            text = if (showAdvancedOptions) "▾ Hide Pabili & Deadhead options" else "▸ Add Pabili goods budget or deadhead return km"
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            setPadding(dp(2), dp(4), dp(2), dp(10))
            setOnClickListener {
                draftTitle = titleInput.text.toString()
                draftPickupAddress = pickupInput.text.toString()
                draftPickupNote = pickupNoteInput.text.toString()
                draftDropoffAddress = dropoffInput?.text?.toString().orEmpty()
                draftDropoffNote = dropoffNoteInput?.text?.toString().orEmpty()
                draftBookingNotes = bookingNoteInput.text.toString()
                draftOfferFee = feeInput.text.toString()
                draftTip = tipInput.text.toString()
                draftErrandGoods = errandCostInput.text.toString()
                draftDeadheadKm = deadheadKmInput.text.toString()
                showAdvancedOptions = !showAdvancedOptions
                if (showAdvancedOptions) sheetExpanded = true
                showAuthenticatedCustomerPortal()
            }
        })

        if (showAdvancedOptions) {
            sheetBody.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Pabili Goods (PHP)", errandCostInput, 4).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
                })
                addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Deadhead km (PHP 2/km)", deadheadKmInput, 4).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        }

        // 4A. BOOKING SCHEDULE SELECTOR (Immediate Dispatch vs. Scheduled Future Slot)
        val schedCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 12)
        val schedTitleView = TextView(this).apply {
            text = if (selectedScheduledStartIso.isNullOrBlank()) {
                "Booking Schedule • Immediate Dispatch (Now)"
            } else {
                "Booking Schedule • Scheduled: $selectedScheduledLabel"
            }
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (selectedScheduledStartIso.isNullOrBlank()) AlistoUiKit.CYAN else AlistoUiKit.GOLD_SOFT))
            setPadding(0, 0, 0, dp(8))
        }
        schedCard.addView(schedTitleView)

        val schedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        lateinit var btnBookNow: TextView
        lateinit var btnScheduleLater: TextView

        fun refreshScheduleButtons() {
            val isSched = !selectedScheduledStartIso.isNullOrBlank()
            schedTitleView.text = if (!isSched) {
                "Booking Schedule • Immediate Dispatch (Now)"
            } else {
                "Booking Schedule • $selectedScheduledLabel"
            }
            schedTitleView.setTextColor(Color.parseColor(if (!isSched) AlistoUiKit.CYAN else AlistoUiKit.GOLD_SOFT))
            btnScheduleLater.text = if (isSched) "Change Time" else "Schedule Later"

            listOf(btnBookNow to !isSched, btnScheduleLater to isSched).forEach { (btn, active) ->
                btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                btn.setTextColor(Color.parseColor(if (active) AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_PRIMARY))
                btn.background = AlistoUiKit.frostedGlassBg(
                    this@CustomerMainActivity,
                    fillHex = if (active) AlistoUiKit.GOLD_ACCENT else "#A6164CA8",
                    radiusDp = 18,
                    strokeHex = if (active) "#E6FEF08A" else AlistoUiKit.BORDER,
                    alphaFraction = if (active) 0.90f else 0.72f
                )
            }
        }

        btnBookNow = TextView(this).apply {
            text = "Book Now"
            gravity = Gravity.CENTER
            textSize = 12.5f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
            setOnClickListener {
                selectedScheduledStartIso = null
                selectedScheduledLabel = ""
                refreshScheduleButtons()
            }
        }
        btnScheduleLater = TextView(this).apply {
            text = "Schedule Later"
            gravity = Gravity.CENTER
            textSize = 12.5f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val nowCal = java.util.Calendar.getInstance()
                val defaultCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, 30) }
                val dpDialog = android.app.DatePickerDialog(
                    this@CustomerMainActivity,
                    { _, year, month, dayOfMonth ->
                        android.app.TimePickerDialog(
                            this@CustomerMainActivity,
                            { _, hourOfDay, minute ->
                                val picked = java.util.Calendar.getInstance().apply {
                                    set(year, month, dayOfMonth, hourOfDay, minute, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                val minAllowedMs = System.currentTimeMillis() + (15L * 60L * 1000L)
                                if (picked.timeInMillis < minAllowedMs) {
                                    Toast.makeText(
                                        this@CustomerMainActivity,
                                        "Please pick a schedule at least 15 minutes from now.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@TimePickerDialog
                                }
                                val utcFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                                }
                                val displayFmt = java.text.SimpleDateFormat("MMM d, yyyy • h:mm a", java.util.Locale.US)
                                selectedScheduledStartIso = utcFmt.format(picked.time)
                                selectedScheduledLabel = displayFmt.format(picked.time)
                                refreshScheduleButtons()
                            },
                            defaultCal.get(java.util.Calendar.HOUR_OF_DAY),
                            defaultCal.get(java.util.Calendar.MINUTE),
                            false
                        ).show()
                    },
                    defaultCal.get(java.util.Calendar.YEAR),
                    defaultCal.get(java.util.Calendar.MONTH),
                    defaultCal.get(java.util.Calendar.DAY_OF_MONTH)
                )
                dpDialog.datePicker.minDate = nowCal.timeInMillis - 1000L
                dpDialog.datePicker.maxDate = nowCal.timeInMillis + (30L * 24L * 60L * 60L * 1000L)
                dpDialog.show()
            }
        }
        schedRow.addView(btnBookNow)
        schedRow.addView(btnScheduleLater)
        schedCard.addView(schedRow)
        refreshScheduleButtons()
        sheetBody.addView(schedCard)

                // 4B. PAYMENT METHOD SELECTOR (Optional Wallet Credits for Good Standing; Mandatory for Delinquent)
        if (user.isDelinquent) selectedPaymentMethod = "WALLET_CREDITS"
        val payCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 12)
        payCard.addView(TextView(this).apply {
            text = if (user.isDelinquent) {
                "Payment Method • Mandatory Prepaid Wallet Credits (Delinquent Standing)"
            } else {
                "Payment Method • Balance: PHP ${"%.2f".format(user.creditBalance)}"
            }
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (user.isDelinquent) AlistoUiKit.GOLD_SOFT else AlistoUiKit.CYAN))
            setPadding(0, 0, 0, dp(8))
        })
        val payRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val payOptions = listOf("CASH" to "Cash to Pro", "WALLET_CREDITS" to "Wallet Credits")
        val payBtns = mutableListOf<Pair<String, TextView>>()
        fun refreshPayButtons() {
            payBtns.forEach { (code, btn) ->
                val active = selectedPaymentMethod == code
                val lockedOut = user.isDelinquent && code == "CASH"
                btn.alpha = if (lockedOut) 0.42f else 1.0f
                btn.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                btn.setTextColor(Color.parseColor(if (active) AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_PRIMARY))
                btn.background = AlistoUiKit.frostedGlassBg(
                    this@CustomerMainActivity,
                    fillHex = if (active) AlistoUiKit.GOLD_ACCENT else "#A6164CA8",
                    radiusDp = 18,
                    strokeHex = if (active) "#E6FEF08A" else AlistoUiKit.BORDER,
                    alphaFraction = if (active) 0.90f else 0.72f
                )
            }
        }
        payOptions.forEachIndexed { idx, (code, label) ->
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12.5f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx == 0) rightMargin = dp(8)
                }
                setOnClickListener {
                    if (user.isDelinquent && code == "CASH") {
                        Toast.makeText(
                            this@CustomerMainActivity,
                            "Delinquent accounts must pay with prepaid Wallet Credits. Top up in the Wallet tab.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    selectedPaymentMethod = code
                    refreshPayButtons()
                }
            }
            payBtns.add(code to btn)
            payRow.addView(btn)
        }
        refreshPayButtons()
        payCard.addView(payRow)
        if (user.isDelinquent || user.creditBalance < 200.0) {
            payCard.addView(TextView(this).apply {
                text = "Need credits? Tap here to open Wallet & Top Up →"
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
                setPadding(dp(2), dp(8), dp(2), 0)
                setOnClickListener {
                    activeTab = "WALLET"
                    showAuthenticatedCustomerPortal()
                }
            })
        }
        sheetBody.addView(payCard)

        // 5. PRIMARY SUBMIT CTA
        var submitBtnRef: View? = null
        sheetBody.addView(
            AlistoUiKit.pillButton(this, "Confirm & Broadcast Request", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 50, textSizeSp = 15f) {
                if (isSubmittingBooking) {
                    Toast.makeText(this@CustomerMainActivity, "Sending your booking request — please wait...", Toast.LENGTH_SHORT).show()
                    return@pillButton
                }
                val title = titleInput.text.toString().ifBlank { selectedServiceLabel }
                val pAddr = pickupInput.text.toString().ifBlank { user.addressText }
                val pNote = pickupNoteInput.text.toString().trim()
                val dAddr = dropoffInput?.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
                    ?: if (sequentialWaypoints.isNotEmpty()) sequentialWaypoints.last().optString("address", pAddr) else pAddr
                val dNote = dropoffNoteInput?.text?.toString()?.trim().orEmpty()
                val bNotes = bookingNoteInput.text.toString().trim()

                if (needsDropoff && (activeDropoffLat == null || activeDropoffLng == null)) {
                    Toast.makeText(this@CustomerMainActivity, "Please select or pin a Drop-Off location first.", Toast.LENGTH_LONG).show()
                    return@pillButton
                }
                val fee = feeInput.text.toString().toDoubleOrNull() ?: activeCalculatedFare ?: 0.0
                if (fee <= 0.0) {
                    Toast.makeText(this@CustomerMainActivity, "Waiting for valid route & Super Admin fare calculation.", Toast.LENGTH_LONG).show()
                    return@pillButton
                }
                val errandCost = errandCostInput.text.toString().toDoubleOrNull() ?: 0.0
                val deadheadKm = deadheadKmInput.text.toString().toDoubleOrNull() ?: 0.0
                val tip = tipInput.text.toString().toDoubleOrNull() ?: 0.0
                val wpArr = JSONArray().apply { sequentialWaypoints.forEach { put(it) } }

                val launchBroadcast: (Boolean) -> Unit = { allowMulti ->
                    showBroadcastChargesConfirmation(title, fee, tip, selectedPaymentMethod) {
                        if (isSubmittingBooking) {
                            Toast.makeText(this@CustomerMainActivity, "Booking request already in progress...", Toast.LENGTH_SHORT).show()
                            return@showBroadcastChargesConfirmation
                        }
                        // Synchronous UI lock + stable UUIDv4 idempotency key across retries for the same trip
                        isSubmittingBooking = true
                        submitBtnRef?.isEnabled = false
                        submitBtnRef?.alpha = 0.6f
                        (submitBtnRef as? TextView)?.text = "Placing Booking..."

                        val reqSig = listOf(
                            selectedVerticalKey, selectedJobSubtype,
                            activeOriginLat, activeOriginLng,
                            activeDropoffLat, activeDropoffLng,
                            fee, tip, selectedPaymentMethod, selectedScheduledStartIso
                        ).joinToString("|")
                        if (pendingBookingIdempotencyKey.isNullOrBlank() || pendingBookingSignature != reqSig) {
                            pendingBookingIdempotencyKey = java.util.UUID.randomUUID().toString()
                            pendingBookingSignature = reqSig
                        }
                        val idemKey = pendingBookingIdempotencyKey!!

                        uiScope.launch {
                            try {
                                val res = AlistoKernelClient.createBookingV2Rpc(
                                    verticalKey = selectedVerticalKey,
                                    jobSubtype = selectedJobSubtype,
                                    municipality = user.municipality,
                                    barangayZone = user.barangayZone,
                                    skillCategory = selectedSkillCategory,
                                    serviceTitle = title,
                                    pickupAddress = pAddr,
                                    dropoffAddress = dAddr,
                                    waypointsJson = wpArr,
                                    quotedFee = fee,
                                    tipAmount = tip,
                                    errandItemCostPhp = errandCost,
                                    deadheadReturnKm = deadheadKm,
                                    pickupLat = activeOriginLat,
                                    pickupLng = activeOriginLng,
                                    dropoffLat = activeDropoffLat ?: activeOriginLat,
                                    dropoffLng = activeDropoffLng ?: activeOriginLng,
                                    pickupNote = pNote,
                                    dropoffNote = dNote,
                                    bookingNotes = bNotes,
                                    vehicleType = if (needsDropoff) selectedVehicleType else "",
                                    paymentMethod = if (user.isDelinquent) "WALLET_CREDITS" else selectedPaymentMethod,
                                    scheduledStartAtIso = selectedScheduledStartIso,
                                    idempotencyKey = idemKey,
                                    allowMultiBooking = allowMulti
                                )
                                res.onSuccess { respJson ->
                                    pendingBookingIdempotencyKey = null
                                    pendingBookingSignature = ""
                                    sequentialWaypoints.clear()
                                    draftTitle = ""; draftPickupNote = ""; draftDropoffAddress = ""; draftDropoffNote = ""; draftBookingNotes = ""; draftTip = ""; draftDeadheadKm = ""; lastRemoteNoticeKey = ""
                                    activeDropoffLat = null; activeDropoffLng = null
                                    val scheduledDisplay = selectedScheduledLabel
                                    selectedScheduledStartIso = null
                                    selectedScheduledLabel = ""
                                    val autoMatched = respJson.optBoolean("auto_assigned", false)
                                    val routeMsg = when {
                                        scheduledDisplay.isNotBlank() -> "Scheduled booking posted for $scheduledDisplay — pros can now reserve it on the board."
                                        autoMatched -> "Yey! We found a pro for you! Your pro is preparing to head over."
                                        bNotes.isNotBlank() -> "Posted to Open Bookings — matching pros are reviewing your special note."
                                        else -> "We are finding the nearest pro for you..."
                                    }
                                    Toast.makeText(this@CustomerMainActivity, routeMsg, Toast.LENGTH_LONG).show()
                                    AlistoKernelClient.bookings.forEach { bk -> lastBookingStatuses[bk.id] = bk.status }
                                    activeTab = "ACTIVITY"
                                    bookingStep = "LAUNCHER"
                                    showAuthenticatedCustomerPortal()
                                }.onFailure { err ->
                                    AlertDialog.Builder(this@CustomerMainActivity)
                                        .setTitle("Booking Notice").setMessage(err.message).setPositiveButton("OK", null).show()
                                }
                            } finally {
                                isSubmittingBooking = false
                                submitBtnRef?.isEnabled = true
                                submitBtnRef?.alpha = 1.0f
                                (submitBtnRef as? TextView)?.text = "Confirm & Broadcast Request"
                            }
                        }
                    }
                }

                // Require explicit confirmation before creating a 2nd+ active booking request.
                val existingOpenBookings = AlistoKernelClient.bookings.filter {
                    it.clientId == user.id &&
                        it.status !in listOf("COMPLETED", "COMPLETED_WITH_DISPUTE", "CANCELLED", "EXPIRED")
                }
                if (existingOpenBookings.isNotEmpty()) {
                    val activeSummary = existingOpenBookings.take(2).joinToString("\n") {
                        "• ${it.serviceTitle} (${it.status.replace('_', ' ')})"
                    }
                    AlertDialog.Builder(this@CustomerMainActivity)
                        .setTitle("You Already Have an Active Booking")
                        .setMessage(
                            "You currently have ${existingOpenBookings.size} ongoing booking request(s):\n$activeSummary\n\n" +
                                "Do you want to proceed and submit another booking request?"
                        )
                        .setPositiveButton("Confirm New Booking") { _, _ -> launchBroadcast(true) }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    launchBroadcast(false)
                }
            }.also { submitBtnRef = it }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        )

        sheetScroll.addView(sheetBody)
        sheetWrap.addView(sheetScroll)
        root.addView(sheetWrap)

        // Load map centered on current GPS or chosen origin
        val mapLat = if (originPinnedManually) activeOriginLat else (AlistoLocationTracker.lastLat ?: activeOriginLat)
        val mapLng = if (originPinnedManually) activeOriginLng else (AlistoLocationTracker.lastLng ?: activeOriginLng)

        customerMapView?.loadInteractiveMap(mapLat, mapLng, enableTapToPin = true) { lat, lng ->
            // Direct tap pins Pickup when not in explicit crosshair mode
            if (dropPinTarget == null) {
                uiScope.launch {
                    val rev = withContext(Dispatchers.IO) {
                        AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, lat, lng)
                    }
                    suppressAutocomplete = true
                    activeOriginLat = lat
                    activeOriginLng = lng
                    originPinnedManually = true
                    draftPickupAddress = rev
                    pickupInput.setText(rev)
                    suppressAutocomplete = false
                    recalculateRouteAndFare(user)
                    Toast.makeText(this@CustomerMainActivity, "Pickup pinned: $rev", Toast.LENGTH_SHORT).show()
                }
            }
        }
        recalculateRouteAndFare(user)
        AlistoLocationTracker.lastLat?.let { lat ->
            AlistoLocationTracker.lastLng?.let { lng ->
                customerMapView?.updateUserLocation(lat, lng, AlistoLocationTracker.lastAccuracyM, flyTo = false)
            }
        }
        return root
    }

    private fun attachInlineAutocomplete(
        input: EditText,
        suggestionsBox: LinearLayout,
        isPickup: Boolean,
        onSelect: (LandmarkResult) -> Unit
    ) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutocomplete) return
                val q = s?.toString()?.trim().orEmpty()
                if (isPickup) draftPickupAddress = q else {
                    draftDropoffAddress = q
                    if (q.isEmpty()) {
                        activeDropoffLat = null
                        activeDropoffLng = null
                        userEditedFeeManually = false
                        AlistoKernelClient.currentSessionUser?.let { recalculateRouteAndFare(it) }
                    }
                }

                if (isPickup) pickupSearchJob?.cancel() else dropoffSearchJob?.cancel()
                if (q.length < 3) {
                    suggestionsBox.removeAllViews()
                    suggestionsBox.visibility = View.GONE
                    return
                }

                val job = uiScope.launch {
                    delay(450L)
                    val biasLat = AlistoLocationTracker.lastLat ?: activeOriginLat
                    val biasLng = AlistoLocationTracker.lastLng ?: activeOriginLng
                    val hits = AlistoLocationTracker.searchLandmarks(this@CustomerMainActivity, q, biasLat, biasLng)
                    suggestionsBox.removeAllViews()
                    if (hits.isEmpty()) {
                        suggestionsBox.visibility = View.GONE
                        return@launch
                    }
                    suggestionsBox.visibility = View.VISIBLE
                    hits.take(5).forEach { hit ->
                        val row = LinearLayout(this@CustomerMainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            background = AlistoUiKit.roundedBg(this@CustomerMainActivity, AlistoUiKit.BG_INPUT, 10, AlistoUiKit.BORDER_LIGHT)
                            setPadding(dp(12), dp(9), dp(12), dp(9))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(5) }
                            setOnClickListener { onSelect(hit) }

                            addView(TextView(this@CustomerMainActivity).apply {
                                text = hit.title; textSize = 13f; setTypeface(null, Typeface.BOLD)
                                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); maxLines = 1
                            })
                            addView(TextView(this@CustomerMainActivity).apply {
                                text = hit.subtitle; textSize = 11f
                                setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); maxLines = 1
                            })
                        }
                        suggestionsBox.addView(row)
                    }
                }
                if (isPickup) pickupSearchJob = job else dropoffSearchJob = job
            }
        })
    }

    private fun refreshCustomerMapLayers(user: AuthenticatedUser) {
        customerMapView?.updateMapLayers(
            originLat = activeOriginLat,
            originLng = activeOriginLng,
            originAddress = draftPickupAddress.ifBlank { user.addressText },
            dropoffLat = activeDropoffLat,
            dropoffLng = activeDropoffLng,
            dropoffAddress = draftDropoffAddress,
            waypoints = sequentialWaypoints,
            routePoints = activeRoutePoints,
            bookings = AlistoKernelClient.bookings.filter { user.role == "super_admin" || it.clientId == user.id },
            telemetryPins = AlistoKernelClient.liveTelemetryPins
        )
    }

    private fun isPointInClark(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        return lat in 15.1695..15.2250 && lng in 120.5100..120.5775
    }

    private fun isClarkAddressText(addr: String): Boolean {
        val hay = addr.lowercase()
        if (hay.contains("clarkview")) return false
        val tokens = listOf(
            "clark freeport", "clarkfield", "clark global", "clark international",
            "clark, pampanga", "clark pampanga", "m.a. roxas", "manuel a. roxas",
            "parade grounds", "air force city"
        )
        return tokens.any { hay.contains(it) }
    }

    private fun isClarkRestrictedTrip(): Boolean {
        if (selectedVerticalKey == "skills_marketplace") return false
        return isPointInClark(activeOriginLat, activeOriginLng) ||
            isPointInClark(activeDropoffLat, activeDropoffLng) ||
            isClarkAddressText(draftPickupAddress) ||
            isClarkAddressText(draftDropoffAddress)
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return r * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
    }

    // Returns return-trip distance (km) from a remote drop-off back to the edge of the nearest High-Demand Zone
    private fun computeRemoteDeadheadKm(destLat: Double, destLng: Double): Double {
        // Core High-Demand Zones: Angeles City, Clark/Dau-Mabalacat, Clark Freeport, San Fernando
        val hubs = listOf(
            Triple(15.1450, 120.5887, 5.0),
            Triple(15.1780, 120.5850, 4.5),
            Triple(15.1860, 120.5480, 4.0),
            Triple(15.0430, 120.6840, 4.5)
        )
        val excessKm = hubs.minOf { (hLat, hLng, radiusKm) ->
            (haversineKm(destLat, destLng, hLat, hLng) - radiusKm).coerceAtLeast(0.0)
        }
        return if (excessKm >= 1.5) Math.round(excessKm * 10.0) / 10.0 else 0.0
    }

    private fun applyFareFromSuperAdminRules() {
        refreshVehicleUiCallback?.invoke()
        val pc = AlistoKernelClient.findPricingClass(selectedVerticalKey, if (selectedVerticalKey == "skills_marketplace") "" else selectedVehicleType)
        if (selectedVerticalKey == "skills_marketplace") {
            activeDeadheadNoticeView?.visibility = View.GONE
            val fare = pc?.computeEffectiveFare(0.0, 0.0)
            activeCalculatedFare = fare
            if (fare != null && fare > 0.0) {
                activeQuoteBanner?.text = "${pc.className} • Call-Out Rate: PHP ${"%.2f".format(fare)}"
                if (!userEditedFeeManually) {
                    suppressFeeWatcher = true
                    draftOfferFee = "%.2f".format(fare)
                    activeFeeInput?.setText(draftOfferFee)
                    suppressFeeWatcher = false
                }
            } else {
                activeQuoteBanner?.text = "Enter your preferred service offer (Super Admin rate pending)"
            }
            return
        }
        if (activeDropoffLat == null || activeDropoffLng == null || activeRouteDistanceKm <= 0.0) {
            activeCalculatedFare = null
            activeDeadheadNoticeView?.visibility = View.GONE
            activeQuoteBanner?.text = "Set Pickup & Drop-Off to calculate road route & fare (PHP 0.00 pending)"
            if (!userEditedFeeManually) {
                suppressFeeWatcher = true
                draftOfferFee = ""
                activeFeeInput?.setText("")
                suppressFeeWatcher = false
            }
            return
        }
        val dhKm = draftDeadheadKm.toDoubleOrNull() ?: 0.0
        val dhRate = pc?.deadheadPerKmRate ?: 2.0
        val dhFee = Math.round((dhKm * dhRate) * 100.0) / 100.0
        val pabiliPhp = draftErrandGoods.toDoubleOrNull() ?: 0.0
        val fare = pc?.computeEffectiveFare(activeRouteDistanceKm, dhKm, sequentialWaypoints.size, pabiliPhp)
        activeCalculatedFare = fare
        val stopsTag = if (sequentialWaypoints.isNotEmpty()) " • +${sequentialWaypoints.size} stop(s)" else ""
        val cashoutTag = if (pabiliPhp > 0.0 && pabiliPhp < 500.0) " • +cash-out" else ""
        val dhTag = if (dhFee > 0.0) " • +PHP ${"%.2f".format(dhFee)} return fuel" else ""

        if (dhFee > 0.0) {
            activeDeadheadNoticeView?.text = "Remote Return Surcharge (+PHP ${"%.2f".format(dhFee)} • ${"%.1f".format(dhKm)} km): Drop-off is outside our high-demand areas, so PHP ${"%.2f".format(dhRate)}/km is included to help cover your rider's fuel back to the nearest active zone."
            activeDeadheadNoticeView?.visibility = View.VISIBLE
        } else {
            activeDeadheadNoticeView?.visibility = View.GONE
        }

        if (fare != null && fare > 0.0) {
            activeQuoteBanner?.text = "$selectedVehicleType • ${"%.2f".format(activeRouteDistanceKm)} km (~${activeRouteDurationMin.toInt()} min)$stopsTag$cashoutTag$dhTag • Fare: PHP ${"%.2f".format(fare)}"
            if (!userEditedFeeManually) {
                suppressFeeWatcher = true
                draftOfferFee = "%.2f".format(fare)
                activeFeeInput?.setText(draftOfferFee)
                suppressFeeWatcher = false
            }
        } else {
            activeQuoteBanner?.text = "Local Road: ${"%.2f".format(activeRouteDistanceKm)} km (~${activeRouteDurationMin.toInt()} min) • Pricing class unavailable"
        }
    }

    private fun recalculateRouteAndFare(user: AuthenticatedUser) {
        routeCalcJob?.cancel()
        refreshVehicleUiCallback?.invoke()
        if (selectedVerticalKey == "skills_marketplace") {
            activeRoutePoints = emptyList()
            activeRouteDistanceKm = 0.0
            activeRouteDurationMin = 0.0
            draftDeadheadKm = ""
            activeDeadheadInput?.setText("")
            applyFareFromSuperAdminRules()
            refreshCustomerMapLayers(user)
            return
        }
        val dLat = activeDropoffLat
        val dLng = activeDropoffLng
        if (dLat == null || dLng == null) {
            activeRoutePoints = emptyList()
            activeRouteDistanceKm = 0.0
            activeRouteDurationMin = 0.0
            draftDeadheadKm = ""
            activeDeadheadInput?.setText("")
            lastRemoteNoticeKey = ""
            applyFareFromSuperAdminRules()
            refreshCustomerMapLayers(user)
            return
        }

        // Auto-detect out-of-high-demand destination and apply PHP 2/km return surcharge
        val autoDhKm = AlistoKernelClient.computeDeadheadReturnKmFromZones(dLat, dLng) ?: computeRemoteDeadheadKm(dLat, dLng)
        if (autoDhKm > 0.0) {
            val formattedDh = "%.1f".format(autoDhKm)
            draftDeadheadKm = formattedDh
            if (activeDeadheadInput?.text?.toString() != formattedDh) {
                activeDeadheadInput?.setText(formattedDh)
            }
            val destKey = "${"%.3f".format(dLat)},${"%.3f".format(dLng)}"
            if (lastRemoteNoticeKey != destKey && !isFinishing && !isDestroyed) {
                lastRemoteNoticeKey = destKey
                val dhFee = Math.round((autoDhKm * 2.0) * 100.0) / 100.0
                AlertDialog.Builder(this)
                    .setTitle("Remote Area Return Surcharge")
                    .setMessage(
                        "Your drop-off is outside our high-demand service zones, where riders have a lower chance of finding a return booking.\n\n" +
                        "A return surcharge of PHP 2.00/km (${"%.1f".format(autoDhKm)} km = +PHP ${"%.2f".format(dhFee)}) has been added to help offset your rider's fuel back to the nearest active zone."
                    )
                    .setPositiveButton("Got It", null)
                    .show()
            }
        } else {
            if (draftDeadheadKm.isNotBlank()) {
                draftDeadheadKm = ""
                activeDeadheadInput?.setText("")
            }
            lastRemoteNoticeKey = ""
        }

        activeQuoteBanner?.text = "Calculating local-road route & fare..."
        val wpsSnapshot = sequentialWaypoints.toList()
        routeCalcJob = uiScope.launch {
            val res = AlistoRoutingEngine.computeLocalRoute(
                originLat = activeOriginLat,
                originLng = activeOriginLng,
                waypoints = wpsSnapshot,
                destLat = dLat,
                destLng = dLng
            )
            activeRoutePoints = res.points
            activeRouteDistanceKm = res.distanceKm
            activeRouteDurationMin = res.durationMin
            applyFareFromSuperAdminRules()
            refreshCustomerMapLayers(user)
        }
    }

    private fun startLiveGpsOnMap(forceCenter: Boolean, snapOriginToGps: Boolean) {
        if (!AlistoLocationTracker.hasPermission(this)) return
        var pendingForceCenter = forceCenter
        var pendingSnapOrigin = snapOriginToGps
        AlistoLocationTracker.startLiveUpdates(this) { lat, lng, acc ->
            val inPinMode = dropPinTarget != null
            val shouldFly = !inPinMode && (pendingForceCenter || !hasAutoCenteredGps)
            customerMapView?.updateUserLocation(lat, lng, acc, flyTo = shouldFly)
            if (shouldFly) {
                hasAutoCenteredGps = true
                pendingForceCenter = false
            }
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastCustomerLocSyncMs >= 10_000L) {
                lastCustomerLocSyncMs = nowMs
                uiScope.launch { AlistoKernelClient.updateLiveLocationRpc(lat, lng) }
            }

            val doSnap = !inPinMode && pendingSnapOrigin
            if (!inPinMode && (doSnap || !originPinnedManually)) {
                activeOriginLat = lat
                activeOriginLng = lng
                if (doSnap) {
                    pendingSnapOrigin = false
                    uiScope.launch {
                        val rev = withContext(Dispatchers.IO) {
                            AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, lat, lng)
                        }
                        suppressAutocomplete = true
                        draftPickupAddress = rev
                        activePickupField?.setText(rev)
                        suppressAutocomplete = false
                        AlistoKernelClient.currentSessionUser?.let { refreshCustomerMapLayers(it) }
                    }
                } else {
                    AlistoKernelClient.currentSessionUser?.let { refreshCustomerMapLayers(it) }
                }
            }
        }
    }

    private fun snapPickupToCurrentGps() {
        if (!AlistoLocationTracker.hasPermission(this)) {
            AlistoLocationTracker.requestPermission(this)
            return
        }
        val fix = AlistoLocationTracker.getBestLastKnown(this)
        val lat = AlistoLocationTracker.lastLat ?: fix?.latitude
        val lng = AlistoLocationTracker.lastLng ?: fix?.longitude
        if (lat != null && lng != null) {
            val acc = AlistoLocationTracker.lastAccuracyM
            activeOriginLat = lat
            activeOriginLng = lng
            originPinnedManually = true
            uiScope.launch {
                val rev = withContext(Dispatchers.IO) {
                    AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, lat, lng)
                }
                suppressAutocomplete = true
                draftPickupAddress = rev
                activePickupField?.setText(rev)
                suppressAutocomplete = false
                customerMapView?.updateUserLocation(lat, lng, acc, flyTo = true)
                AlistoKernelClient.currentSessionUser?.let { recalculateRouteAndFare(it) }
                Toast.makeText(this@CustomerMainActivity, "Pickup set to GPS (±${acc.toInt()}m)", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Acquiring live GPS fix...", Toast.LENGTH_SHORT).show()
            startLiveGpsOnMap(forceCenter = true, snapOriginToGps = true)
        }
    }

    private fun buildCustomerActivityTab(user: AuthenticatedUser): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(TextView(this@CustomerMainActivity).apply {
                text = "Your Activity"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Refresh", true, AlistoUiKit.BG_CARD, AlistoUiKit.CYAN) {
                uiScope.launch {
                    AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "customer")
                    showAuthenticatedCustomerPortal()
                }
            })
        })

        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Active", activityFilter == "ACTIVE", AlistoUiKit.CYAN) {
                activityFilter = "ACTIVE"; showAuthenticatedCustomerPortal()
            }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) } })
            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "All History", activityFilter == "ALL", AlistoUiKit.CYAN) {
                activityFilter = "ALL"; showAuthenticatedCustomerPortal()
            })
        })

        val mine = AlistoKernelClient.bookings.filter { user.role == "super_admin" || it.clientId == user.id }
        val shown = if (activityFilter == "ACTIVE") mine.filter { it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED") } else mine

        if (shown.isEmpty()) {
            val emptyCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 22)
            emptyCard.addView(TextView(this).apply {
                text = if (activityFilter == "ACTIVE") "No active bookings right now." else "No bookings yet."
                textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            })
            emptyCard.addView(TextView(this).apply {
                text = "Tap Home to choose a service and pick your location."
                textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(6), 0, dp(14))
            })
            emptyCard.addView(AlistoUiKit.pillButton(this, "Choose a Service", AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP, heightDp = 44) {
                activeTab = "BOOK"; bookingStep = "LAUNCHER"; showAuthenticatedCustomerPortal()
            })
            container.addView(emptyCard)
        } else {
            shown.forEach { b -> container.addView(buildCustomerBookingCard(b)) }
        }

        scroll.addView(container)
        return scroll
    }

    private fun openCustomerP2pTransferDialog(user: AuthenticatedUser) {
        val recipInput = AlistoUiKit.inputField(
            this, "@username, phone, or email",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        val amtInput = AlistoUiKit.inputField(
            this, "e.g. 100.00",
            android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        )
        val noteInput = AlistoUiKit.inputField(
            this, "Optional note (e.g. Fare split)",
            android.text.InputType.TYPE_CLASS_TEXT
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
            addView(TextView(this@CustomerMainActivity).apply {
                text = "Available to send: PHP ${"%.2f".format(user.creditBalance)} (Escrow credits cannot be transferred)."
                textSize = 12f
                setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
                setPadding(0, 0, 0, dp(10))
            })
            addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Recipient (@username / Phone / Email)", recipInput, 8))
            addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Amount to Send (PHP)", amtInput, 8))
            addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Memo / Note (Optional)", noteInput, 4))
        }

        AlertDialog.Builder(this)
            .setTitle("Send Credits (P2P Transfer)")
            .setView(box)
            .setPositiveButton("Verify Recipient") { _, _ ->
                if (isSubmittingP2pTransfer) {
                    Toast.makeText(this, "Transfer already in progress — please wait...", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val recipRaw = recipInput.text.toString().trim()
                val amount = amtInput.text.toString().toDoubleOrNull() ?: 0.0
                val note = noteInput.text.toString().trim()
                if (recipRaw.isBlank()) {
                    Toast.makeText(this, "Enter a recipient @username, phone, or email.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (amount < 1.0 || amount > 50000.0) {
                    Toast.makeText(this, "Amount must be between PHP 1.00 and PHP 50,000.00.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (amount > user.creditBalance) {
                    Toast.makeText(this, "Insufficient available balance (PHP ${"%.2f".format(user.creditBalance)}).", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                uiScope.launch {
                    val lookupRes = AlistoKernelClient.lookupP2pRecipientRpc(recipRaw)
                    lookupRes.onSuccess { resJson ->
                        val r = resJson.optJSONObject("recipient") ?: JSONObject()
                        val rName = r.optString("full_name", "Alisto User")
                        val rHandle = r.optString("username", "").let { if (it.isNotBlank()) "@$it" else recipRaw }
                        val rRole = r.optString("role", "user").uppercase()
                        val memoLine = if (note.isNotBlank()) "\nNote: $note" else ""

                        AlertDialog.Builder(this@CustomerMainActivity)
                            .setTitle("Confirm Credit Transfer")
                            .setMessage(
                                "Recipient: $rName ($rHandle)\n" +
                                    "Account Type: $rRole\n" +
                                    "Amount: PHP ${"%.2f".format(amount)}$memoLine\n\n" +
                                    "This transfer is immediate and recorded on both users' credit ledgers."
                            )
                            .setPositiveButton("Confirm & Send") { _, _ ->
                                if (isSubmittingP2pTransfer) return@setPositiveButton
                                isSubmittingP2pTransfer = true
                                uiScope.launch {
                                    try {
                                        val txRes = AlistoKernelClient.transferP2pCreditsRpc(recipRaw, amount, note)
                                        txRes.onSuccess { txJson ->
                                            val ref = txJson.optString("transfer_ref", "P2P")
                                            val newBal = txJson.optDouble("sender_balance_after", user.creditBalance - amount)
                                            AlertDialog.Builder(this@CustomerMainActivity)
                                                .setTitle("Transfer Completed")
                                                .setMessage(
                                                    "Sent PHP ${"%.2f".format(amount)} to $rName ($rHandle).\n" +
                                                        "Ref: $ref\n" +
                                                        "New Balance: PHP ${"%.2f".format(newBal)}"
                                                )
                                                .setPositiveButton("Done", null)
                                                .show()
                                            showAuthenticatedCustomerPortal()
                                        }.onFailure { err ->
                                            AlertDialog.Builder(this@CustomerMainActivity)
                                                .setTitle("Transfer Failed")
                                                .setMessage(err.message ?: "Unable to complete transfer.")
                                                .setPositiveButton("OK", null)
                                                .show()
                                        }
                                    } finally {
                                        isSubmittingP2pTransfer = false
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@CustomerMainActivity)
                            .setTitle("Recipient Not Found")
                            .setMessage(err.message ?: "Unable to verify recipient.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildCustomerWalletTab(user: AuthenticatedUser): View {
        val payInfo = AlistoKernelClient.currentPaymentModeInfo
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(TextView(this).apply {
            text = "Customer Wallet"; textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(14))
        })

        val heroCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.GOLD_ACCENT, padDp = 20)
        heroCard.addView(TextView(this).apply {
            text = "AVAILABLE PREPAID CREDITS"; textSize = 11f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
        })
        heroCard.addView(TextView(this).apply {
            text = "PHP ${"%.2f".format(user.creditBalance)}"
            textSize = 30f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            setPadding(0, dp(4), 0, dp(10))
        })
        heroCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "Escrow: PHP ${"%.2f".format(user.heldCredits)}", AlistoUiKit.BG_DEEP, AlistoUiKit.GOLD_SOFT).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
            addView(AlistoUiKit.badgePill(
                this@CustomerMainActivity,
                if (user.isDelinquent) "PREPAID REQUIRED" else "GOOD STANDING",
                if (user.isDelinquent) "#7F1D1D" else "#064E3B",
                if (user.isDelinquent) "#FCA5A5" else "#34D399"
            ))
        })
        if (user.commissionArrearsPhp > 0.0) {
            heroCard.addView(TextView(this).apply {
                text = "Unpaid No-Show Arrears: PHP ${"%.2f".format(user.commissionArrearsPhp)} (auto-settled upon next top-up)"
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#FCA5A5")); setPadding(0, dp(10), 0, 0)
            })
        } else {
            heroCard.addView(TextView(this).apply {
                text = if (user.isDelinquent) {
                    "Delinquent standing active: top up credits to pay contractors across Skills, Delivery, Errands & Local Rides."
                } else {
                    "Optional cashless credits: pay contractors across Skills, Delivery, Errands & Local Rides."
                }
                textSize = 12f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(10), 0, 0)
            })
        }
        container.addView(heroCard)

        val modeSuffix = if (payInfo.paymentMode == "MANUAL") "(Manual Ref)" else "(PayMongo QR Ph)"
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(AlistoUiKit.pillButton(this@CustomerMainActivity, "+PHP 200", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 46) {
                initiateCustomerPaymentFlow(200.0)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })
            addView(AlistoUiKit.pillButton(this@CustomerMainActivity, "+PHP 500", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 46) {
                initiateCustomerPaymentFlow(500.0)
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        })

        container.addView(AlistoUiKit.pillButton(
            this, "Custom Top-Up Amount $modeSuffix", AlistoUiKit.BG_CARD, AlistoUiKit.GOLD_SOFT, AlistoUiKit.GOLD_ACCENT, heightDp = 46
        ) {
            val amtInput = AlistoUiKit.inputField(this, "e.g. 300", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(6))
                addView(AlistoUiKit.labeledField(this@CustomerMainActivity, "Top-Up Amount (PHP)", amtInput, 4))
            }
            AlertDialog.Builder(this)
                .setTitle("Custom Wallet Top-Up").setView(box)
                .setPositiveButton("Continue") { _, _ ->
                    val amt = amtInput.text.toString().toDoubleOrNull() ?: 0.0
                    if (amt < 20.0) Toast.makeText(this, "Minimum top-up is PHP 20.00", Toast.LENGTH_LONG).show()
                    else initiateCustomerPaymentFlow(amt)
                }.setNegativeButton("Cancel", null).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })

        container.addView(AlistoUiKit.pillButton(
            this, "Send Credits (P2P Transfer)", "#0E3A5D", AlistoUiKit.TEXT_PRIMARY, AlistoUiKit.CYAN, heightDp = 46
        ) {
            openCustomerP2pTransferDialog(user)
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(18) } })

        container.addView(TextView(this).apply {
            text = "Recent Top-Up Transactions"; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(8))
        })
        if (AlistoKernelClient.paymentTransactions.isEmpty()) {
            val c = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
            c.addView(TextView(this).apply { text = "No top-up transactions recorded yet."; textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)) })
            container.addView(c)
        } else {
            AlistoKernelClient.paymentTransactions.take(8).forEach { tx ->
                val c = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
                val (sBg, sFg) = AlistoUiKit.statusColors(tx.status)
                c.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@CustomerMainActivity).apply {
                        text = "${tx.txRef}\n${tx.purpose} • PHP ${"%.2f".format(tx.amountPhp)}"
                        textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(AlistoUiKit.badgePill(this@CustomerMainActivity, tx.status, sBg, sFg))
                })
                container.addView(c)
            }
        }

        container.addView(TextView(this).apply {
            text = "Wallet Credit Ledger"; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, dp(10), 0, dp(8))
        })
        if (AlistoKernelClient.ledgerHistory.isEmpty()) {
            val c = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
            c.addView(TextView(this).apply { text = "No credit ledger movements yet."; textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)) })
            container.addView(c)
        } else {
            AlistoKernelClient.ledgerHistory.take(12).forEach { entry ->
                val c = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 12)
                c.addView(TextView(this).apply {
                    text = "${entry.entryType}  •  PHP ${entry.amount}  (Bal: PHP ${"%.2f".format(entry.balanceAfter)})"
                    textSize = 12.5f; setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(if (entry.amount.startsWith("-")) "#FCA5A5" else "#34D399"))
                })
                if (entry.notes.isNotBlank()) {
                    c.addView(TextView(this).apply {
                        text = entry.notes; textSize = 11.5f
                        setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(3), 0, 0)
                    })
                }
                container.addView(c)
            }
        }

        scroll.addView(container)
        return scroll
    }

    private fun initiateCustomerPaymentFlow(amountPhp: Double) {
        val payInfo = AlistoKernelClient.currentPaymentModeInfo
        if (payInfo.paymentMode == "MANUAL") {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(10))
                setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
            }
            layout.addView(TextView(this).apply {
                text = "Amount Due: PHP ${"%.2f".format(amountPhp)}\n\n${payInfo.manualInstructions}"
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); textSize = 13f; setPadding(0, 0, 0, dp(12))
            })
            val refInput = AlistoUiKit.inputField(this, "GCash / QR Ph / Bank Reference No.")
            layout.addView(AlistoUiKit.labeledField(this, "Payment Reference Number", refInput))

            AlertDialog.Builder(this)
                .setTitle("Customer Wallet Top-Up").setView(layout)
                .setPositiveButton("Submit for Approval") { _, _ ->
                    val ref = refInput.text.toString().trim()
                    uiScope.launch {
                        val res = AlistoKernelClient.submitManualPaymentRequestRpc(
                            "WALLET_TOPUP", amountPhp, null, ref, "Manual receipt submitted via Customer APK"
                        )
                        res.onSuccess {
                            Toast.makeText(this@CustomerMainActivity, "Reference submitted for Admin approval!", Toast.LENGTH_LONG).show()
                            showAuthenticatedCustomerPortal()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity)
                                .setTitle("Submission Error").setMessage(err.message).setPositiveButton("OK", null).show()
                        }
                    }
                }.setNegativeButton("Cancel", null).show()
            return
        }

        Toast.makeText(this, "Opening PayMongo (${payInfo.paymentMode})...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            val res = AlistoKernelClient.initiatePayMongoCheckout("WALLET_TOPUP", amountPhp, null)
            res.onSuccess { json ->
                val url = json.optString("checkout_url", "")
                val txRef = json.optString("tx_ref", "")
                Toast.makeText(this@CustomerMainActivity, "Checkout [$txRef]", Toast.LENGTH_LONG).show()
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                showAuthenticatedCustomerPortal()
            }.onFailure { err ->
                AlertDialog.Builder(this@CustomerMainActivity)
                    .setTitle("PayMongo Checkout").setMessage(err.message).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun buildCustomerAccountTab(user: AuthenticatedUser): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(TextView(this).apply {
            text = "Account"; textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(14))
        })

        val profileCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 18)
        profileCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(TextView(this@CustomerMainActivity).apply {
                text = user.nickname.take(1).uppercase(); gravity = Gravity.CENTER; textSize = 22f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.BG_DEEP))
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, AlistoUiKit.CYAN, 26)
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) }
            })
            addView(LinearLayout(this@CustomerMainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@CustomerMainActivity).apply {
                    text = user.fullName; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                })
                addView(TextView(this@CustomerMainActivity).apply {
                    text = "@${user.username} • ${user.phone}"; textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                })
            })
        })

        profileCard.addView(
            AlistoUiKit.badgePill(
                this, "Trust Score ${"%.0f".format(user.customerTrustScore)}%  •  UP ${user.thumbsUpCount} / DOWN ${user.thumbsDownCount}",
                "#064E3B", "#34D399"
            ).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        )
        profileCard.addView(TextView(this).apply {
            text = "Home Pin: ${user.addressText.ifBlank { "${user.barangayZone}, ${user.municipality}" }}"
            textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
        })
        container.addView(profileCard)

        container.addView(AlistoUiKit.pillButton(this, "Navigate Home (Local Roads • avoid=ht)", "#064E3B", "#34D399", "#10B981", heightDp = 46) {
            AlistoSpatialEngine.launchLocalRoadNavigation(this, user.homeLat, user.homeLng)
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })

        container.addView(AlistoUiKit.pillButton(this, "Sync Account State", AlistoUiKit.BG_CARD, AlistoUiKit.CYAN, AlistoUiKit.BORDER_LIGHT, heightDp = 46) {
            uiScope.launch {
                AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "customer")
                Toast.makeText(this@CustomerMainActivity, "Account synced", Toast.LENGTH_SHORT).show()
                showAuthenticatedCustomerPortal()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } })

        container.addView(AlistoUiKit.pillButton(this, "Sign Out & Lock", "#450A0A", "#FCA5A5", "#7F1D1D", heightDp = 46) {
            realtimePollJob?.cancel(); chatHeartbeatJob?.cancel(); AlistoChatEngine.stopWatching(); AlistoLocationTracker.stopLiveUpdates(); AlistoKernelClient.signOut(); showEntryPortalGate()
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) })

        scroll.addView(container)
        return scroll
    }

    private fun buildCustomerBookingCard(b: BookingRecord): View {
        val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 16)
        val (badgeBg, badgeFg) = AlistoUiKit.statusColors(b.status)

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addView(TextView(this@CustomerMainActivity).apply {
                text = b.bookingRef; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(AlistoUiKit.badgePill(this@CustomerMainActivity, b.status, badgeBg, badgeFg))
        })

        card.addView(TextView(this).apply { text = b.serviceTitle; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)) })

        val addrSummary = buildString {
            append("Pickup: ${b.pickupAddress}")
            if (b.pickupNote.isNotBlank()) append("\n  Note: ${b.pickupNote}")
            if (b.dropoffAddress.isNotBlank() && b.dropoffAddress != b.pickupAddress) {
                append("\nDrop-Off: ${b.dropoffAddress}")
                if (b.dropoffNote.isNotBlank()) append("\n  Note: ${b.dropoffNote}")
            }
        }
        card.addView(TextView(this).apply {
            text = addrSummary; textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(4), 0, dp(8))
        })

        if (b.bookingNotes.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "Booking Note (Open Board Only): ${b.bookingNotes}"
                textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.AMBER))
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#2A1B08", 10, "#78350F")
                setPadding(dp(10), dp(7), dp(10), dp(7))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            })
        }

        val liveWaitSnap = computeLiveWaitingSnapshot(b)
        val effWaitFee = if (b.waitingFeePhp > 0.0) b.waitingFeePhp else liveWaitSnap.runningFeePhp
        val effWaitMins = if (b.waitingBillableMinutes > 0) b.waitingBillableMinutes else liveWaitSnap.billableMinutes
        val finalBillTotal = b.quotedFee + effWaitFee + b.tipAmount

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "Total: PHP ${"%.2f".format(finalBillTotal)}", AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
            if (effWaitFee > 0.0) {
                addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "+PHP ${"%.2f".format(effWaitFee)} Wait (${effWaitMins}m)", "#450A0A", "#FDA4AF").apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
                })
            }
            if (b.tipAmount > 0) addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "+PHP ${"%.0f".format(b.tipAmount)} Tip", AlistoUiKit.BG_INPUT, AlistoUiKit.AMBER))
        })
        if (b.status in listOf("COMPLETED", "COMPLETED_WITH_DISPUTE")) {
            val compLines = buildString {
                append("Fare: PHP ${"%.2f".format(b.quotedFee)}")
                if (effWaitFee > 0.0) append("  •  Paid Waiting (${effWaitMins}m): PHP ${"%.2f".format(effWaitFee)}")
                if (b.tipAmount > 0.0) append("  •  Tip: PHP ${"%.2f".format(b.tipAmount)}")
                append("\nFinal Total Charged: PHP ${"%.2f".format(finalBillTotal)} (${b.paymentMethod})")
            }
            card.addView(TextView(this).apply {
                text = compLines
                textSize = 12f; setTextColor(Color.parseColor("#CBD5E1"))
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#0F172A", 10, "#334155")
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            })
        } else {
            card.addView(buildKindnessAndWaitingBanner(b))
        }

        val proName = b.contractorFullName
        if (proName != null) {
            val proBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = AlistoUiKit.frostedGlassBg(this@CustomerMainActivity, "#B30A2E6E", 14, "#CCFACC15", alphaFraction = 0.85f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { showAssignedContractorProfileModal(b) }
            }
            val decodedBmp = try {
                val rawUrl = b.contractorProfilePhotoUrl.orEmpty()
                if (rawUrl.startsWith("data:image") && rawUrl.contains(",")) {
                    val bytes = Base64.decode(rawUrl.substringAfter(","), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else null
            } catch (_: Exception) { null }

            if (decodedBmp != null) {
                headerRow.addView(ImageView(this).apply {
                    setImageBitmap(decodedBmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) }
                })
            } else {
                headerRow.addView(TextView(this).apply {
                    text = proName.take(1).uppercase()
                    gravity = Gravity.CENTER
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(AlistoUiKit.BG_DEEP))
                    background = AlistoUiKit.roundedBg(this@CustomerMainActivity, AlistoUiKit.GOLD_ACCENT, 22)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(10) }
                })
            }

            val vipTag = if (b.contractorVipBadge) " • VIP" else ""
            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this).apply {
                text = "$proName$vipTag  (Tap for Profile)"
                textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            })
            infoCol.addView(TextView(this).apply {
                val phoneStr = b.contractorPhone?.let { " • $it" }.orEmpty()
                text = "★ ${"%.2f".format(b.contractorRatingAvg)} (${b.contractorRatingCount} reviews)$phoneStr"
                textSize = 11.5f; setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
            })
            headerRow.addView(infoCol)
            proBox.addView(headerRow)

            val vehDesc = listOfNotNull(b.contractorVehicleColor, b.contractorVehicleMake, b.contractorVehicleModel)
                .filter { it.isNotBlank() }.joinToString(" ")
            val vehTypeLabel = b.contractorVehicleType?.replace("_", " ")?.replaceFirstChar { it.uppercase() } ?: ""
            val plateLabel = b.contractorPlateNo?.takeIf { it.isNotBlank() }
            if (vehDesc.isNotBlank() || plateLabel != null || (b.verticalKey != "skills_marketplace" && vehTypeLabel.isNotBlank())) {
                val vehLine = buildString {
                    append("Vehicle: ")
                    append(vehDesc.ifBlank { vehTypeLabel.ifBlank { "Registered Vehicle" } })
                    if (plateLabel != null) append("  •  Plate: $plateLabel")
                }
                proBox.addView(TextView(this).apply {
                    text = vehLine
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                    setPadding(0, dp(7), 0, 0)
                })
            }

            if (b.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")) {
                proBox.addView(AlistoUiKit.pillButton(
                    this, "Track Pro & My Location on Live Map →", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 38, textSizeSp = 12f
                ) {
                    activeTab = "BOOK"
                    bookingStep = "LAUNCHER"
                    showAuthenticatedCustomerPortal()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                })
            }
            card.addView(proBox)
        } else if (b.status in listOf("CREATED", "PENDING", "SEARCHING")) {
            card.addView(TextView(this).apply {
                text = "We are finding the nearest pro for you..."
                textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT))
                setPadding(0, 0, 0, dp(8))
            })
        }

        if (b.status in listOf("PENDING", "SEARCHING", "CREATED")) {
            val matchingBids = AlistoKernelClient.bookingBids.filter { it.bookingId == b.id && it.status == "ACTIVE" }
            if (matchingBids.isNotEmpty()) {
                card.addView(TextView(this).apply {
                    text = "Incoming Offers (${matchingBids.size})"
                    textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.CYAN)); setPadding(0, dp(4), 0, dp(8))
                })
                matchingBids.forEach { bid ->
                    val bidCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_INPUT, AlistoUiKit.BORDER_LIGHT, padDp = 12)
                    val vipStr = if (bid.contractorVipBadge) " • VIP" else ""
                    val highlights = if (bid.reviewHighlights.isNotEmpty()) "\n${bid.reviewHighlights.joinToString(" • ")}" else ""
                    bidCard.addView(TextView(this).apply {
                        text = "${bid.contractorName} (@${bid.contractorUsername})$vipStr\n★ ${"%.2f".format(bid.contractorRatingAvg)} (${bid.contractorRatingCount}) • ETA ${bid.estimatedEtaMin} min$highlights"
                        textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, 0, 0, dp(10))
                    })
                    bidCard.addView(AlistoUiKit.pillButton(
                        this, "Accept Offer • PHP ${"%.2f".format(bid.bidAmountPhp)}", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, heightDp = 42, textSizeSp = 13f
                    ) {
                        uiScope.launch {
                            val res = AlistoKernelClient.acceptContractorBidRpc(b.id, bid.id)
                            res.onSuccess {
                                Toast.makeText(this@CustomerMainActivity, "Offer accepted!", Toast.LENGTH_LONG).show()
                                showAuthenticatedCustomerPortal()
                            }.onFailure { err ->
                                AlertDialog.Builder(this@CustomerMainActivity)
                                    .setTitle("Bid Error").setMessage(err.message).setPositiveButton("OK", null).show()
                            }
                        }
                    })
                    card.addView(bidCard)
                }
            }
        }

        if (b.status == "COMPLETED" && b.contractorId != null) {
            card.addView(AlistoUiKit.pillButton(this, "★ Rate Your Pro", AlistoUiKit.AMBER, AlistoUiKit.BG_DEEP, heightDp = 42, textSizeSp = 13f) {
                openCustomerStarRatingDialog(b)
            }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } })
        }

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(AlistoUiKit.pillButton(
                this@CustomerMainActivity, "In-App Chat", AlistoUiKit.BG_INPUT, AlistoUiKit.TEXT_PRIMARY, AlistoUiKit.BORDER_LIGHT, heightDp = 40, textSizeSp = 12.5f
            ) { openChatDialog(b) }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (b.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")) rightMargin = dp(8)
                }
            })
            if (b.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")) {
                addView(AlistoUiKit.pillButton(
                    this@CustomerMainActivity, "Cancel Options", "#450A0A", "#FCA5A5", "#7F1D1D", heightDp = 40, textSizeSp = 12.5f
                ) {
                    val opts = arrayOf("50/50 Mutual Cancellation", "Report 30-Min Contractor No-Show")
                    AlertDialog.Builder(this@CustomerMainActivity)
                        .setTitle("Cancel ${b.bookingRef}")
                        .setItems(opts) { _, which ->
                            uiScope.launch {
                                if (which == 0) AlistoKernelClient.executeMutualOrNoShowCancelRpc(b.id, "MUTUAL_CONFIRMED", "Customer & Contractor mutual cancel")
                                else AlistoKernelClient.executeMutualOrNoShowCancelRpc(b.id, "CONTRACTOR_NO_SHOW_30MIN", "Contractor >30m no-show")
                                showAuthenticatedCustomerPortal()
                            }
                        }
                        .setNegativeButton("Keep Booking", null).show()
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            }
        })
        return card
    }

    private fun openCustomerStarRatingDialog(b: BookingRecord) {
        val contractorId = b.contractorId ?: return
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(10))
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_SURFACE))
        }
        val starSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("5 Stars (★★★★★ - Excellent)", "4 Stars (★★★★☆ - Good)", "3 Stars (★★★☆☆ - Fair)", "2 Stars (★★☆☆☆ - Poor)", "1 Star (★☆☆☆☆ - Poor)")
            )
            background = AlistoUiKit.roundedBg(this@CustomerMainActivity, AlistoUiKit.BG_INPUT, 10, AlistoUiKit.BORDER_LIGHT)
        }
        layout.addView(AlistoUiKit.labeledField(this, "Rating", starSpinner))
        val tagBoxes = listOf("Punctual Arrival", "Quality Workmanship", "Transparent Pricing", "Polite & Professional").map { tag ->
            CheckBox(this).apply { text = tag; setTextColor(Color.WHITE); isChecked = true }
        }
        tagBoxes.forEach { layout.addView(it) }
        val commentInput = AlistoUiKit.inputField(this, "Optional review comment...")
        layout.addView(AlistoUiKit.labeledField(this, "Comments", commentInput))

        AlertDialog.Builder(this)
            .setTitle("Rate Pro • ${b.bookingRef}").setView(layout)
            .setPositiveButton("Submit Rating") { _, _ ->
                val stars = 5 - starSpinner.selectedItemPosition
                val selectedTags = tagBoxes.filter { it.isChecked }.map { it.text.toString() }
                val comments = commentInput.text.toString().ifBlank { "Great service!" }
                uiScope.launch {
                    val res = AlistoKernelClient.submitCustomerStarRatingRpc(b.id, contractorId, stars, selectedTags, comments)
                    res.onSuccess {
                        Toast.makeText(this@CustomerMainActivity, "$stars★ rating submitted!", Toast.LENGTH_LONG).show()
                        showAuthenticatedCustomerPortal()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun openChatDialog(booking: BookingRecord) {
        openLiveCustomerChatDialog(booking)
    }

    private fun syncCustomerChatEngineWatcher(user: AuthenticatedUser) {
        val activeChatJob = AlistoKernelClient.bookings.firstOrNull {
            it.clientId == user.id && it.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")
        }
        if (activeChatJob != null) {
            AlistoChatEngine.watchActiveBooking(
                context = this,
                bookingId = activeChatJob.id,
                bookingRef = activeChatJob.bookingRef,
                userId = user.id,
                userName = user.nickname.ifBlank { user.fullName.ifBlank { "Customer" } },
                counterpartyName = activeChatJob.contractorFullName ?: "Your Pro"
            )
        } else {
            AlistoChatEngine.stopWatching()
        }
    }

    private fun startCustomerChatHeartbeat() {
        chatHeartbeatJob?.cancel()
        AlistoKernelClient.currentSessionUser?.let { syncCustomerChatEngineWatcher(it) }
    }

    private fun startCustomerRealtimeLoop() {
        startCustomerChatHeartbeat()
        realtimePollJob?.cancel()
        AlistoKernelClient.bookings.forEach { b ->
            if (!lastBookingStatuses.containsKey(b.id)) lastBookingStatuses[b.id] = b.status
        }
        if (lastVerticalsDigest.isEmpty()) {
            lastVerticalsDigest = AlistoKernelClient.verticalsDigest()
        }
        realtimePollJob = uiScope.launch {
            while (isActive) {
                delay(6_000L)
                if (AlistoKernelClient.currentSessionToken.isNullOrBlank()) break
                AlistoKernelClient.syncBookingsAndWallet()
                    AlistoKernelClient.currentSessionUser?.let { u -> syncCustomerChatEngineWatcher(u); maybeOpenPendingChatDialog() }
                val fired = AlistoPushCenter.dispatchUnreadNotifications(this@CustomerMainActivity, uiScope)
                val user = AlistoKernelClient.currentSessionUser

                var statusChanged = false
                if (user != null) {
                    AlistoKernelClient.bookings.filter { it.clientId == user.id }.forEach { b ->
                        val prev = lastBookingStatuses[b.id]
                        if (prev != null && prev != b.status) {
                            statusChanged = true
                            val promptMsg = when (b.status) {
                                "ACCEPTED", "ON_THE_WAY" -> "Yey! We found a pro for you! ${b.contractorFullName ?: "Your pro"} is on the way. Please get ready for pickup!"
                                "ARRIVED" -> "${b.contractorFullName ?: "Your pro"} has arrived at your pickup location!"
                                "ONGOING", "IN_PROGRESS" -> "Your booking ${b.bookingRef} is now underway."
                                "COMPLETED", "COMPLETED_WITH_DISPUTE" -> "Booking ${b.bookingRef} completed! Thank you for riding with Alisto."
                                "EXPIRED" -> "No pro accepted ${b.bookingRef} in time. Please try again."
                                else -> null
                            }
                            if (promptMsg != null) {
                                Toast.makeText(this@CustomerMainActivity, promptMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                        if (b.status == "ARRIVED" && b.customerGoingOutAt.isNullOrBlank() && arrivedAlertedBookingIds.add(b.id)) {
                            showArrivalDoorbellModal(b)
                        }
                        lastBookingStatuses[b.id] = b.status
                    }
                    val activeChatJob = AlistoKernelClient.bookings.firstOrNull {
                        it.clientId == user.id && it.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")
                    }
                    if (activeChatJob != null) {
                        val chatRes = AlistoKernelClient.fetchBookingMessagesRpc(activeChatJob.id)
                        chatRes.onSuccess { msgs ->
                            val latest = msgs.lastOrNull()
                            if (latest != null && latest.id.isNotBlank()) {
                                val prevId = lastSeenChatMsgIds[activeChatJob.id]
                                lastSeenChatMsgIds[activeChatJob.id] = latest.id
                                if (prevId != null && prevId != latest.id && latest.senderId != user.id) {
                                    CustomerDoorbellEngine.playIncomingChatChime(this@CustomerMainActivity)
                                    Toast.makeText(this@CustomerMainActivity, "Message from ${latest.senderName}: ${latest.body}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    val latestVerticalsDigest = AlistoKernelClient.verticalsDigest()
                    val verticalsChanged = latestVerticalsDigest != lastVerticalsDigest
                    if (verticalsChanged) {
                        lastVerticalsDigest = latestVerticalsDigest
                        if (activeTab == "BOOK" && bookingStep != "LAUNCHER" && !AlistoKernelClient.isVerticalEnabled(selectedVerticalKey)) {
                            bookingStep = "LAUNCHER"
                            Toast.makeText(
                                this@CustomerMainActivity,
                                "This service vertical was paused by operations.",
                                Toast.LENGTH_LONG
                            ).show()
                            showAuthenticatedCustomerPortal()
                            break
                        }
                        if (activeTab == "BOOK" && bookingStep == "LAUNCHER") {
                            statusChanged = true
                        }
                    }

                    refreshCustomerMapLayers(user)
                    if (statusChanged && (activeTab == "ACTIVITY" || (activeTab == "BOOK" && bookingStep == "LAUNCHER"))) {
                        showAuthenticatedCustomerPortal()
                        break
                    }
                }
                if (fired > 0 && !statusChanged) {
                    Toast.makeText(this@CustomerMainActivity, "$fired new live update(s)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        realtimePollJob?.cancel()
        chatHeartbeatJob?.cancel()
        AlistoChatEngine.stopWatching()
        pickupSearchJob?.cancel()
        dropoffSearchJob?.cancel()
        reverseGeoJob?.cancel()
        AlistoLocationTracker.stopLiveUpdates()
        uiScope.cancel()
    }
}
