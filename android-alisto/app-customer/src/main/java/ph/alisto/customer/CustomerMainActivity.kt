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
    private var entryMode = "LOGIN"
    private var activeTab = "BOOK"          // BOOK, ACTIVITY, ACCOUNT
    private var bookingStep = "LAUNCHER"    // LAUNCHER -> PICKUP_MAP
    private var activityFilter = "ACTIVE"   // ACTIVE, ALL
    private var sheetExpanded = false
    private var showAdvancedOptions = false

    private var selectedVerticalKey = "skills_marketplace"
    private var selectedJobSubtype = "ON_SITE_SKILL"
    private var selectedServiceLabel = "Skills & Trades"
    private var selectedSkillCategory = "General Service"

    private var compressedProfilePhotoDataUrl: String? = null
    private val sequentialWaypoints = mutableListOf<JSONObject>()
    private var realtimePollJob: Job? = null
    private var customerMapView: AlistoInteractiveMapView? = null
    private var activePickupField: EditText? = null
    private var mapTapMode: String = "ORIGIN"

    private var activeOriginLat: Double = 15.1450
    private var activeOriginLng: Double = 120.5887
    private var originPinnedManually = false
    private var promptedLocationPerm = false
    private var hasAutoCenteredGps = false

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AlistoLocationTracker.REQ_LOCATION_PERMISSION) {
            if (AlistoLocationTracker.hasPermission(this)) {
                startLiveGpsOnMap(forceCenter = true, snapOriginToGps = !originPinnedManually)
            } else {
                Toast.makeText(
                    this,
                    "Location permission not granted. You can still search landmarks or drop a pin.",
                    Toast.LENGTH_LONG
                ).show()
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

    private fun showAuthenticatedCustomerPortal() {
        val user = AlistoKernelClient.currentSessionUser ?: return showEntryPortalGate()
        AlistoPushCenter.ensureNotificationPermission(this)
        AlistoPushCenter.dispatchUnreadNotifications(this, uiScope)

        if (draftPickupAddress.isBlank()) {
            activeOriginLat = AlistoLocationTracker.lastLat ?: user.homeLat
            activeOriginLng = AlistoLocationTracker.lastLng ?: user.homeLng
            draftPickupAddress = user.addressText.ifBlank { "${user.barangayZone}, ${user.municipality}" }
        }
        startCustomerRealtimeLoop()
        AlistoComplianceGate.checkOtaUpdate(this, "customer", BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
        AlistoComplianceGate.enforceRa10173ConsentIfRequired(this, uiScope, "customer")

        if (!AlistoLocationTracker.hasPermission(this) && !promptedLocationPerm) {
            promptedLocationPerm = true
            AlistoLocationTracker.requestPermission(this)
        } else if (AlistoLocationTracker.hasPermission(this)) {
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
            "ACCOUNT" -> contentHost.addView(buildCustomerAccountTab(user))
            else -> {
                if (bookingStep == "LAUNCHER") {
                    contentHost.addView(buildServiceLauncherScreen(user))
                } else {
                    contentHost.addView(buildCustomerPickupMapScreen(user))
                }
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

    /**
     * Step 1: Guided Service Launcher shown upon opening the app.
     */
    private fun buildServiceLauncherScreen(user: AuthenticatedUser): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP))
        }

        // Live GPS mini-map preview at the top showing where the user currently is
        val mapStage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.36f)
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

        // Floating greeting pill + GPS recenter pill
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
            background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#E60D1424", 24, AlistoUiKit.BORDER_LIGHT)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }

            addView(View(this@CustomerMainActivity).apply {
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, AlistoUiKit.EMERALD, 6)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) }
            })
            addView(TextView(this@CustomerMainActivity).apply {
                text = "Hi ${user.nickname} • ${user.municipality}"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                maxLines = 1
            })
        })
        topHud.addView(AlistoUiKit.chipButton(this, "My GPS", true, "#E60D1424", AlistoUiKit.CYAN) {
            if (!AlistoLocationTracker.hasPermission(this)) {
                AlistoLocationTracker.requestPermission(this)
            } else {
                startLiveGpsOnMap(forceCenter = true, snapOriginToGps = false)
            }
        }.apply { elevation = dp(4).toFloat() })
        mapStage.addView(topHud)
        root.addView(mapStage)

        // Rounded Bottom Sheet asking what service the customer needs
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.topSheetBg(this@CustomerMainActivity, AlistoUiKit.BG_SURFACE, 24, AlistoUiKit.BORDER)
            elevation = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.64f)
        }
        sheet.addView(AlistoUiKit.dragHandle(this))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(20))
        }

        // Active booking jump banner if one is already running
        val activeBookings = AlistoKernelClient.bookings.filter {
            (user.role == "super_admin" || it.clientId == user.id) &&
                it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED")
        }
        if (activeBookings.isNotEmpty()) {
            val topB = activeBookings.first()
            val bidCount = AlistoKernelClient.bookingBids.count { it.bookingId == topB.id && it.status == "ACTIVE" }
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#0C2942", 14, AlistoUiKit.CYAN)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
                setOnClickListener { activeTab = "ACTIVITY"; showAuthenticatedCustomerPortal() }

                addView(TextView(this@CustomerMainActivity).apply {
                    text = "${topB.bookingRef} • ${topB.status}" + if (bidCount > 0) " • $bidCount offer(s)" else ""
                    textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.CYAN))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@CustomerMainActivity).apply {
                    text = "Track →"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                })
            })
        }

        body.addView(TextView(this).apply {
            text = "What do you need today?"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        })
        body.addView(TextView(this).apply {
            text = "Choose a service to set your pickup or job site location."
            textSize = 13f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            setPadding(0, dp(3), 0, dp(14))
        })

        data class LauncherOption(
            val title: String,
            val subtitle: String,
            val badge: String,
            val accent: String,
            val vKey: String,
            val sub: String,
            val cat: String
        )

        val options = listOf(
            LauncherOption(
                "Request Delivery",
                "Send packages, food, or documents point-to-point",
                "DELIVERY", AlistoUiKit.AMBER,
                "delivery_logistics", "POINT_TO_POINT", "Parcel & Delivery"
            ),
            LauncherOption(
                "Request Errands (Pabili)",
                "Multi-stop store pickups, groceries & personal errands",
                "ERRANDS", AlistoUiKit.CYAN,
                "delivery_logistics", "ERRAND_PURCHASING", "Errands & Pabili"
            ),
            LauncherOption(
                "Request Skills & Trades",
                "Book verified electricians, plumbers, aircon techs & shops",
                "SKILLS", AlistoUiKit.EMERALD,
                "skills_marketplace", "ON_SITE_SKILL", "General Service"
            ),
            LauncherOption(
                "Request a Local Ride",
                "Point-to-point sub-400cc local road mobility",
                "MOBILITY", "#38BDF8",
                "ride_hailing", "POINT_TO_POINT", "Local Ride"
            )
        )

        options.forEach { opt ->
            val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER_LIGHT, padDp = 15)
            card.setOnClickListener {
                selectedVerticalKey = opt.vKey
                selectedJobSubtype = opt.sub
                selectedServiceLabel = opt.title
                selectedSkillCategory = opt.cat
                mapTapMode = "ORIGIN"
                bookingStep = "PICKUP_MAP"
                sheetExpanded = false
                showAuthenticatedCustomerPortal()
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) }
                addView(AlistoUiKit.badgePill(this@CustomerMainActivity, opt.badge, AlistoUiKit.BG_INPUT, opt.accent).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                })
                addView(TextView(this@CustomerMainActivity).apply {
                    text = opt.title
                    textSize = 15.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                })
                addView(TextView(this@CustomerMainActivity).apply {
                    text = opt.subtitle
                    textSize = 12.5f
                    setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            row.addView(textCol)
            row.addView(TextView(this).apply {
                text = "→"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(opt.accent))
            })
            card.addView(row)
            body.addView(card)
        }

        scroll.addView(body)
        sheet.addView(scroll)
        root.addView(sheet)
        return root
    }

    /**
     * Step 2: Pickup / Job Site selection via Search Landmark, Current GPS, or Drop Pin on Map.
     */
    private fun buildCustomerPickupMapScreen(user: AuthenticatedUser): View {
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

        // Floating Top Bar: Back to Launcher + Selected Service Badge + My GPS button
        val topHud = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        }
        topHud.addView(AlistoUiKit.chipButton(this, "← Services", true, "#E60D1424", AlistoUiKit.TEXT_PRIMARY) {
            bookingStep = "LAUNCHER"
            showAuthenticatedCustomerPortal()
        }.apply {
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        })

        topHud.addView(TextView(this).apply {
            text = selectedServiceLabel
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.CYAN))
            background = AlistoUiKit.roundedBg(this@CustomerMainActivity, "#E60D1424", 20, AlistoUiKit.CYAN)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            elevation = dp(4).toFloat()
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) }
        })

        topHud.addView(AlistoUiKit.chipButton(this, "My GPS", true, "#E60D1424", AlistoUiKit.EMERALD) {
            snapPickupToCurrentGps()
        }.apply { elevation = dp(4).toFloat() })
        mapStage.addView(topHud)

        // Floating Bottom Map Pin Controls (Drop Pickup Pin vs Drop Stop Pin)
        val mapControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }
        val btnOriginPin = TextView(this)
        val btnStopPin = TextView(this)
        fun styleMapTapPills() {
            btnOriginPin.apply {
                text = "Tap Map: Pickup Pin"
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (mapTapMode == "ORIGIN") AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_PRIMARY))
                background = AlistoUiKit.roundedBg(
                    this@CustomerMainActivity,
                    if (mapTapMode == "ORIGIN") AlistoUiKit.EMERALD else "#E60D1424", 20,
                    if (mapTapMode == "ORIGIN") AlistoUiKit.EMERALD else AlistoUiKit.BORDER_LIGHT
                )
                setPadding(dp(11), dp(7), dp(11), dp(7))
                elevation = dp(4).toFloat()
            }
            btnStopPin.apply {
                text = "Tap Map: +Stop"
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(if (mapTapMode == "WAYPOINT") AlistoUiKit.BG_DEEP else AlistoUiKit.TEXT_PRIMARY))
                background = AlistoUiKit.roundedBg(
                    this@CustomerMainActivity,
                    if (mapTapMode == "WAYPOINT") AlistoUiKit.AMBER else "#E60D1424", 20,
                    if (mapTapMode == "WAYPOINT") AlistoUiKit.AMBER else AlistoUiKit.BORDER_LIGHT
                )
                setPadding(dp(11), dp(7), dp(11), dp(7))
                elevation = dp(4).toFloat()
            }
        }
        styleMapTapPills()
        btnOriginPin.setOnClickListener { mapTapMode = "ORIGIN"; styleMapTapPills() }
        btnStopPin.setOnClickListener { mapTapMode = "WAYPOINT"; styleMapTapPills() }

        mapControlsRow.addView(btnOriginPin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        if (selectedVerticalKey != "skills_marketplace") {
            mapControlsRow.addView(btnStopPin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        mapStage.addView(mapControlsRow)
        root.addView(mapStage)

        // Rounded Bottom Sheet for Pickup Selection & Booking Submission
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

        // Pickup / Job Site Card with 3 direct methods: Search Landmark, Use Current Location, or Drop Pin
        val pickupCard = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 14)
        val pickupLabel = if (selectedVerticalKey == "skills_marketplace") "Job Site Location" else "Pickup Location"
        val pickupInput = AlistoUiKit.inputField(this, "Enter address or search landmark...", InputType.TYPE_CLASS_TEXT, draftPickupAddress)
        activePickupField = pickupInput
        pickupCard.addView(AlistoUiKit.labeledField(this, "● $pickupLabel", pickupInput, 8))

        val quickLocRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Search Landmark", true, AlistoUiKit.BG_INPUT, AlistoUiKit.CYAN) {
                openLandmarkSearchDialog(forWaypoint = false) { picked ->
                    activeOriginLat = picked.lat
                    activeOriginLng = picked.lng
                    originPinnedManually = true
                    draftPickupAddress = "${picked.title} — ${picked.subtitle}"
                    pickupInput.setText(draftPickupAddress)
                    customerMapView?.recenter(picked.lat, picked.lng, 16)
                    refreshCustomerMapLayers(user)
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })

            addView(AlistoUiKit.chipButton(this@CustomerMainActivity, "Current Location", true, AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD) {
                snapPickupToCurrentGps()
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        }
        pickupCard.addView(quickLocRow)

        val titleHint = when {
            selectedJobSubtype == "ERRAND_PURCHASING" -> "Items to buy or errands to run..."
            selectedVerticalKey == "delivery_logistics" -> "Package details & drop-off note..."
            selectedVerticalKey == "ride_hailing" -> "Destination / landmark note..."
            else -> "Describe work needed (e.g., Aircon cleaning, Plumbing)"
        }
        val titleInput = AlistoUiKit.inputField(this, titleHint, InputType.TYPE_CLASS_TEXT, draftTitle)
        pickupCard.addView(AlistoUiKit.labeledField(this, "◆ Request Details", titleInput, 8))

        val waypointsStatus = TextView(this).apply {
            text = if (sequentialWaypoints.isEmpty()) {
                if (selectedVerticalKey == "skills_marketplace") "Drop pin on map anytime to fine-tune job site."
                else "Search or tap map (+Stop) to add destination / pabili stops."
            } else {
                "Stops (${sequentialWaypoints.size}): " + sequentialWaypoints.joinToString(" → ") {
                    "#${it.getInt("stop_number")} ${it.getString("address")}"
                }
            }
            textSize = 12f
            setTextColor(Color.parseColor(if (sequentialWaypoints.isEmpty()) AlistoUiKit.TEXT_MUTED else AlistoUiKit.AMBER))
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        pickupCard.addView(waypointsStatus)

        if (selectedVerticalKey != "skills_marketplace") {
            val wpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            wpRow.addView(AlistoUiKit.chipButton(this, "+ Search Stop / Drop-Off", true, AlistoUiKit.BG_INPUT, AlistoUiKit.AMBER) {
                draftTitle = titleInput.text.toString()
                draftPickupAddress = pickupInput.text.toString()
                openLandmarkSearchDialog(forWaypoint = true) { picked ->
                    val stopNum = sequentialWaypoints.size + 1
                    sequentialWaypoints.add(JSONObject().apply {
                        put("stop_number", stopNum); put("lat", picked.lat); put("lng", picked.lng)
                        put("address", "${picked.title}, ${picked.subtitle}")
                    })
                    customerMapView?.recenter(picked.lat, picked.lng, 15)
                    showAuthenticatedCustomerPortal()
                }
            }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) } })

            if (sequentialWaypoints.isNotEmpty()) {
                wpRow.addView(AlistoUiKit.chipButton(this, "Clear", false, AlistoUiKit.BG_INPUT, AlistoUiKit.ROSE) {
                    sequentialWaypoints.clear()
                    draftTitle = titleInput.text.toString()
                    draftPickupAddress = pickupInput.text.toString()
                    showAuthenticatedCustomerPortal()
                })
            }
            pickupCard.addView(wpRow)
        }
        sheetBody.addView(pickupCard)

        // Offer & Tip Row
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
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
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

        sheetBody.addView(
            AlistoUiKit.pillButton(this, "Confirm & Broadcast Request", AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP, heightDp = 50, textSizeSp = 15f) {
                val title = titleInput.text.toString().ifBlank { selectedServiceLabel }
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
                        skillCategory = selectedSkillCategory,
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
                        bookingStep = "LAUNCHER"
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

        // Load map centered on live GPS if available, otherwise current origin pin
        val mapLat = if (originPinnedManually) activeOriginLat else (AlistoLocationTracker.lastLat ?: activeOriginLat)
        val mapLng = if (originPinnedManually) activeOriginLng else (AlistoLocationTracker.lastLng ?: activeOriginLng)

        customerMapView?.loadInteractiveMap(mapLat, mapLng, enableTapToPin = true) { lat, lng ->
            val rev = AlistoSpatialEngine.reverseGeocodePin(this@CustomerMainActivity, lat, lng)
            if (mapTapMode == "ORIGIN") {
                activeOriginLat = lat
                activeOriginLng = lng
                originPinnedManually = true
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
            refreshCustomerMapLayers(user)
        }
        refreshCustomerMapLayers(user)
        AlistoLocationTracker.lastLat?.let { lat ->
            AlistoLocationTracker.lastLng?.let { lng ->
                customerMapView?.updateUserLocation(lat, lng, AlistoLocationTracker.lastAccuracyM, flyTo = false)
            }
        }

        return root
    }

    private fun refreshCustomerMapLayers(user: AuthenticatedUser) {
        customerMapView?.updateMapLayers(
            originLat = activeOriginLat,
            originLng = activeOriginLng,
            originAddress = draftPickupAddress.ifBlank { user.addressText },
            waypoints = sequentialWaypoints,
            bookings = AlistoKernelClient.bookings.filter { user.role == "super_admin" || it.clientId == user.id },
            telemetryPins = AlistoKernelClient.liveTelemetryPins
        )
    }

    private fun startLiveGpsOnMap(forceCenter: Boolean, snapOriginToGps: Boolean) {
        if (!AlistoLocationTracker.hasPermission(this)) return
        AlistoLocationTracker.startLiveUpdates(this) { lat, lng, acc ->
            val shouldFly = forceCenter || !hasAutoCenteredGps
            customerMapView?.updateUserLocation(lat, lng, acc, flyTo = shouldFly)
            if (shouldFly) hasAutoCenteredGps = true

            if (snapOriginToGps || !originPinnedManually) {
                activeOriginLat = lat
                activeOriginLng = lng
                if (snapOriginToGps) {
                    val rev = AlistoSpatialEngine.reverseGeocodePin(this, lat, lng)
                    draftPickupAddress = rev
                    activePickupField?.setText(rev)
                }
                AlistoKernelClient.currentSessionUser?.let { refreshCustomerMapLayers(it) }
            }
        }
    }

    private fun snapPickupToCurrentGps() {
        if (!AlistoLocationTracker.hasPermission(this)) {
            AlistoLocationTracker.requestPermission(this)
            return
        }
        val fix = AlistoLocationTracker.getBestLastKnown(this)
        val lat = fix?.latitude ?: AlistoLocationTracker.lastLat
        val lng = fix?.longitude ?: AlistoLocationTracker.lastLng
        if (lat != null && lng != null) {
            val acc = fix?.accuracy ?: AlistoLocationTracker.lastAccuracyM
            activeOriginLat = lat
            activeOriginLng = lng
            originPinnedManually = true
            val rev = AlistoSpatialEngine.reverseGeocodePin(this, lat, lng)
            draftPickupAddress = rev
            activePickupField?.setText(rev)
            customerMapView?.updateUserLocation(lat, lng, acc, flyTo = true)
            AlistoKernelClient.currentSessionUser?.let { refreshCustomerMapLayers(it) }
            Toast.makeText(this, "Pickup set to your GPS: $rev", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Acquiring live GPS fix...", Toast.LENGTH_SHORT).show()
            startLiveGpsOnMap(forceCenter = true, snapOriginToGps = true)
        }
    }

    private fun openLandmarkSearchDialog(
        forWaypoint: Boolean,
        onPicked: (LandmarkResult) -> Unit
    ) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.parseColor(AlistoUiKit.BG_SURFACE))
        }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val queryInput = AlistoUiKit.inputField(this, "e.g., SM City Clark, MarQuee Mall, Balibago...")
        searchRow.addView(queryInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })

        val resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val statusLabel = TextView(this).apply {
            text = "Type a landmark, street, or subdivision and tap Search."
            textSize = 12f
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }

        lateinit var dialog: AlertDialog

        searchRow.addView(AlistoUiKit.pillButton(this, "Search", AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP, heightDp = 44, textSizeSp = 13f) {
            val q = queryInput.text.toString().trim()
            if (q.length < 2) {
                Toast.makeText(this, "Enter at least 2 characters", Toast.LENGTH_SHORT).show()
                return@pillButton
            }
            statusLabel.text = "Searching Philippine landmarks..."
            resultsBox.removeAllViews()
            uiScope.launch {
                val biasLat = AlistoLocationTracker.lastLat ?: activeOriginLat
                val biasLng = AlistoLocationTracker.lastLng ?: activeOriginLng
                val hits = AlistoLocationTracker.searchLandmarks(this@CustomerMainActivity, q, biasLat, biasLng)
                if (hits.isEmpty()) {
                    statusLabel.text = "No matches found for \"$q\". Try a nearby mall, road, or barangay."
                } else {
                    statusLabel.text = "Tap a result to drop the pin:"
                    hits.forEach { hit ->
                        val itemCard = AlistoUiKit.surfaceCard(this@CustomerMainActivity, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER_LIGHT, padDp = 12)
                        itemCard.addView(TextView(this@CustomerMainActivity).apply {
                            text = hit.title
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                        })
                        itemCard.addView(TextView(this@CustomerMainActivity).apply {
                            text = hit.subtitle
                            textSize = 11.5f
                            setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                            maxLines = 2
                            setPadding(0, dp(2), 0, 0)
                        })
                        itemCard.setOnClickListener {
                            dialog.dismiss()
                            onPicked(hit)
                        }
                        resultsBox.addView(itemCard)
                    }
                }
            }
        })

        layout.addView(searchRow)
        layout.addView(statusLabel)
        val scrollResults = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240))
            addView(resultsBox)
        }
        layout.addView(scrollResults)

        dialog = AlertDialog.Builder(this)
            .setTitle(if (forWaypoint) "Search Stop / Drop-Off Landmark" else "Search Pickup / Job Site Landmark")
            .setView(layout)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun buildCustomerActivityTab(user: AuthenticatedUser): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
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

    private fun buildCustomerAccountTab(user: AuthenticatedUser): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
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
            realtimePollJob?.cancel(); AlistoLocationTracker.stopLiveUpdates(); AlistoKernelClient.signOut(); showEntryPortalGate()
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
        card.addView(TextView(this).apply { text = b.pickupAddress; textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(3), 0, dp(10)) })

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "PHP ${"%.2f".format(b.quotedFee)}", AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
            if (b.tipAmount > 0) addView(AlistoUiKit.badgePill(this@CustomerMainActivity, "+PHP ${"%.0f".format(b.tipAmount)} Tip", AlistoUiKit.BG_INPUT, AlistoUiKit.AMBER))
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
                AlistoKernelClient.currentSessionUser?.let { refreshCustomerMapLayers(it) }
                if (fired > 0) Toast.makeText(this@CustomerMainActivity, "$fired new live update(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        realtimePollJob?.cancel()
        AlistoLocationTracker.stopLiveUpdates()
        uiScope.cancel()
    }
}
