package ph.alisto.contractor

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.AlistoLocationTracker
import ph.alisto.kernel.AlistoSpatialEngine
import ph.alisto.kernel.AlistoUiKit
import ph.alisto.kernel.AlistoUiKit.dp
import ph.alisto.kernel.AuthenticatedUser
import ph.alisto.kernel.BookingRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ACTIVE_JOB_STATUSES = setOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")

internal fun ContractorMainActivity.findMyActiveBooking(userId: String): BookingRecord? {
    return AlistoKernelClient.bookings.firstOrNull {
        it.contractorId == userId && it.status in ACTIVE_JOB_STATUSES
    }
}

internal fun ContractorMainActivity.distanceToPickupKm(b: BookingRecord, user: AuthenticatedUser): Double {
    val myLat = AlistoLocationTracker.lastLat ?: user.homeLat
    val myLng = AlistoLocationTracker.lastLng ?: user.homeLng
    val res = FloatArray(1)
    Location.distanceBetween(myLat, myLng, b.pickupLat, b.pickupLng, res)
    return (res[0].toDouble() / 1000.0).coerceAtLeast(0.0)
}

internal fun formatDistanceLabel(km: Double): String {
    return if (km < 1.0) "${(km * 1000).toInt()} m away" else "${"%.1f".format(km)} km away"
}

private fun parseWaypointEntries(rawJson: String): MutableList<JSONObject> {
    val list = mutableListOf<JSONObject>()
    try {
        val arr = JSONArray(rawJson.ifBlank { "[]" })
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is JSONObject -> {
                    val addr = item.optString("address", item.optString("label", "")).trim()
                    if (addr.isNotBlank()) {
                        list.add(JSONObject().apply {
                            put("address", addr)
                            if (item.has("lat") && !item.isNull("lat")) put("lat", item.optDouble("lat"))
                            if (item.has("lng") && !item.isNull("lng")) put("lng", item.optDouble("lng"))
                        })
                    }
                }
                is String -> {
                    if (item.isNotBlank()) {
                        list.add(JSONObject().apply { put("address", item.trim()) })
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return list
}

private fun ContractorMainActivity.navigateToPointOrAddress(lat: Double?, lng: Double?, address: String) {
    if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
        AlistoSpatialEngine.launchLocalRoadNavigation(this, lat, lng)
        return
    }
    try {
        val uri = Uri.parse("google.navigation:q=${Uri.encode(address)}")
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
        try {
            val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(address)}")
            startActivity(Intent(Intent.ACTION_VIEW, web))
        } catch (_: Exception) {
            Toast.makeText(this, "No maps app available to navigate.", Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun ContractorMainActivity.geocodeOptional(address: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    try {
        @Suppress("DEPRECATION")
        val hits = Geocoder(this@geocodeOptional, Locale.getDefault()).getFromLocationName("$address, Pampanga, Philippines", 1)
        val first = hits?.firstOrNull()
        if (first != null) first.latitude to first.longitude else null
    } catch (_: Exception) {
        null
    }
}

private fun formatManilaDateTime(isoOrNow: Instant = Instant.now()): String {
    val zone = try { ZoneId.of("Asia/Manila") } catch (_: Exception) { ZoneId.systemDefault() }
    val fmt = DateTimeFormatter.ofPattern("MMM dd, yyyy • hh:mm a", Locale.US)
    return fmt.format(isoOrNow.atZone(zone))
}

private fun computeElapsedLabel(b: BookingRecord): String {
    val startIso = b.startedAt ?: b.onTheWayAt ?: b.acceptedAt ?: return "Just now"
    return try {
        val start = Instant.parse(startIso)
        val dur = Duration.between(start, Instant.now())
        val totalMin = dur.toMinutes().coerceAtLeast(0L)
        val hrs = totalMin / 60L
        val mins = totalMin % 60L
        val secs = (dur.seconds % 60L).coerceAtLeast(0L)
        when {
            hrs > 0 -> "${hrs}h ${mins}m"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    } catch (_: Exception) {
        "Active session"
    }
}

// ==========================================================================
// 1. DEDICATED CURRENT / ACTIVE BOOKING TAB
// ==========================================================================
internal fun ContractorMainActivity.buildContractorActiveBookingTab(user: AuthenticatedUser): View {
    val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(28))
    }

    val activeJob = findMyActiveBooking(user.id)

    container.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        addView(TextView(this@buildContractorActiveBookingTab).apply {
            text = "Active Booking"
            textSize = 22f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val cooldownLeft = ((5_000L - (System.currentTimeMillis() - lastCompletedAtMs)) / 1000L).toInt()
        val badgeText = when {
            activeJob != null -> activeJob.status.replace("_", " ")
            cooldownLeft > 0 -> "Cooldown ${cooldownLeft}s"
            else -> "Standby"
        }
        addView(AlistoUiKit.badgePill(
            this@buildContractorActiveBookingTab,
            badgeText,
            if (activeJob != null) "#0C2D48" else "#1E293B",
            if (activeJob != null) "#38BDF8" else AlistoUiKit.TEXT_MUTED
        ))
    })

    if (activeJob == null) {
        val emptyCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 22)
        emptyCard.addView(TextView(this).apply {
            text = "No Active Booking Right Now"
            textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        })
        emptyCard.addView(TextView(this).apply {
            text = "When you accept an auto-assigned offer or win a booking from the Dispatch Board, full route navigation, editable waypoints, customer profile, and status controls appear right here."
            textSize = 13f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(8), 0, dp(16))
        })
        emptyCard.addView(
            AlistoUiKit.pillButton(this, "Browse Dispatch Board →", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, heightDp = 44, textSizeSp = 13.5f) {
                boardCategory = "OPEN"; activeTab = "JOBS"; showActiveContractorPortal(user)
            }
        )
        container.addView(emptyCard)
        scroll.addView(container)
        return scroll
    }

    val b = activeJob
    val totalCollect = b.quotedFee + b.tipAmount

    // Hero Fare & Reference Card
    val hero = AlistoUiKit.surfaceCard(this, "#0B1D39", "#38BDF8", padDp = 16)
    hero.addView(TextView(this).apply {
        text = "${b.bookingRef}  •  ${b.verticalKey.replace("_", " ").uppercase()}  •  ${b.paymentMethod}"
        textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#93C5FD"))
    })
    hero.addView(TextView(this).apply {
        text = b.serviceTitle
        textSize = 18f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        setPadding(0, dp(4), 0, dp(6))
    })
    hero.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(AlistoUiKit.badgePill(this@buildContractorActiveBookingTab, "Total: PHP ${"%.2f".format(totalCollect)}", "#064E3B", "#6EE7B7").apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
        })
        if (b.tipAmount > 0.0) {
            addView(AlistoUiKit.badgePill(this@buildContractorActiveBookingTab, "Tip: PHP ${"%.0f".format(b.tipAmount)}", "#2A1B08", AlistoUiKit.GOLD_ACCENT).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            })
        }
        addView(AlistoUiKit.badgePill(this@buildContractorActiveBookingTab, "Elapsed: ${computeElapsedLabel(b)}", "#1E293B", "#CBD5E1"))
    })
    container.addView(hero)

    // Tappable Customer Nickname Card -> Opens Detailed Profile Modal
    val custCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#334155", padDp = 14)
    custCard.setOnClickListener { showCustomerProfileModal(b) }
    custCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@buildContractorActiveBookingTab).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@buildContractorActiveBookingTab).apply {
                text = "CUSTOMER (TAP FOR FULL PROFILE)"
                textSize = 10.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
            })
            addView(TextView(this@buildContractorActiveBookingTab).apply {
                text = "${b.clientNickname}  •  Trust ${"%.0f".format(b.clientTrustScore)}%"
                textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                setPadding(0, dp(2), 0, 0)
            })
        })
        addView(AlistoUiKit.badgePill(this@buildContractorActiveBookingTab, "View Profile →", "#1E293B", "#7DD3FC"))
    })
    container.addView(custCard)

    // Route & Navigation Card (Pickup Locked, Waypoints & Drop-off Editable + Tappable)
    val routeCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 16)
    routeCard.addView(TextView(this).apply {
        text = "ROUTE & NAVIGATION (TAP ANY STOP TO NAVIGATE)"
        textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#38BDF8"))
        setPadding(0, 0, 0, dp(10))
    })

    // 1) Pickup Location (Locked — Not Editable)
    val pickupBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AlistoUiKit.roundedBg(this@buildContractorActiveBookingTab, "#0F1D32", 12, "#10B981")
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        setOnClickListener { navigateToPointOrAddress(b.pickupLat, b.pickupLng, b.pickupAddress) }
    }
    pickupBox.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@buildContractorActiveBookingTab).apply {
            text = "PICKUP LOCATION • LOCKED"
            textSize = 10.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@buildContractorActiveBookingTab).apply {
            text = "Navigate ↗"; textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#6EE7B7"))
        })
    })
    pickupBox.addView(TextView(this).apply {
        text = buildString {
            append(b.pickupAddress.ifBlank { "${b.barangayZone}, ${b.municipality}" })
            if (b.pickupNote.isNotBlank()) append("\nPickup Note: ${b.pickupNote}")
        }
        textSize = 13.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, dp(4), 0, 0)
    })
    routeCard.addView(pickupBox)

    // 2) Waypoints (Editable: Add / Edit / Remove + Tap to Navigate)
    val waypoints = parseWaypointEntries(b.waypointsJson)
    routeCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        addView(TextView(this@buildContractorActiveBookingTab).apply {
            text = "WAYPOINTS (${waypoints.size})"
            textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(AlistoUiKit.chipButton(this@buildContractorActiveBookingTab, "+ Add Stop", true, "#1E293B", AlistoUiKit.GOLD_ACCENT) {
            promptEditOrAddWaypoint(b, waypoints, editIndex = null, user = user)
        })
    })

    if (waypoints.isEmpty()) {
        routeCard.addView(TextView(this).apply {
            text = "No extra stops added. Tap + Add Stop if the customer requests an additional waypoint."
            textSize = 12f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, 0, 0, dp(10))
        })
    } else {
        waypoints.forEachIndexed { idx, wp ->
            val wpAddr = wp.optString("address", "Stop ${idx + 1}")
            val wpLat = if (wp.has("lat") && !wp.isNull("lat")) wp.optDouble("lat") else null
            val wpLng = if (wp.has("lng") && !wp.isNull("lng")) wp.optDouble("lng") else null
            val wpBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = AlistoUiKit.roundedBg(this@buildContractorActiveBookingTab, "#131C2E", 10, "#475569")
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                setOnClickListener { navigateToPointOrAddress(wpLat, wpLng, wpAddr) }
            }
            wpBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@buildContractorActiveBookingTab).apply {
                    text = "STOP ${idx + 1}: $wpAddr"
                    textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@buildContractorActiveBookingTab).apply {
                    text = "Nav ↗"; textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#38BDF8"))
                })
            })
            wpBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
                addView(AlistoUiKit.chipButton(this@buildContractorActiveBookingTab, "Edit", false, "#1E293B", "#E2E8F0") {
                    promptEditOrAddWaypoint(b, waypoints, editIndex = idx, user = user)
                }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) } })
                addView(AlistoUiKit.chipButton(this@buildContractorActiveBookingTab, "Remove", false, "#450A0A", "#FCA5A5") {
                    val updated = waypoints.toMutableList().apply { removeAt(idx) }
                    saveRouteChanges(b, b.dropoffAddress, b.dropoffLat, b.dropoffLng, updated, user)
                })
            })
            routeCard.addView(wpBox)
        }
    }

    // 3) Final Drop-Off Location (Editable + Tappable for Navigation)
    val dropBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AlistoUiKit.roundedBg(this@buildContractorActiveBookingTab, "#1A1220", 12, "#F43F5E")
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        setOnClickListener { navigateToPointOrAddress(b.dropoffLat, b.dropoffLng, b.dropoffAddress) }
    }
    dropBox.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@buildContractorActiveBookingTab).apply {
            text = "DROP-OFF LOCATION"
            textSize = 10.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FB7185"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@buildContractorActiveBookingTab).apply {
            text = "Navigate ↗"; textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FDA4AF"))
        })
    })
    dropBox.addView(TextView(this).apply {
        text = buildString {
            append(b.dropoffAddress.ifBlank { b.pickupAddress })
            if (b.dropoffNote.isNotBlank()) append("\nDrop-Off Note: ${b.dropoffNote}")
        }
        textSize = 13.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY)); setPadding(0, dp(4), 0, dp(8))
    })
    dropBox.addView(AlistoUiKit.chipButton(this, "Edit Drop-Off Location", false, "#1E293B", "#FDE68A") {
        promptEditDropoffLocation(b, waypoints, user)
    })
    routeCard.addView(dropBox)

    if (b.bookingNotes.isNotBlank()) {
        routeCard.addView(TextView(this).apply {
            text = "Special Note: ${b.bookingNotes}"
            textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FDE68A"))
            background = AlistoUiKit.roundedBg(this@buildContractorActiveBookingTab, "#2A1B08", 10, "#92400E")
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })
    }
    container.addView(routeCard)

    // Status Progression Actions (Arrived -> Ongoing -> Complete with Payment Summary)
    val actionCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 16)
    actionCard.addView(TextView(this).apply {
        text = "UPDATE BOOKING STATUS"
        textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
        setPadding(0, 0, 0, dp(10))
    })

    when (b.status) {
        "ACCEPTED" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Heading to Pickup (On The Way)", "#0284C7", "#FFFFFF", heightDp = 50, textSizeSp = 14.5f) {
                transitionJob(b.id, "ON_THE_WAY")
            })
        }
        "ON_THE_WAY" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Arrived at Pickup Location", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, heightDp = 50, textSizeSp = 14.5f) {
                transitionJob(b.id, "ARRIVED")
            })
        }
        "ARRIVED" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Start Trip / Service (Ongoing)", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, heightDp = 50, textSizeSp = 14.5f) {
                transitionJob(b.id, "ONGOING")
            })
        }
        "ONGOING", "IN_PROGRESS" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Complete Booking & Payment Summary", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, "#6EE7B7", heightDp = 52, textSizeSp = 15f) {
                showCompletionPaymentSummaryDialog(b, user)
            })
        }
    }

    if (b.status in listOf("ACCEPTED", "ON_THE_WAY", "ARRIVED")) {
        actionCard.addView(
            AlistoUiKit.pillButton(this, "Safety / Unable to Fulfill Refusal", "#450A0A", "#FCA5A5", "#7F1D1D", heightDp = 40, textSizeSp = 12.5f) {
                promptLtfrbSafetyRefusal(b, user)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            }
        )
    }
    container.addView(actionCard)

    scroll.addView(container)
    return scroll
}

// ==========================================================================
// 2. CUSTOMER PROFILE MODAL (WITH CLOSE BUTTON)
// ==========================================================================
internal fun ContractorMainActivity.showCustomerProfileModal(b: BookingRecord) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(8))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    box.addView(TextView(this).apply {
        text = b.clientFullName.ifBlank { b.clientNickname }
        textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
    })
    box.addView(TextView(this).apply {
        val uname = b.clientUsername.takeIf { it.isNotBlank() }?.let { "@$it • " }.orEmpty()
        text = "${uname}Nickname: ${b.clientNickname}"
        textSize = 13f; setTextColor(Color.parseColor("#7DD3FC")); setPadding(0, dp(3), 0, dp(12))
    })

    fun row(label: String, value: String) {
        box.addView(TextView(this).apply {
            text = "$label: $value"
            textSize = 13.5f; setTextColor(Color.parseColor("#E2E8F0")); setPadding(0, dp(4), 0, dp(4))
        })
    }
    row("Phone", b.clientPhone.ifBlank { "Not listed" })
    row("Location", listOf(b.clientBarangayZone, b.clientMunicipality).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "${b.barangayZone}, ${b.municipality}" })
    row("Trust Score", "${"%.0f".format(b.clientTrustScore)}%  (👍 ${b.clientThumbsUp} / 👎 ${b.clientThumbsDown})")
    row("Account Status", b.clientAccountStatus)

    val builder = AlertDialog.Builder(this)
        .setTitle("Customer Profile")
        .setView(box)
        .setNegativeButton("Close", null)

    if (b.clientPhone.isNotBlank()) {
        builder.setPositiveButton("Call Customer") { _, _ ->
            try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${b.clientPhone}"))) } catch (_: Exception) {}
        }
    }
    builder.show()
}

// ==========================================================================
// 3. EDITABLE WAYPOINTS & DROP-OFF LOCATION DIALOGS
// ==========================================================================
private fun ContractorMainActivity.promptEditOrAddWaypoint(
    b: BookingRecord,
    currentWaypoints: MutableList<JSONObject>,
    editIndex: Int?,
    user: AuthenticatedUser
) {
    val existing = editIndex?.let { currentWaypoints.getOrNull(it)?.optString("address", "") }.orEmpty()
    val input = EditText(this).apply {
        setText(existing)
        hint = "Enter stop address or landmark..."
        setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#94A3B8"))
        setBackgroundColor(Color.parseColor("#0F172A"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    AlertDialog.Builder(this)
        .setTitle(if (editIndex == null) "Add Waypoint Stop" else "Edit Waypoint Stop #${editIndex + 1}")
        .setMessage("Pickup location is locked. Enter the updated waypoint address:")
        .setView(input)
        .setPositiveButton("Save Stop") { _, _ ->
            val addr = input.text.toString().trim()
            if (addr.isBlank()) {
                Toast.makeText(this, "Waypoint address cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            uiScope.launch {
                val geo = geocodeOptional(addr)
                val obj = JSONObject().apply {
                    put("address", addr)
                    if (geo != null) { put("lat", geo.first); put("lng", geo.second) }
                }
                val updated = currentWaypoints.toMutableList()
                if (editIndex == null) updated.add(obj) else updated[editIndex] = obj
                saveRouteChanges(b, b.dropoffAddress, b.dropoffLat, b.dropoffLng, updated, user)
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun ContractorMainActivity.promptEditDropoffLocation(
    b: BookingRecord,
    currentWaypoints: MutableList<JSONObject>,
    user: AuthenticatedUser
) {
    val input = EditText(this).apply {
        setText(b.dropoffAddress)
        hint = "Enter updated drop-off address..."
        setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#94A3B8"))
        setBackgroundColor(Color.parseColor("#0F172A"))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    AlertDialog.Builder(this)
        .setTitle("Edit Final Drop-Off Location")
        .setMessage("Pickup location cannot be modified. Update the final drop-off address below:")
        .setView(input)
        .setPositiveButton("Update Drop-Off") { _, _ ->
            val newAddr = input.text.toString().trim()
            if (newAddr.isBlank()) {
                Toast.makeText(this, "Drop-off address cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            uiScope.launch {
                val geo = geocodeOptional(newAddr)
                val newLat = geo?.first ?: b.dropoffLat
                val newLng = geo?.second ?: b.dropoffLng
                saveRouteChanges(b, newAddr, newLat, newLng, currentWaypoints, user)
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun ContractorMainActivity.saveRouteChanges(
    b: BookingRecord,
    dropoffAddress: String,
    dropoffLat: Double,
    dropoffLng: Double,
    waypoints: List<JSONObject>,
    user: AuthenticatedUser
) {
    val arr = JSONArray()
    waypoints.forEach { arr.put(it) }
    uiScope.launch {
        val res = AlistoKernelClient.updateBookingRouteRpc(b.id, dropoffAddress, dropoffLat, dropoffLng, arr)
        res.onSuccess {
            ContractorSoundEngine.playStepUpdatedTone(this@saveRouteChanges)
            Toast.makeText(this@saveRouteChanges, "Route updated cleanly.", Toast.LENGTH_SHORT).show()
            showActiveContractorPortal(user)
        }.onFailure { err ->
            Toast.makeText(this@saveRouteChanges, err.message ?: "Route update failed.", Toast.LENGTH_LONG).show()
        }
    }
}

// ==========================================================================
// 4. COMPLETION PAYMENT SUMMARY + DISPUTE WORKFLOW
// ==========================================================================
internal fun ContractorMainActivity.showCompletionPaymentSummaryDialog(b: BookingRecord, user: AuthenticatedUser) {
    val totalAmt = b.quotedFee + b.tipAmount
    val payLabel = if (b.paymentMethod.equals("WALLET_CREDITS", ignoreCase = true)) "ALISTO WALLET CREDITS (PREPAID)" else "CASH ON HAND"
    val completedNow = formatManilaDateTime(Instant.now())
    val elapsed = computeElapsedLabel(b)

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(12))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    box.addView(TextView(this).apply {
        text = "PHP ${"%.2f".format(totalAmt)}"
        textSize = 30f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
    })
    box.addView(TextView(this).apply {
        text = "Fare: PHP ${"%.2f".format(b.quotedFee)}  •  Tip: PHP ${"%.2f".format(b.tipAmount)}"
        textSize = 12.5f; setTextColor(Color.parseColor("#CBD5E1")); setPadding(0, dp(2), 0, dp(12))
    })

    fun detailLine(k: String, v: String) {
        box.addView(TextView(this).apply {
            text = "$k: $v"
            textSize = 13.5f; setTextColor(Color.parseColor("#F8FAFC")); setPadding(0, dp(4), 0, dp(4))
        })
    }
    detailLine("Booking Ref", b.bookingRef)
    detailLine("Payment Type", payLabel)
    detailLine("Completion Date/Time", completedNow)
    detailLine("Elapsed Time", elapsed)

    box.addView(TextView(this).apply {
        text = "Tap Confirm to mark this booking Paid & Completed and return to available status (5s cooldown), or tap Dispute to flag an issue to Admin."
        textSize = 12f; setTextColor(Color.parseColor("#94A3B8")); setPadding(0, dp(10), 0, 0)
    })

    val dlg = AlertDialog.Builder(this)
        .setTitle("Payment & Completion Summary")
        .setView(box)
        .setCancelable(true)
        .setNegativeButton("Dispute") { _, _ ->
            showContractorDisputeDialog(b, user)
        }
        .setPositiveButton("Confirm Paid & Complete") { _, _ ->
            uiScope.launch {
                val res = AlistoKernelClient.advanceBookingStateRpc(b.id, "COMPLETED")
                res.onSuccess {
                    lastCompletedAtMs = System.currentTimeMillis()
                    ContractorSoundEngine.playCompletedTone(this@showCompletionPaymentSummaryDialog)
                    Toast.makeText(
                        this@showCompletionPaymentSummaryDialog,
                        "Booking completed! You are now available (5s cooldown before next auto-assign).",
                        Toast.LENGTH_LONG
                    ).show()
                    activeTab = "DRIVE"
                    routeContractorByAccountState()
                }.onFailure { err ->
                    Toast.makeText(this@showCompletionPaymentSummaryDialog, err.message ?: "Completion failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
        .create()
    dlg.show()
}

private fun ContractorMainActivity.showContractorDisputeDialog(b: BookingRecord, user: AuthenticatedUser) {
    val reasons = arrayOf(
        "PRICING_DISPUTE" to "Dispute in Pricing / Extra Stops",
        "CUSTOMER_UNABLE_TO_PAY" to "Customer Unable or Refused to Pay",
        "CUSTOMER_THREAT_SAFETY" to "Customer Threatened Contractor / Safety Incident",
        "WRONG_OR_FAKE_LOCATION" to "Wrong / Unreachable Drop-Off Location",
        "REFUSED_OR_DAMAGED_ITEM" to "Recipient Refused Parcel / Item Conflict",
        "OTHER" to "Other Booking Dispute"
    )
    val labels = reasons.map { it.second }.toTypedArray()
    var selectedIdx = 0

    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(6))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    val noteInput = EditText(this).apply {
        hint = "Describe what happened (amount, details, safety notes)..."
        minLines = 3
        setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#94A3B8"))
        setBackgroundColor(Color.parseColor("#0F172A"))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    wrap.addView(TextView(this).apply {
        text = "Additional Details (Sent directly to Admin Disputes Desk):"
        textSize = 12f; setTextColor(Color.parseColor("#CBD5E1")); setPadding(0, dp(4), 0, dp(6))
    })
    wrap.addView(noteInput)

    AlertDialog.Builder(this)
        .setTitle("File Completion Dispute • ${b.bookingRef}")
        .setSingleChoiceItems(labels, 0) { _, which -> selectedIdx = which }
        .setView(wrap)
        .setNegativeButton("Back", null)
        .setPositiveButton("Submit Dispute & Close Job") { _, _ ->
            val (code, friendly) = reasons[selectedIdx]
            val details = noteInput.text.toString().trim().ifBlank { friendly }
            uiScope.launch {
                val res = AlistoKernelClient.completeBookingWithDisputeRpc(b.id, code, details)
                res.onSuccess {
                    lastCompletedAtMs = System.currentTimeMillis()
                    ContractorSoundEngine.playDisputeTone(this@showContractorDisputeDialog)
                    Toast.makeText(
                        this@showContractorDisputeDialog,
                        "Tagged COMPLETED WITH DISPUTE & sent to Admin Disputes Desk.",
                        Toast.LENGTH_LONG
                    ).show()
                    activeTab = "DRIVE"
                    routeContractorByAccountState()
                }.onFailure { err ->
                    Toast.makeText(this@showContractorDisputeDialog, err.message ?: "Unable to file dispute.", Toast.LENGTH_LONG).show()
                }
            }
        }
        .show()
}

// ==========================================================================
// 5. VERTICAL DISPATCH BOARD (OPEN, PENDING [DELIVERY/RIDES], ONGOING,
//    SCHEDULED, COMPLETED — WITH DISTANCE TO PICKUP & 1-ACTIVE-JOB GUARD)
// ==========================================================================
internal fun ContractorMainActivity.buildVerticalDispatchBoardTab(user: AuthenticatedUser): View {
    val prof = AlistoKernelClient.currentContractorProfile
    val currentShifts = getEffectiveShiftVerticals(prof)
    val myActiveJob = findMyActiveBooking(user.id)

    val openAll = AlistoKernelClient.bookings
        .filter { isBookingOpenForBoard(it, currentShifts, user.id) && it.scheduledStartAt.isNullOrBlank() }
        .sortedBy { distanceToPickupKm(it, user) }

    val pendingAll = AlistoKernelClient.bookings
        .filter {
            it.verticalKey in currentShifts &&
                it.contractorId == null &&
                it.status in listOf("CREATED", "PENDING", "SEARCHING") &&
                (it.offerContractorId == null || it.offerContractorId == user.id)
        }
        .sortedBy { distanceToPickupKm(it, user) }

    val hasDelivery = "delivery_logistics" in currentShifts
    val hasRides = "ride_hailing" in currentShifts
    val showPendingSubs = hasDelivery || hasRides
    if (!showPendingSubs) pendingSubCategory = "ALL"

    val pendingFiltered = when (pendingSubCategory) {
        "DELIVERY" -> pendingAll.filter { it.verticalKey == "delivery_logistics" }
        "RIDES" -> pendingAll.filter { it.verticalKey == "ride_hailing" }
        else -> pendingAll
    }

    val ongoingList = AlistoKernelClient.bookings.filter { it.contractorId == user.id && it.status in ACTIVE_JOB_STATUSES }
    val scheduledList = AlistoKernelClient.bookings
        .filter {
            !it.scheduledStartAt.isNullOrBlank() &&
                it.status !in listOf("COMPLETED", "COMPLETED_WITH_DISPUTE", "CANCELLED", "EXPIRED") &&
                (it.contractorId == user.id || (it.contractorId == null && it.verticalKey in currentShifts))
        }
        .sortedBy { it.scheduledStartAt }

    val completedList = AlistoKernelClient.bookings
        .filter { it.contractorId == user.id && it.status in listOf("COMPLETED", "COMPLETED_WITH_DISPUTE") }

    val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(28))
    }

    // Header
    container.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        addView(TextView(this@buildVerticalDispatchBoardTab).apply {
            text = "Dispatch Board"
            textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(AlistoUiKit.badgePill(this@buildVerticalDispatchBoardTab, "● Auto-Sync", "#064E3B", "#6EE7B7"))
    })

    // 1-active-booking notice banner
    if (myActiveJob != null) {
        val lockBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = AlistoUiKit.roundedBg(this@buildVerticalDispatchBoardTab, "#0C2542", 12, "#38BDF8")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            setOnClickListener { activeTab = "ACTIVE"; showActiveContractorPortal(user) }
            addView(TextView(this@buildVerticalDispatchBoardTab).apply {
                text = "1 Active Booking Limit: Complete ${myActiveJob.bookingRef} first."
                textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#7DD3FC"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@buildVerticalDispatchBoardTab).apply {
                text = "Open Active →"; textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
            })
        }
        container.addView(lockBanner)
    }

    // VERTICAL CATEGORY SELECTOR LIST
    val categories = listOf(
        Triple("OPEN", "Open", openAll.size),
        Triple("PENDING", "Pending (Pick Nearby)", pendingAll.size),
        Triple("ONGOING", "Ongoing", ongoingList.size),
        Triple("SCHEDULED", "Scheduled", scheduledList.size),
        Triple("COMPLETED", "Completed", completedList.size)
    )

    val menuCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AlistoUiKit.roundedBg(this@buildVerticalDispatchBoardTab, "#0B1324", 16, "#1E293B")
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
    }

    categories.forEach { (key, title, cnt) ->
        val selected = boardCategory == key
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = AlistoUiKit.roundedBg(
                this@buildVerticalDispatchBoardTab,
                if (selected) "#172554" else "#0B1324",
                12,
                if (selected) AlistoUiKit.GOLD_ACCENT else "#1E293B"
            )
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
            setOnClickListener {
                boardCategory = key
                showActiveContractorPortal(user)
            }
            addView(TextView(this@buildVerticalDispatchBoardTab).apply {
                text = if (selected) "▸ $title" else title
                textSize = 14f; setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(Color.parseColor(if (selected) AlistoUiKit.GOLD_ACCENT else AlistoUiKit.TEXT_PRIMARY))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(AlistoUiKit.badgePill(
                this@buildVerticalDispatchBoardTab,
                cnt.toString(),
                if (cnt > 0 && selected) AlistoUiKit.GOLD_ACCENT else "#1E293B",
                if (cnt > 0 && selected) AlistoUiKit.BG_DEEP else "#CBD5E1"
            ))
        }
        menuCard.addView(row)

        // Subcategory vertical pills directly under Pending (context-aware: Delivery / Rides only when relevant)
        if (key == "PENDING" && selected && showPendingSubs) {
            val subWrap = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(18), dp(2), dp(8), dp(8))
            }
            val subs = buildList {
                add("ALL" to "All (${pendingAll.size})")
                if (hasDelivery) add("DELIVERY" to "Delivery (${pendingAll.count { it.verticalKey == "delivery_logistics" }})")
                if (hasRides) add("RIDES" to "Rides (${pendingAll.count { it.verticalKey == "ride_hailing" }})")
            }
            subs.forEach { (sKey, sLbl) ->
                subWrap.addView(
                    AlistoUiKit.chipButton(this, sLbl, pendingSubCategory == sKey, AlistoUiKit.CYAN, AlistoUiKit.BG_DEEP) {
                        pendingSubCategory = sKey
                        showActiveContractorPortal(user)
                    }.apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
                    }
                )
            }
            menuCard.addView(subWrap)
        }
    }
    container.addView(menuCard)

    // Render list for selected vertical category
    val activeList = when (boardCategory) {
        "PENDING" -> pendingFiltered
        "ONGOING" -> ongoingList
        "SCHEDULED" -> scheduledList
        "COMPLETED" -> completedList
        else -> openAll
    }

    if (activeList.isEmpty()) {
        val empty = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 20)
        empty.addView(TextView(this).apply {
            text = "No bookings in ${boardCategory.lowercase().replaceFirstChar { it.uppercase() }} right now."
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        })
        empty.addView(TextView(this).apply {
            text = "Active Shift: ${shiftModeLabel(currentShifts)} • Updates automatically in real time."
            textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(6), 0, 0)
        })
        container.addView(empty)
    } else {
        activeList.forEach { b ->
            container.addView(buildBoardBookingCard(b, user, myActiveJob != null))
        }
    }

    scroll.addView(container)
    return scroll
}

private fun ContractorMainActivity.buildBoardBookingCard(
    b: BookingRecord,
    user: AuthenticatedUser,
    hasActiveJobLock: Boolean
): View {
    val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 16)
    val distKm = distanceToPickupKm(b, user)
    val isDisputed = b.status == "COMPLETED_WITH_DISPUTE"
    val (sBg, sFg) = if (isDisputed) "#450A0A" to "#FDA4AF" else AlistoUiKit.statusColors(b.status)

    // Top metadata row: ref + vertical + distance to pickup + status
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        addView(TextView(this@buildBoardBookingCard).apply {
            text = "${b.bookingRef} • ${b.verticalKey.replace("_", " ")}"
            textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (b.contractorId == null) {
            addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, "📍 ${formatDistanceLabel(distKm)}", "#0C2D48", "#7DD3FC").apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
        }
        addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, b.status.replace("_", " "), sBg, sFg))
    })

    card.addView(TextView(this).apply {
        text = b.serviceTitle
        textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
    })

    if (!b.scheduledStartAt.isNullOrBlank()) {
        card.addView(TextView(this).apply {
            text = "Scheduled Pickup: ${b.scheduledStartAt}"
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
            setPadding(0, dp(4), 0, dp(2))
        })
    }

    val routeText = buildString {
        append("Pickup: ${b.pickupAddress} (${b.barangayZone}, ${b.municipality})")
        if (b.dropoffAddress.isNotBlank() && b.dropoffAddress != b.pickupAddress) {
            append("\nDrop-Off: ${b.dropoffAddress}")
        }
    }
    card.addView(TextView(this).apply {
        text = routeText
        textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(4), 0, dp(8))
    })

    // Fee + Tip + Payment pills
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, "PHP ${"%.2f".format(b.quotedFee)}", AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
        })
        if (b.tipAmount > 0.0) {
            addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, "Tip ₱${"%.0f".format(b.tipAmount)}", AlistoUiKit.BG_INPUT, AlistoUiKit.GOLD_ACCENT).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
        }
        addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, b.paymentMethod, AlistoUiKit.BG_INPUT, "#CBD5E1"))
    })

    // Action row
    if (b.contractorId == user.id && b.status in ACTIVE_JOB_STATUSES) {
        card.addView(AlistoUiKit.pillButton(this, "Open in Active Booking Tab →", "#0284C7", "#FFFFFF", heightDp = 42, textSizeSp = 13f) {
            activeTab = "ACTIVE"; showActiveContractorPortal(user)
        })
    } else if (b.contractorId == null && b.status in listOf("CREATED", "PENDING", "SEARCHING")) {
        if (hasActiveJobLock) {
            card.addView(TextView(this).apply {
                text = "Finish your current active booking before requesting another."
                textSize = 12f; setTextColor(Color.parseColor("#94A3B8"))
            })
        } else {
            val reqBtn = AlistoUiKit.pillButton(
                this, "Request Booking (${formatDistanceLabel(distKm)})", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, "#FEF08A", heightDp = 44, textSizeSp = 13f
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
                        startActivity(ContractorOfferBridge.buildIntent(this@buildBoardBookingCard, offerPayload))
                    }.onFailure { err ->
                        Toast.makeText(this@buildBoardBookingCard, err.message ?: "Booking unavailable.", Toast.LENGTH_LONG).show()
                        AlistoKernelClient.syncBookingsAndWallet()
                        showActiveContractorPortal(user)
                    }
                }
            }
            if (b.verticalKey == "skills_marketplace") {
                card.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(AlistoUiKit.pillButton(this@buildBoardBookingCard, "Place Bid", "#0F294A", "#7DD3FC", "#38BDF8", heightDp = 44, textSizeSp = 13f) {
                        promptSubmitOpenBid(b)
                    }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { rightMargin = dp(8) } })
                    addView(reqBtn.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f) })
                })
            } else {
                card.addView(reqBtn)
            }
        }
    }

    return card
}
