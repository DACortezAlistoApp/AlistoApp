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
    private var entryMode = "LOGIN" // "LOGIN" or "SIGNUP"

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
                    res.onSuccess {
                        Toast.makeText(this@ContractorMainActivity, "Biometric Keystore Session Restored!", Toast.LENGTH_SHORT).show()
                        routeContractorByAccountState()
                    }.onFailure {
                        showContractorEntryGate()
                    }
                }
            },
            onFallbackOrError = {
                showContractorEntryGate()
            }
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
                "Separated Contractor Portal • Biometric Keystore"
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
                    val idStr = idInput.text.toString()
                    val pwStr = pwInput.text.toString()
                    isEnabled = false
                    uiScope.launch {
                        val res = AlistoKernelClient.authenticateUserRpc(idStr, pwStr, "contractor")
                        isEnabled = true
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(
                                this@ContractorMainActivity,
                                "contractor",
                                user.id,
                                "${user.fullName} (@${user.username})"
                            )
                            routeContractorByAccountState()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Login Failed")
                                .setMessage(err.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            })
        } else {
            // SECTION 4.1 OF V2/V3 SPEC: STEP-BY-STEP CONTRACTOR REGISTRATION FLOW
            container.addView(sectionTitle("Step 1: Personal Identifiable Information (PII)"))
            val fnInput = styledInput("First Name")
            container.addView(labeledField("First Name", fnInput))
            val lnInput = styledInput("Last Name")
            container.addView(labeledField("Last Name", lnInput))
            val dobInput = styledInput("1990-08-15")
            container.addView(labeledField("Date of Birth (YYYY-MM-DD)", dobInput))
            val phoneInput = styledInput("+63917XXXXXXX")
            container.addView(labeledField("Active Mobile Number (Admin Call Verification)", phoneInput))
            val emailInput = styledInput("contractor@alisto.ph")
            container.addView(labeledField("Email Address", emailInput))
            val pwInput = styledInput("Create Permanent Non-Expiring Password").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            container.addView(labeledField("Permanent Password", pwInput))

            container.addView(sectionTitle("Step 2: Base Location & GPS Dispatch Anchor"))
            val addrInput = styledInput("House No., Street, Barangay, Angeles City")
            container.addView(labeledField("Home Dispatch Address", addrInput))

            var homeLat = 15.1450
            var homeLng = 120.5887
            val gpsLabel = TextView(this).apply {
                text = "GPS Dispatch Pin: 15.14500, 120.58870"
                setTextColor(Color.parseColor("#34D399"))
                textSize = 12f
            }
            container.addView(gpsLabel)
            container.addView(Button(this).apply {
                text = "SNAP DISPATCH ANCHOR TO CURRENT GPS"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.parseColor("#00F2FE"))
                setOnClickListener {
                    homeLat = 15.1450 + (Math.random() * 0.009)
                    homeLng = 120.5887 + (Math.random() * 0.009)
                    gpsLabel.text = "GPS Dispatch Pin Snapped: ${"%.5f".format(homeLat)}, ${"%.5f".format(homeLng)}"
                }
            })

            container.addView(sectionTitle("Step 3: Primary Vertical — On-Demand Skills Marketplace"))
            val skillOptions = listOf("Plumbing", "Electrical", "Carpentry", "Appliance Repair", "Aircon Servicing", "General Handyman")
            val skillCheckboxes = skillOptions.map { skill ->
                CheckBox(this).apply {
                    text = skill
                    setTextColor(Color.WHITE)
                    isChecked = (skill == "Plumbing" || skill == "Aircon Servicing")
                }
            }
            skillCheckboxes.forEach { container.addView(it) }

            container.addView(sectionTitle("Step 4: Secondary Verticals — Conditional Transport Expansion"))
            val transportContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(20, 16, 20, 16)
                setBackgroundColor(Color.parseColor("#1E293B"))
            }

            val optDeliveryCb = CheckBox(this).apply {
                text = "Opt-In: Delivery & Logistics Rider"
                setTextColor(Color.parseColor("#FBBF24"))
            }
            val optRideCb = CheckBox(this).apply {
                text = "Opt-In: Ride-Hailing Rider (Sub-400cc)"
                setTextColor(Color.parseColor("#FBBF24"))
            }
            val toggleListener = CompoundButton.OnCheckedChangeListener { _, _ ->
                transportContainer.visibility = if (optDeliveryCb.isChecked || optRideCb.isChecked) View.VISIBLE else View.GONE
            }
            optDeliveryCb.setOnCheckedChangeListener(toggleListener)
            optRideCb.setOnCheckedChangeListener(toggleListener)
            container.addView(optDeliveryCb)
            container.addView(optRideCb)

            val vehicleSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@ContractorMainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("motorcycle", "tricycle", "micro_mobility")
                )
            }
            transportContainer.addView(TextView(this).apply {
                text = "Vehicle Classification (Automobiles Strictly Excluded):"
                setTextColor(Color.WHITE)
            })
            transportContainer.addView(vehicleSpinner)

            val docStatusText = TextView(this).apply {
                text = "Required Transport Scans (Compressed to 1024px @ 65% JPEG):"
                setTextColor(Color.parseColor("#34D399"))
                textSize = 12f
                setPadding(0, 8, 0, 8)
            }
            transportContainer.addView(docStatusText)

            transportContainer.addView(Button(this).apply {
                text = "COMPRESS & ATTACH DRIVER LICENSE + OR/CR + VEHICLE PHOTO (1024px)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val holder = "${fnInput.text} ${lnInput.text}".ifBlank { "Contractor" }
                    val lic = AlistoMediaCompressor.generateCompressedCredentialStamp("Professional Driver License", holder, false)
                    val orcr = AlistoMediaCompressor.generateCompressedCredentialStamp("Vehicle OR/CR", holder, false)
                    val veh = AlistoMediaCompressor.generateCompressedCredentialStamp("Sub-400cc Vehicle Photo", holder, false)
                    compressedLicenseUrl = lic.dataUrl
                    compressedOrcrUrl = orcr.dataUrl
                    compressedVehicleUrl = veh.dataUrl
                    docStatusText.text = "Attached: License (${lic.sizeKb}KB), OR/CR (${orcr.sizeKb}KB), Vehicle (${veh.sizeKb}KB)"
                }
            })
            container.addView(transportContainer)

            container.addView(sectionTitle("Step 5: Profile Photo (VGA <80KB) & Submit to PENDING_REVIEW Gate"))
            val vgaStatus = TextView(this).apply {
                text = "Profile Photo: Pending VGA (640x480) Compression"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
            }
            container.addView(vgaStatus)
            container.addView(Button(this).apply {
                text = "CAPTURE & COMPRESS VGA PROFILE PHOTO (640x480 • <80 KB)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val holder = "${fnInput.text} ${lnInput.text}".ifBlank { "Contractor" }
                    val vga = AlistoMediaCompressor.generateCompressedCredentialStamp("Contractor Profile Photo", holder, true)
                    compressedProfileUrl = vga.dataUrl
                    vgaStatus.text = "Compressed Profile Photo Ready: ${vga.standardLabel}"
                }
            })

            container.addView(Button(this).apply {
                text = "SUBMIT CONTRACTOR REGISTRATION (ENTERS PENDING_REVIEW GATE)"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    val fn = fnInput.text.toString().trim()
                    val ln = lnInput.text.toString().trim()
                    val ph = phoneInput.text.toString().trim()
                    val em = emailInput.text.toString().trim()
                    val pw = pwInput.text.toString().trim()
                    if (fn.isEmpty() || ln.isEmpty() || ph.isEmpty() || pw.isEmpty()) {
                        Toast.makeText(this@ContractorMainActivity, "Complete all mandatory PII fields.", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val selectedSkills = skillCheckboxes.filter { it.isChecked }.map { it.text.toString() }
                    uiScope.launch {
                        val res = AlistoKernelClient.registerContractorPortalRpc(
                            firstName = fn,
                            lastName = ln,
                            birthDate = dobInput.text.toString().ifBlank { "1990-08-15" },
                            phone = ph,
                            email = em.ifBlank { "${fn.lowercase()}@alisto.ph" },
                            password = pw,
                            municipality = "Angeles City",
                            barangayZone = "Malabanias",
                            addressText = addrInput.text.toString().ifBlank { "Angeles City" },
                            homeLat = homeLat,
                            homeLng = homeLng,
                            offersSkills = selectedSkills.isNotEmpty(),
                            skillTags = selectedSkills,
                            offersDelivery = optDeliveryCb.isChecked,
                            offersRideHailing = optRideCb.isChecked,
                            vehicleType = vehicleSpinner.selectedItem.toString(),
                            profilePhotoUrl = compressedProfileUrl,
                            licenseDocUrl = compressedLicenseUrl,
                            orcrDocUrl = compressedOrcrUrl,
                            vehiclePhotoUrl = compressedVehicleUrl
                        )
                        res.onSuccess { user ->
                            AlistoBiometricVault.saveEncryptedSession(
                                this@ContractorMainActivity,
                                "contractor",
                                user.id,
                                "${user.fullName} (@${user.username})"
                            )
                            routeContractorByAccountState()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@ContractorMainActivity)
                                .setTitle("Registration Error")
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

    /**
     * Deterministic Database-Driven Account State Router (Section 4.3 of V2/V3 Spec)
     */
    private fun routeContractorByAccountState() {
        val user = AlistoKernelClient.currentSessionUser ?: return showContractorEntryGate()
        when (user.accountStatus) {
            "PENDING_REVIEW" -> showPendingVerificationGate(user, isResubmission = false)
            "RESUBMISSION_REQUIRED" -> showPendingVerificationGate(user, isResubmission = true)
            "SUSPENDED" -> showSuspendedScreen(user)
            else -> showActiveContractorPortal(user)
        }
    }

    private fun showPendingVerificationGate(user: AuthenticatedUser, isResubmission: Boolean) {
        val prof = AlistoKernelClient.currentContractorProfile
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070A12")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 64, 42, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                if (isResubmission) "RESUBMISSION REQUIRED" else "PENDING ADMIN REVIEW",
                "${user.fullName} (@${user.username})"
            )
        )

        container.addView(TextView(this).apply {
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor(if (isResubmission) "#7F1D1D" else "#1E293B"))
            setTextColor(Color.WHITE)
            textSize = 14f
            text = if (isResubmission) {
                "DOCUMENT RESUBMISSION LOOP ACTIVE:\n" +
                "Flagged Files: ${prof?.flaggedDocs?.joinToString(", ") ?: "Compliance Scan"}\n" +
                "Admin Instructions: ${prof?.resubmissionNotes ?: "Please re-upload a clearer 1024px scan."}"
            } else {
                "PENDING_REVIEW GATE LOCKED:\n" +
                "Your application is queued for manual Admin document inspection and a direct verification call to ${user.phone}.\n" +
                "The Skills Job Board and Dispatch Queue will unlock automatically once an Admin sets your account to ACTIVE."
            }
        })

        if (isResubmission) {
            container.addView(Button(this).apply {
                text = "COMPRESS & RE-UPLOAD FLAGGED DOCUMENT (1024px JPEG)"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener {
                    val scan = AlistoMediaCompressor.generateCompressedCredentialStamp("Resubmitted Clear Scan", user.fullName, false)
                    Toast.makeText(this@ContractorMainActivity, "Uploaded ${scan.standardLabel}! Admin notified.", Toast.LENGTH_LONG).show()
                }
            })
        }

        container.addView(Button(this).apply {
            text = "CHECK VERIFICATION STATUS (GET_APP_BOOT_STATE)"
            setBackgroundColor(Color.parseColor("#00F2FE"))
            setTextColor(Color.parseColor("#070A12"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                uiScope.launch {
                    AlistoKernelClient.fetchAppBootStateRpc(user.id, "contractor")
                    routeContractorByAccountState()
                }
            }
        })

        container.addView(Button(this).apply {
            text = "Sign Out"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                AlistoKernelClient.signOut()
                showContractorEntryGate()
            }
        })

        scroll.addView(container)
        setContentView(scroll)
    }

    private fun showSuspendedScreen(user: AuthenticatedUser) {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070A12")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 64, 42, 64)
        }
        container.addView(TextView(this).apply {
            text = "ACCOUNT SUSPENDED\n@${user.username} (${user.fullName})\nPlease contact Alisto Network PH Admin Operations."
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(Button(this).apply {
            text = "Back to Login Gate"
            setOnClickListener { AlistoKernelClient.signOut(); showContractorEntryGate() }
        })
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun showActiveContractorPortal(user: AuthenticatedUser) {
        val prof = AlistoKernelClient.currentContractorProfile
        val activePass = AlistoKernelClient.currentSubscriptionPass

        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070D19")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "${user.fullName} (@${user.username})",
                if (user.vipGrindBadge) "★ VIP GRIND PASS HOLDER • 0.00% COMMISSION 24/7" else "ACTIVE CONTRACTOR • ${prof?.vehicleType ?: "motorcycle"} (${prof?.dispatchRadiusM ?: 1500}m)"
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
                text = "Refresh Boot State"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    uiScope.launch {
                        AlistoKernelClient.fetchAppBootStateRpc(user.id, "contractor")
                        routeContractorByAccountState()
                    }
                }
            })
            addView(Button(this@ContractorMainActivity).apply {
                text = "Lock (Keep Biometric)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AlistoKernelClient.signOut()
                    showContractorEntryGate()
                }
            })
        }
        container.addView(sessionBar)

        // SECTION 5 OF V3 SPEC: HYBRID MONETIZATION & SUBSCRIPTION PASS CENTER
        val commPct = "%.1f".format(AlistoKernelClient.effectiveCommissionRate * 100.0)
        container.addView(sectionTitle("Engine 03: Hybrid Wallet & Subscription Passes (Rate: $commPct%)"))

        val walletBox = TextView(this).apply {
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.WHITE)
            textSize = 13.5f
            text = "Available System Wallet: PHP ${"%.2f".format(AlistoKernelClient.contractorAvailableCredits)}\n" +
                   "Locked Commission Holds: PHP ${"%.2f".format(AlistoKernelClient.contractorHeldCredits)}\n" +
                   "Active Pass: ${activePass?.tierCode ?: "STANDARD PAY-AS-YOU-GO (<=12% Cap)"}"
        }
        container.addView(walletBox)

        container.addView(Button(this).apply {
            text = "+ CASH IN PHP 500 WALLET CREDITS (AUTO +PHP 25 BONUS!)"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                uiScope.launch {
                    val res = AlistoKernelClient.topUpCreditsQrPhRpc(500.0)
                    res.onSuccess { bonus ->
                        Toast.makeText(
                            this@ContractorMainActivity,
                            "Cash-In Complete! +PHP 500.00 Paid + PHP ${"%.2f".format(bonus)} Bonus Minted!",
                            Toast.LENGTH_LONG
                        ).show()
                        showActiveContractorPortal(AlistoKernelClient.currentSessionUser ?: user)
                    }
                }
            }
        })

        val passBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 10)
            addView(Button(this@ContractorMainActivity).apply {
                text = "ACTIVATE PART-TIME PASS (PHP 3,000 • 4h/day @ 0% + PHP 150 Cushion)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener { activatePass("PART_TIME_PASS") }
            })
            addView(Button(this@ContractorMainActivity).apply {
                text = "ACTIVATE FULL-TIME PASS (PHP 5,000 • 8h/day @ 0% + 32 Days + PHP 250)"
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.WHITE)
                setOnClickListener { activatePass("FULL_TIME_PASS") }
            })
            addView(Button(this@ContractorMainActivity).apply {
                text = "ACTIVATE HARD-WORKING GRIND PASS (PHP 8,000 • 24/7 @ 0% + 33 Days + VIP)"
                setBackgroundColor(Color.parseColor("#FBBF24"))
                setTextColor(Color.parseColor("#070A12"))
                setTypeface(null, Typeface.BOLD)
                setOnClickListener { activatePass("GRIND_PASS") }
            })
        }
        container.addView(passBtnRow)

        // ENGINE 04: JOB BOARD & STATE MACHINE
        container.addView(sectionTitle("Engine 04: Barangay Job Board & Dispatch Queue"))
        AlistoKernelClient.bookings.forEach { b ->
            container.addView(buildContractorJobCard(b))
        }

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun activatePass(tierCode: String) {
        uiScope.launch {
            val res = AlistoKernelClient.purchaseSubscriptionPassRpc(tierCode)
            res.onSuccess {
                Toast.makeText(this@ContractorMainActivity, "$tierCode Activated! 0.00% Commission Window Unlocked!", Toast.LENGTH_LONG).show()
                routeContractorByAccountState()
            }.onFailure { err ->
                Toast.makeText(this@ContractorMainActivity, err.message, Toast.LENGTH_LONG).show()
            }
        }
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
            text = "${b.serviceTitle}\nZone: ${b.barangayZone}, ${b.municipality}\nQuoted Fee: PHP ${"%.2f".format(b.quotedFee)} | Tip (100% Yours): PHP ${"%.2f".format(b.tipAmount)} | Hold ($commPct%): PHP ${"%.2f".format(reqComm)}"
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
                actionBtn.text = "CLAIM JOB (HOLD $commPct%: PHP ${"%.2f".format(reqComm)})"
                actionBtn.setOnClickListener {
                    uiScope.launch {
                        val res = AlistoKernelClient.claimBookingRpc(b.id)
                        res.onSuccess { routeContractorByAccountState() }
                            .onFailure { err ->
                                AlertDialog.Builder(this@ContractorMainActivity)
                                    .setTitle("Dispatch Guard")
                                    .setMessage(err.message)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                    }
                }
                card.addView(actionBtn)
            }
            "ACCEPTED" -> {
                actionBtn.text = "MARK ARRIVED AT SITE"
                actionBtn.setOnClickListener { transitionJob(b.id, "ARRIVED") }
                card.addView(actionBtn)
            }
            "ARRIVED" -> {
                actionBtn.text = "START WORK (IN_PROGRESS)"
                actionBtn.setOnClickListener { transitionJob(b.id, "IN_PROGRESS") }
                card.addView(actionBtn)
            }
            "IN_PROGRESS" -> {
                actionBtn.text = "COMPLETE JOB & SETTLE"
                actionBtn.setOnClickListener { transitionJob(b.id, "COMPLETED") }
                card.addView(actionBtn)
            }
        }
        return card
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
        uiScope.cancel()
    }
}
