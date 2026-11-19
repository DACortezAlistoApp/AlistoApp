package ph.alisto.customer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
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

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectedVerticalKey = "skills_marketplace"
    private var selectedJobSubtype = "ON_SITE_SKILL"
    private var activeTab = "BOOK"
    private var activityFilter = "ACTIVE"
    private var sheetExpanded = false
    private var showAdvancedOptions = false

    private var entryMode = "LOGIN"
    private var compressedProfilePhotoDataUrl: String? = null
    private val sequentialWaypoints = mutableListOf<JSONObject>()
    private var realtimePollJob: Job? = null
    private var customerMapView: AlistoInteractiveMapView? = null
    private var mapTapMode: String = "ORIGIN"
    private var activeOriginLat: Double = 15.1450
    private var activeOriginLng: Double = 120.5887
    private var draftTitle: String = ""
    private var draftPickupAddress: String = ""
    private var draftOfferFee: String = "500"
    private var draftTip: String = "50"
    private var draftErrandGoods: String = "0"
    private var draftDeadheadKm: String = "0"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlistoUiKit.applyDarkSystemBars(this)
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
                    this,
                    "Quick Unlock • $savedLabel",
                    AlistoUiKit.EMERALD,
                    AlistoUiKit.BG_DEEP
                ) { triggerFrictionlessBiometricEntry() }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
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
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
                AlistoUiKit.pillButton(this, "Sign In", AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP) {
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
                    this, "Snap Current GPS Pin", AlistoUiKit.BG_INPUT, AlistoUiKit.CYAN,
                    AlistoUiKit.BORDER_LIGHT, heightDp = 42, textSizeSp = 13f
                ) {
                    homeLat = 15.1450 + (Math.random() * 0.009)
                    homeLng = 120.5887 + (Math.random() * 0.009)
                    val rev = AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, homeLat, homeLng)
                    addrInput.setText(rev)
                    gpsCoordText.text = "Snapped: $rev"
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(14) }
                }
            )

            formCard.addView(
                AlistoUiKit.pillButton(this, "Create Account", AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP) {
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

    private fun showAuthenticatedCustomerPortal() {
        val user = AlistoKernelClient.currentSessionUser ?: return showEntryPortalGate()
        AlistoPushCenter.ensureNotificationPermission(this)
        AlistoPushCenter.dispatchUnreadNotifications(this, uiScope)
        if (draftPickupAddress.isBlank()) {
            activeOriginLat = user.homeLat
            activeOriginLng = user.homeLng
            draftPickupAddress = user.addressText.ifBlank { "${user.barangayZone}, ${user.municipality}" }
        }
        startCustomerRealtimeLoop()
        AlistoComplianceGate.checkOtaUpdate(this, "customer", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        AlistoComplianceGate.enforceRa10173ConsentIfRequired(this, uiScope, "customer")

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }
        val contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        when (activeTab) {
            "ACTIVITY" -> contentHost.addView(buildCustomerActivityTab(user))
            "ACCOUNT" -> contentHost.addView(buildCustomerAccountTab(user))
            else -> contentHost.addView(buildCustomerBookMapTab(user))
        }

        val myActiveCount = AlistoKernelClient.bookings.count {
            (user.role == "super_admin" || it.clientId == user.id) &&
                it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")
        }
        val bottomNav = AlistoUiKit.bottomNavBar(
            this,
            tabs = listOf(
                Triple("BOOK", "◎", "Book"),
                Triple("ACTIVITY", "☰", if (myActiveCount > 0) "Activity ($myActiveCount)" else "Activity"),
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

    private fun buildCustomerBookMapTab(user: AuthenticatedUser): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }

        val mapStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, if (sheetExpanded) 0.34f else 0.48f
            )
        }

        val mapContainer = AlistoInteractiveMapView(this)
        customerMapView = mapContainer
        mapStage.addView(
            mapContainer,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // Floating Top Bar (Greeting + Live GPS pill + Sync)
        val topHud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        }
        val livePinsCount = AlistoKernelClient.liveTelemetryPins.size
        topHud.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#E60D1424", 24, AlistoUiKit.BORDER_LIGHT)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }

            addView(View(this@CustomerMainActivity).apply {
                background = AlistoUiKit.roundedBg(
                    this@CustomerMainActivity, if (livePinsCount > 0) AlistoUiKit.EMERALD else AlistoUiKit.CYAN, 6
                )
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
            })
            addView(TextView(this@CustomerMainActivity).apply {
                text = if (livePinsCount > 0) "Hi ${user.nickname} • $livePinsCount live tracking" else "Hi ${user.nickname} • ${user.municipality}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                maxLines = 1
            })
        })
        topHud.addView(AlistoUiKit.chipButton(this, "Sync", true, "#E60D1424", AlistoUiKit.CYAN) {
            uiScope.launch {
                AlistoKernelClient.fetchAppBootStateRpc(AlistoKernelClient.currentSessionToken ?: "", "customer")
                showAuthenticatedCustomerPortal()
            }
        }.apply { elevation = dp(4).toFloat() })
        mapStage.addView(topHud)

        // Floating Bottom Map Pin Controls
        val mapControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }
        val btnOriginPin = TextView(this)
        val btnStopPin = TextView(this)
        fun styleMapTapPills() {
            btnOriginPin.apply {
                text = "Pin Pickup"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (mapTapMode == "ORIGIN") AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_PRIMARY))
                background = AlistoUiKit.roundedBg(
                    this@CustomerMainActivity,
                    if (mapTapMode == "ORIGIN") AlistoUiKit.EMERALD else "#E60D1424", 20,
                    if (mapTapMode == "ORIGIN") AlistoUiKit.EMERALD else AlistoUiKit.BORDER_LIGHT
                )
                setPadding(dp(12), dp(7), dp(12), dp(7))
                elevation = dp(4).toFloat()
            }
            btnStopPin.apply {
                text = "+ Pin Stop"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (mapTapMode == "WAYPOINT") AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_PRIMARY))
                background = AlistoUiKit.roundedBg(
                    this@CustomerMainActivity,
                    if (mapTapMode == "WAYPOINT") AlistoUiKit.AMBER else "#E60D1424", 20,
                    if (mapTapMode == "WAYPOINT") AlistoUiKit.AMBER else AlistoUiKit.BORDER_LIGHT
                )
                setPadding(dp(12), dp(7), dp(12), dp(7))
                elevation = dp(4).toFloat()
            }
        }
        styleMapTapPills()
        btnOriginPin.setOnClickListener { mapTapMode = "ORIGIN"; styleMapTapPills() }
        btnStopPin.setOnClickListener { mapTapMode = "WAYPOINT"; styleMapTapPills() }

        mapControlsRow.addView(btnOriginPin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        mapControlsRow.addView(btnStopPin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        mapControlsRow.addView(AlistoUiKit.chipButton(this, "Center", true, "#E60D1424", AlistoUiKit.CYAN) {
            customerMapView?.recenter(activeOriginLat, activeOriginLng, 15)
        }.apply { elevation = dp(4).toFloat() })
        mapStage.addView(mapControlsRow)
        root.addView(mapStage)

        // Lyft-Style Rounded Bottom Sheet
        val sheetWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.topSheetBg(this@CustomerMainActivity, AlistoUiKit.BG_SURFACE, 24, AlistoUiKit.BORDER)
            elevation = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, if (sheetExpanded) 0.66f else 0.52f
            )
        }
        sheetWrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { sheetExpanded = !sheetExpanded; showAuthenticatedCustomerPortal() }
            addView(AlistoUiKit.dragHandle(this@CustomerMainActivity))
        })

        val sheetScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val sheetBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(2), dp(18), dp(20))
        }

        // Active Booking Spotlight Banner
        val activeBookings = AlistoKernelClient.bookings.filter {
            (user.role == "super_admin" || it.clientId == user.id) &&
                it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")
        }
        if (activeBookings.isNotEmpty()) {
            val topB = activeBookings.first()
            val bidCount = AlistoKernelClient.bookingBids.count { it.bookingId == topB.id && it.status == "ACTIVE" }
            sheetBody.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#0C2942", 14, AlistoUiKit.CYAN)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) }
                setOnClickListener { activeTab = "ACTIVITY"; showAuthenticatedCustomerPortal() }

                addView(TextView(this@CustomerMainActivity).apply {
                    text = "${topB.bookingRef} • ${topB.status}" + if (bidCount > 0) " • $bidCount bid(s) ready" else ""
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(AlistoUiKit.CYAN))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@CustomerMainActivity).apply {
                    text = "View →"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                })
            })
        }

        // Service Mode Segmented Selector
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val modes = listOf(
            Triple("skills_marketplace", "ON_SITE_SKILL", "Skills"),
            Triple("delivery_logistics", "ERRAND_PURCHASING", "Errands"),
            Triple("ride_hailing", "POINT_TO_POINT", "Ride")
        )
        modes.forEachIndexed { idx, (vKey, sub, label) ->
            modeRow.addView(
                AlistoUiKit.chipButton(this, label, selectedVerticalKey == vKey, AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP) {
                    selectedVerticalKey = vKey
                    selectedJobSubtype = sub
                    mapTapMode = if (vKey == "skills_marketplace") "ORIGIN" else "WAYPOINT"
                    showAuthenticatedCustomerPortal()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx < modes.lastIndex) rightMargin = dp(8)
                    }
                }
            )
        }
        sheetBody.addView(modeRow)

        val tripCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
        val pickupInput = AlistoUiKit.inputField(this, "Pickup or job site address", InputType.TYPE_CLASS_TEXT, draftPickupAddress)
        val titleHint = when (selectedVerticalKey) {
            "delivery_logistics" -> "What should we pick up or deliver?"
            "ride_hailing" -> "Where are you headed?"
            else -> "What help do you need? (e.g., Aircon, Plumbing)"
        }
        val titleInput = AlistoUiKit.inputField(this, titleHint, InputType.TYPE_CLASS_TEXT, draftTitle)

        tripCard.addView(AlistoUiKit.labeledField(this, "● Pickup / Job Site (Tap map to adjust)", pickupInput, 10))
        tripCard.addView(AlistoUiKit.labeledField(this, "◆ Request Details", titleInput, 8))

        val waypointsStatus = TextView(this).apply {
            text = if (sequentialWaypoints.isEmpty()) {
                if (selectedVerticalKey == "skills_marketplace") "Single job site pin" else "Tap map (+ Pin Stop) to add stops or destination"
            } else {
                "Stops (${sequentialWaypoints.size}): " + sequentialWaypoints.joinToString(" → ") {
                    "#${it.getInt("stop_number")} ${it.getString("address")}"
                }
            }
            textSize = 12f
            setTextColor(Color.parseColor(if (sequentialWaypoints.isEmpty()) AlistoUiKit.TEXT_MUTED else AlistoUiKit.AMBER))
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        tripCard.addView(waypointsStatus)

        if (selectedVerticalKey != "skills_marketplace" || sequentialWaypoints.isNotEmpty()) {
            val wpBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            wpBtnRow.addView(
                AlistoUiKit.chipButton(this, "+ Add Stop Pin", true, AlistoUiKit.BG_INPUT, AlistoUiKit.AMBER) {
                    val stopNum = sequentialWaypoints.size + 1
                    val wLat = activeOriginLat + (stopNum * 0.003)
                    val wLng = activeOriginLng + (stopNum * 0.003)
                    val addr = AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, wLat, wLng)
                    sequentialWaypoints.add(JSONObject().apply {
                        put("stop_number", stopNum); put("lat", wLat); put("lng", wLng); put("address", addr)
                    })
                    draftTitle = titleInput.text.toString()
                    draftPickupAddress = pickupInput.text.toString()
                    showAuthenticatedCustomerPortal()
                }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } }
            )
            if (sequentialWaypoints.isNotEmpty()) {
                wpBtnRow.addView(AlistoUiKit.chipButton(this, "Clear Stops", false, AlistoUiKit.BG_INPUT, AlistoUiKit.ROSE) {
                    sequentialWaypoints.clear()
                    draftTitle = titleInput.text.toString()
                    draftPickupAddress = pickupInput.text.toString()
                    showAuthenticatedCustomerPortal()
                })
            }
            tripCard.addView(wpBtnRow)
        }
        sheetBody.addView(tripCard)

        val feeInput = AlistoUiKit.inputField(this, "500", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, draftOfferFee)
        val tipInput = AlistoUiKit.inputField(this, "50", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, draftTip)
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
        val deadheadKmInput = AlistoUiKit.inputField(this, "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, draftDeadheadKm)
        sheetBody.addView(TextView(this).apply {
            text = if (showAdvancedOptions) "▾ Hide Pabili & Deadhead options" else "▸ Add Pabili goods budget or deadhead return km"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            setPadding(dp(2), dp(4), dp(2), dp(10))
            setOnClickListener {
                draftTitle = titleInput.text.toString()
                draftPickupAddress = pickupInput.text.toString()
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

        val ctaLabel = when (selectedVerticalKey) {
            "delivery_logistics" -> "Request Errand / Delivery"
            "ride_hailing" -> "Request Local Ride"
            else -> "Request Verified Pro"
        }
        sheetBody.addView(
            AlistoUiKit.pillButton(this, ctaLabel, AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP, heightDp = 50, textSizeSp = 15f) {
                val title = titleInput.text.toString().ifBlank { ctaLabel }
                val addr = pickupInput.text.toString().ifBlank { user.addressText }
                val fee = feeInput.text.toString().toDoubleOrNull() ?: 500.0
                val errandCost = errandCostInput.text.toString().toDoubleOrNull() ?: 0.0
                val deadheadKm = deadheadKmInput.text.toString().toDoubleOrNull() ?: 0.0
                val tip = tipInput.text.toString().toDoubleOrNull() ?: 0.0
                val wpArr = JSONArray().apply { sequentialWaypoints.forEach { put(it) } }
                val dropoff = if (sequentialWaypoints.isNotEmpty()) sequentialWaypoints.last().optString("address", addr) else addr

                uiScope.launch {
                    val res = AlistoKernelClient.createBookingV2Rpc(
                        verticalKey = selectedVerticalKey,
                        jobSubtype = selectedJobSubtype,
                        municipality = user.municipality,
                        barangayZone = user.barangayZone,
                        skillCategory = when (selectedVerticalKey) {
                            "delivery_logistics" -> "Delivery & Errands"
                            "ride_hailing" -> "Local Ride"
                            else -> "General Service"
                        },
                        serviceTitle = title,
                        pickupAddress = addr,
                        dropoffAddress = dropoff,
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
                        draftTitle = ""
                        draftErrandGoods = "0"
                        draftDeadheadKm = "0"
                        Toast.makeText(this@CustomerMainActivity, "Request live! Buffer: PHP $buf | Deadhead: PHP $dh", Toast.LENGTH_LONG).show()
                        activeTab = "ACTIVITY"
                        showAuthenticatedCustomerPortal()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@CustomerMainActivity)
                            .setTitle("Booking Notice").setMessage(err.message).setPositiveButton("OK", null).show()
                    }
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        )

        sheetScroll.addView(sheetBody)
        sheetWrap.addView(sheetScroll)
        root.addView(sheetWrap)

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
                draftPickupAddress = rev
                pickupInput.setText(rev)
                Toast.makeText(this@CustomerMainActivity, "Pickup pinned: $rev", Toast.LENGTH_SHORT).show()
            } else {
                val stopNum = sequentialWaypoints.size + 1
                sequentialWaypoints.add(JSONObject().apply {
                    put("stop_number", stopNum); put("lat", lat); put("lng", lng); put("address", rev)
                })
                waypointsStatus.text = "Stops (${sequentialWaypoints.size}): " + sequentialWaypoints.joinToString(" → ") {
                    "#${it.getInt("stop_number")} ${it.getString("address")}"
                }
                waypointsStatus.setTextColor(Color.parseColor(AlistoUiKit.AMBER))
                Toast.makeText(this@CustomerMainActivity, "Stop #$stopNum pinned: $rev", Toast.LENGTH_SHORT).show()
            }
            refreshCustomerMapLayers()
        }
        refreshCustomerMapLayers()

        return root
    }

    private fun buildCustomerActivityTab(user: AuthenticatedUser): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(TextView(this@CustomerMainActivity).apply {
                text = "Your Activity"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
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
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            })
            emptyCard.addView(TextView(this).apply {
                text = "Tap Book to pin a location and connect with verified local pros or errand runners."
                textSize = 13f
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                setPadding(0, dp(6), 0, dp(14))
            })
            emptyCard.addView(AlistoUiKit.pillButton(this, "Start a Booking", AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP, heightDp = 44) {
                activeTab = "BOOK"; showAuthenticatedCustomerPortal()
            })
            container.addView(emptyCard)
        } else {
            shown.forEach { b -> container.addView(buildCustomerBookingCard(b)) }
        }

        scroll.addView(container)
        return scroll
    }

    private fun buildCustomerAccountTab(user: AuthenticatedUser): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
        }

        container.addView(TextView(this).apply {
            text = "Account"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            setPadding(0, 0, 0, dp(14))
        })

        val profileCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 18)
        profileCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
            addView(TextView(this@CustomerMainActivity).apply {
                text = user.nickname.take(1).uppercase()
                gravity = Gravity.CENTER
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
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
                this, "Trust Score ${"%.0f".format(user.customerTrustScore)}%  •  👍 ${user.thumbsUpCount}  /  👎 ${user.thumbsDownCount}",
                "#064E3B", "#34D399"
            ).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        )
        profileCard.addView(TextView(this).apply {
            text = "Home Pin: ${user.addressText.ifBlank { "${user.barangayZone}, ${user.municipality}" }}"
            textSize = 13f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
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
            realtimePollJob?.cancel(); AlistoKernelClient.signOut(); showEntryPortalGate()
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) })

        scroll.addView(container)
        return scroll
    }

    private fun buildCustomerBookingCard(b: BookingRecord): View {
        val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 16)
        val (badgeBg, badgeFg) = AlistoUiKit.statusColors(b.status)

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addView(TextView(this@CustomerMainActivity).apply {
                text = b.bookingRef; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(AlistoUiKit.badgePill(this@CustomerMainActivity, b.status, badgeBg, badgeFg))
        })

        card.addView(TextView(this).apply {
            text = b.serviceTitle; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        })
        card.addView(TextView(this).apply {
            text = b.pickupAddress; textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(3), 0, dp(10))
        })

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "PHP ${"%.2f".format(b.quotedFee)}", AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
            if (b.tipAmount > 0) {
                addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "+PHP ${"%.0f".format(b.tipAmount)} Tip", AlistoUiKit.BG_INPUT, AlistoUiKit.AMBER))
            }
        })

        if (b.contractorFullName != null) {
            val vipTag = if (b.contractorVipBadge) " • VIP" else ""
            card.addView(TextView(this).apply {
                text = "Assigned: ${b.contractorFullName}$vipTag (★ ${"%.2f".format(b.contractorRatingAvg)} • ${b.contractorRatingCount})"
                textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.CYAN)); setPadding(0, 0, 0, dp(10))
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
                                if (which == 0) {
                                    AlistoKernelClient.executeMutualOrNoShowCancelRpc(b.id, "MUTUAL_CONFIRMED", "Customer & Contractor mutual cancel")
                                } else {
                                    AlistoKernelClient.executeMutualOrNoShowCancelRpc(b.id, "CONTRACTOR_NO_SHOW_30MIN", "Contractor >30m no-show")
                                }
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
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(10))
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_SURFACE))
        }
        val msgInput = AlistoUiKit.inputField(this, "Coordinate arrival or pabili item details...")
        dialogLayout.addView(msgInput)

        AlertDialog.Builder(this)
            .setTitle("Chat • ${booking.bookingRef}").setView(dialogLayout)
            .setPositiveButton("Send") { _, _ ->
                val body = msgInput.text.toString()
                if (body.isNotBlank()) {
                    uiScope.launch {
                        val res = AlistoKernelClient.sendModeratedChatMessageRpc(booking.id, body)
                        res.onSuccess { Toast.makeText(this@CustomerMainActivity, "Message sent", Toast.LENGTH_SHORT).show() }
                            .onFailure { err ->
                                AlertDialog.Builder(this@CustomerMainActivity)
                                    .setTitle("Moderation Notice").setMessage(err.message).setPositiveButton("OK", null).show()
                            }
                    }
                }
            }.setNegativeButton("Close", null).show()
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
                        originAddress = draftPickupAddress.ifBlank { u.addressText },
                        waypoints = sequentialWaypoints,
                        bookings = AlistoKernelClient.bookings.filter { u.role == "super_admin" || it.clientId == u.id },
                        telemetryPins = AlistoKernelClient.liveTelemetryPins
                    )
                }
                if (fired > 0) Toast.makeText(this@CustomerMainActivity, "$fired new live update(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        realtimePollJob?.cancel()
        uiScope.cancel()
    }
}
