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
import ph.alisto.kernel.*

class CustomerMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectedVerticalKey = "skills_marketplace"
    private var entryMode = "LOGIN" // "LOGIN" or "SIGNUP"
    private var compressedProfilePhotoDataUrl: String? = null
    private var compressedProfilePhotoMeta: String = "Optional (Not Attached)"

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
                    res.onSuccess {
                        Toast.makeText(this@CustomerMainActivity, "Biometric Keystore Session Restored!", Toast.LENGTH_SHORT).show()
                        showAuthenticatedCustomerPortal()
                    }.onFailure {
                        showEntryPortalGate()
                    }
                }
            },
            onFallbackOrError = {
                showEntryPortalGate()
            }
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
                "Separated Customer Portal • Biometric Keystore"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24) }
            }
        )

        // Biometric Quick Unlock Banner if Keystore Session Exists
        if (AlistoBiometricVault.hasSavedSession(this, "customer")) {
            val bioBtn = Button(this).apply {
                text = "BIOMETRIC QUICK UNLOCK (${AlistoBiometricVault.getSavedUserLabel(this@CustomerMainActivity, "customer")})"
                setBackgroundColor(Color.parseColor("#10B981"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener { triggerFrictionlessBiometricEntry() }
            }
            container.addView(bioBtn)
        }

        // Mode Switcher (Log In vs. Customer Sign-Up)
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 24)
            addView(Button(this@CustomerMainActivity).apply {
                text = "LOG IN"
                setBackgroundColor(Color.parseColor(if (entryMode == "LOGIN") "#00F2FE" else "#1E293B"))
                setTextColor(Color.parseColor(if (entryMode == "LOGIN") "#070A12" else "#FFFFFF"))
                setOnClickListener { entryMode = "LOGIN"; showEntryPortalGate() }
            })
            addView(Button(this@CustomerMainActivity).apply {
                text = "NEW CUSTOMER SIGN UP"
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
                    val idStr = idInput.text.toString()
                    val pwStr = pwInput.text.toString()
                    isEnabled = false
                    uiScope.launch {
                        val res = AlistoKernelClient.authenticateUserRpc(idStr, pwStr, "customer")
                        isEnabled = true
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(
                                this@CustomerMainActivity,
                                "customer",
                                user.id,
                                "${user.nickname} (@${user.username})"
                            )
                            showAuthenticatedCustomerPortal()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity)
                                .setTitle("Authentication Denied")
                                .setMessage(err.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            })
        } else {
            // SECTION 3 OF V2/V3 SPEC: CUSTOMER ONBOARDING & SIGN-UP WORKFLOW
            val fullNameInput = styledInput("Legal Full Name (e.g., Clarisse Dizon)")
            container.addView(labeledField("Full Name (Mandatory)", fullNameInput))

            val nicknameInput = styledInput("Preferred Nickname (e.g., Ate Clarisse)")
            container.addView(labeledField("Preferred Nickname for Contractor Greetings (Mandatory)", nicknameInput))

            val phoneInput = styledInput("+63917XXXXXXX")
            container.addView(labeledField("Mobile Phone Number (Mandatory — Must remain active for direct contractor coordination calls)", phoneInput))

            val emailInput = styledInput("customer@email.com")
            container.addView(labeledField("Email Address (Mandatory — Digital Receipts & Password Reset)", emailInput))

            val pwInput = styledInput("Create Permanent Non-Expiring Password").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            container.addView(labeledField("Permanent Password (Mandatory)", pwInput))

            val addrInput = styledInput("House No., Street, Subdivision, Barangay, Angeles City")
            container.addView(labeledField("Registered Address & Map Pin (Mandatory)", addrInput))

            val gpsCoordText = TextView(this).apply {
                text = "GPS Home Pin Anchor: 15.14500, 120.58870 (Angeles City)"
                setTextColor(Color.parseColor("#34D399"))
                textSize = 12f
                setPadding(0, 0, 0, 8)
            }
            container.addView(gpsCoordText)

            var homeLat = 15.1450
            var homeLng = 120.5887

            container.addView(Button(this).apply {
                text = "USE CURRENT GPS LOCATION (SNAP HOME PIN)"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.parseColor("#00F2FE"))
                setOnClickListener {
                    homeLat = 15.1450 + (Math.random() * 0.009)
                    homeLng = 120.5887 + (Math.random() * 0.009)
                    gpsCoordText.text = "GPS Home Pin Snapped: ${"%.5f".format(homeLat)}, ${"%.5f".format(homeLng)}"
                }
            })

            val photoAdvisory = TextView(this).apply {
                text = "Optional Profile Picture: Helps assigned contractors easily identify you at pickup points or job sites ($compressedProfilePhotoMeta)."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                setPadding(0, 16, 0, 8)
            }
            container.addView(photoAdvisory)

            container.addView(Button(this).apply {
                text = "ATTACH VGA PROFILE PHOTO (640x480 • <80 KB COMPRESSED)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val asset = AlistoMediaCompressor.generateCompressedCredentialStamp(
                        "Customer Profile Photo",
                        fullNameInput.text.toString().ifBlank { "Customer" },
                        isProfilePhoto = true
                    )
                    compressedProfilePhotoDataUrl = asset.dataUrl
                    compressedProfilePhotoMeta = asset.standardLabel
                    photoAdvisory.text = "Profile Photo Compressed & Attached: ${asset.standardLabel}"
                }
            })

            container.addView(Button(this).apply {
                text = "COMPLETE CUSTOMER SIGN-UP & BIND BIOMETRIC KEYSTORE"
                setBackgroundColor(Color.parseColor("#00F2FE"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    val fn = fullNameInput.text.toString().trim()
                    val nick = nicknameInput.text.toString().trim()
                    val ph = phoneInput.text.toString().trim()
                    val em = emailInput.text.toString().trim()
                    val pw = pwInput.text.toString().trim()
                    val ad = addrInput.text.toString().trim()

                    if (fn.isEmpty() || nick.isEmpty() || ph.isEmpty() || em.isEmpty() || pw.isEmpty()) {
                        Toast.makeText(this@CustomerMainActivity, "Please complete all mandatory customer fields.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }

                    uiScope.launch {
                        val res = AlistoKernelClient.registerCustomerPortalRpc(
                            fullName = fn,
                            nickname = nick,
                            phone = ph,
                            email = em,
                            password = pw,
                            municipality = "Angeles City",
                            barangayZone = "Malabanias",
                            addressText = ad.ifEmpty { "Brgy. Malabanias, Angeles City" },
                            homeLat = homeLat,
                            homeLng = homeLng,
                            profilePhotoUrl = compressedProfilePhotoDataUrl
                        )
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(
                                this@CustomerMainActivity,
                                "customer",
                                user.id,
                                "${user.nickname} (@${user.username})"
                            )
                            showAuthenticatedCustomerPortal()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity)
                                .setTitle("Sign-Up Error")
                                .setMessage(err.message)
                                .setPositiveButton("OK", null)
                                .show()
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

        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070D19")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "Hi, ${user.nickname}! (@${user.username})",
                "GPS Home Pin: ${"%.4f".format(user.homeLat)}, ${"%.4f".format(user.homeLng)}"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
            }
        )

        val sessionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
            addView(Button(this@CustomerMainActivity).apply {
                text = "Sync Live Kernel"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    uiScope.launch {
                        AlistoKernelClient.fetchAppBootStateRpc(user.id, "customer")
                        showAuthenticatedCustomerPortal()
                    }
                }
            })
            addView(Button(this@CustomerMainActivity).apply {
                text = "Lock (Keep Biometric)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AlistoKernelClient.signOut()
                    showEntryPortalGate()
                }
            })
            addView(Button(this@CustomerMainActivity).apply {
                text = "Unbind Keystore"
                setBackgroundColor(Color.parseColor("#7F1D1D"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AlistoBiometricVault.clearSavedSession(this@CustomerMainActivity, "customer")
                    AlistoKernelClient.signOut()
                    showEntryPortalGate()
                }
            })
        }
        container.addView(sessionBar)

        container.addView(sectionTitle("1. Select Service Vertical (Engine 10 Rollout Flags)"))
        AlistoKernelClient.verticals.forEach { v ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                val bg = when {
                    !v.isEnabled -> "#111827"
                    selectedVerticalKey == v.key -> "#164E63"
                    else -> "#1E293B"
                }
                setBackgroundColor(Color.parseColor(bg))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }

                setOnClickListener {
                    if (!v.isEnabled) {
                        Toast.makeText(this@CustomerMainActivity, "${v.displayName} is currently locked by SuperAdmin rollout sequence.", Toast.LENGTH_LONG).show()
                    } else {
                        selectedVerticalKey = v.key
                        showAuthenticatedCustomerPortal()
                    }
                }
            }
            card.addView(TextView(this).apply {
                text = "${v.displayName}  [${if (v.isEnabled) "ACTIVE" else "LOCKED"}]"
                setTextColor(if (v.isEnabled) Color.parseColor("#00F2FE") else Color.parseColor("#EF4444"))
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
            })
            card.addView(TextView(this).apply {
                text = v.description
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
            })
            container.addView(card)
        }

        container.addView(sectionTitle("2. Dispatch Service Booking"))
        val titleInput = styledInput("Service Title (e.g., Split-Type Aircon Cleaning)")
        container.addView(labeledField("Service Request Summary", titleInput))

        val pickupInput = styledInput(user.addressText.ifBlank { "${user.barangayZone}, ${user.municipality}" })
        container.addView(labeledField("Pickup / Job Site Address (Synced to GPS Home Pin)", pickupInput))

        val feeInput = styledInput("1200").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Quoted Service Fee (PHP)", feeInput))

        val tipInput = styledInput("100").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Optional Tip (PHP — 100% belongs to Contractor)", tipInput))

        container.addView(Button(this).apply {
            text = "DISPATCH BOOKING TO 11-ENGINE KERNEL"
            setBackgroundColor(Color.parseColor("#00F2FE"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val title = titleInput.text.toString().ifBlank { "On-Demand Household Service" }
                val addr = pickupInput.text.toString().ifBlank { user.addressText }
                val fee = feeInput.text.toString().toDoubleOrNull() ?: 1200.0
                val tip = tipInput.text.toString().toDoubleOrNull() ?: 0.0

                uiScope.launch {
                    val res = AlistoKernelClient.createBookingRpc(
                        verticalKey = selectedVerticalKey,
                        municipality = user.municipality,
                        barangayZone = user.barangayZone,
                        skillCategory = "Aircon & HVAC",
                        serviceTitle = title,
                        pickupAddress = addr,
                        dropoffAddress = "",
                        quotedFee = fee,
                        tipAmount = tip
                    )
                    res.onSuccess {
                        Toast.makeText(this@CustomerMainActivity, "Booking ${it.bookingRef} Dispatched!", Toast.LENGTH_SHORT).show()
                        showAuthenticatedCustomerPortal()
                    }
                }
            }
        })

        container.addView(sectionTitle("3. Active Bookings & Engine 11 Moderated Chat"))
        AlistoKernelClient.bookings.filter { user.role == "super_admin" || it.clientId == user.id }.forEach { b ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                setBackgroundColor(Color.parseColor("#1E293B"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }
            }
            card.addView(TextView(this).apply {
                text = "${b.bookingRef} • [${b.status}] — ${b.serviceTitle}"
                setTextColor(Color.parseColor("#FBBF24"))
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = "Fee: PHP ${b.quotedFee} (+PHP ${b.tipAmount} Tip)"
                setTextColor(Color.WHITE)
            })
            card.addView(Button(this).apply {
                text = "OPEN ENGINE 11 BOOKING CHAT"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener { openChatDialog(b) }
            })
            container.addView(card)
        }

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun openChatDialog(booking: BookingRecord) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val msgInput = styledInput("Type message to contractor...")
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
                            AlertDialog.Builder(this@CustomerMainActivity)
                                .setTitle("Engine 11 Moderation Block")
                                .setMessage(err.message)
                                .setPositiveButton("OK", null)
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

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}
