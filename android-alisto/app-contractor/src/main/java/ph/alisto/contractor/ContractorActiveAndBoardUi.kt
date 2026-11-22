package ph.alisto.contractor

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import ph.alisto.kernel.WalletLedgerEntry
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private val ACTIVE_JOB_STATUSES = setOf("ACCEPTED", "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS")
private val DROPOFF_GPS_EXEMPT_USERNAMES = setOf("marco", "clarisse")
private const val DROPOFF_PROXIMITY_LIMIT_M = 800.0

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

internal fun computeTripDistanceKm(b: BookingRecord): Double? {
    if (b.actualDistanceKm != null && b.actualDistanceKm!! > 0.0) return b.actualDistanceKm
    if ((b.pickupLat == 0.0 && b.pickupLng == 0.0) || (b.dropoffLat == 0.0 && b.dropoffLng == 0.0)) return null
    val res = FloatArray(1)
    Location.distanceBetween(b.pickupLat, b.pickupLng, b.dropoffLat, b.dropoffLng, res)
    val straightKm = res[0].toDouble() / 1000.0
    if (straightKm < 0.02) return null
    return straightKm * 1.25
}

internal fun formatDistanceLabel(km: Double): String {
    return if (km < 1.0) "${(km * 1000).toInt()} m away" else "${"%.1f".format(km)} km away"
}

internal fun formatTripKm(km: Double): String {
    return if (km < 1.0) "${(km * 1000).toInt()} m" else "${"%.2f".format(km)} km"
}

private data class ContractorWaitingSnapshot(
    val billableMinutes: Int,
    val runningFeePhp: Double,
    val bannerText: String,
    val isOverFree: Boolean
)

private fun computeContractorWaitingSnapshot(b: BookingRecord): ContractorWaitingSnapshot {
    val rules = AlistoKernelClient.currentPricingRules
    val freeSec = rules.freeWaitMinutes * 60L
    if (b.status == "ARRIVED" && !b.arrivedAt.isNullOrBlank()) {
        return try {
            val arrived = Instant.parse(b.arrivedAt)
            val elapsedSec = Duration.between(arrived, Instant.now()).seconds.coerceAtLeast(0L)
            if (elapsedSec <= freeSec) {
                val rem = (freeSec - elapsedSec).coerceAtLeast(0L)
                ContractorWaitingSnapshot(
                    0, 0.0,
                    "Free Waiting Countdown: %02d:%02d left (PHP %.2f/min after)".format(rem / 60L, rem % 60L, rules.waitingRatePerMinPhp),
                    false
                )
            } else {
                val overSec = elapsedSec - freeSec
                val billMins = ceil(overSec / 60.0).toInt().coerceAtLeast(1)
                val fee = billMins * rules.waitingRatePerMinPhp
                ContractorWaitingSnapshot(
                    billMins, fee,
                    "Paid Waiting Active (+%02d:%02d over free %dm) • +PHP %.2f".format(overSec / 60L, overSec % 60L, rules.freeWaitMinutes, fee),
                    true
                )
            }
        } catch (_: Exception) {
            ContractorWaitingSnapshot(0, 0.0, "Free ${rules.freeWaitMinutes}-minute waiting window active", false)
        }
    }
    return ContractorWaitingSnapshot(
        b.waitingBillableMinutes,
        b.waitingFeePhp,
        if (b.waitingFeePhp > 0.0) "Recorded Paid Wait (${b.waitingBillableMinutes}m): +PHP ${"%.2f".format(b.waitingFeePhp)}" else "",
        b.waitingFeePhp > 0.0
    )
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
                    if (item.isNotBlank()) list.add(JSONObject().apply { put("address", item.trim()) })
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
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(address)}")))
    } catch (_: Exception) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(address)}")))
        } catch (_: Exception) {
            Toast.makeText(this, "No maps app available to navigate.", Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun ContractorMainActivity.geocodeOptional(address: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    try {
        @Suppress("DEPRECATION")
        val hits = Geocoder(this@geocodeOptional, Locale.getDefault()).getFromLocationName("$address, Pampanga, Philippines", 1)
        hits?.firstOrNull()?.let { it.latitude to it.longitude }
    } catch (_: Exception) { null }
}

private suspend fun ContractorMainActivity.reverseGeocodeOptional(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
    try {
        @Suppress("DEPRECATION")
        val hits = Geocoder(this@reverseGeocodeOptional, Locale.getDefault()).getFromLocation(lat, lng, 1)
        hits?.firstOrNull()?.getAddressLine(0)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }
}

private fun formatManilaDateTime(isoOrNow: Instant = Instant.now()): String {
    val zone = try { ZoneId.of("Asia/Manila") } catch (_: Exception) { ZoneId.systemDefault() }
    return DateTimeFormatter.ofPattern("MMM dd, yyyy • hh:mm a", Locale.US).format(isoOrNow.atZone(zone))
}

private fun computeElapsedLabel(b: BookingRecord): String {
    val startIso = b.startedAt ?: b.onTheWayAt ?: b.acceptedAt ?: return "Just now"
    return try {
        val dur = Duration.between(Instant.parse(startIso), Instant.now())
        val totalMin = dur.toMinutes().coerceAtLeast(0L)
        val hrs = totalMin / 60L
        val mins = totalMin % 60L
        val secs = (dur.seconds % 60L).coerceAtLeast(0L)
        when {
            hrs > 0 -> "${hrs}h ${mins}m"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    } catch (_: Exception) { "Active session" }
}

// ==========================================================================
// 1. DEDICATED CURRENT / ACTIVE BOOKING TAB
// ==========================================================================
internal fun ContractorMainActivity.buildContractorActiveBookingTab(user: AuthenticatedUser): View {
    val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor(AlistoUiKit.BG_DEEP)) }
    val container = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(28))
    }

    val activeJob = findMyActiveBooking(user.id)

    container.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        addView(TextView(this@buildContractorActiveBookingTab).apply {
            text = "Active Booking"
            textSize = 21f; setTypeface(null, Typeface.BOLD)
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
        val emptyCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 20)
        emptyCard.addView(TextView(this).apply {
            text = "No Active Booking Right Now"
            textSize = 16.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        })
        emptyCard.addView(TextView(this).apply {
            text = "When you accept an auto-assigned offer or win a booking from the Dispatch Board, full route navigation, customer Chat/Call/Track controls, editable waypoints, and status updates appear here."
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
    val waitSnap = computeContractorWaitingSnapshot(b)
    val tripDistKm = computeTripDistanceKm(b)
    val totalCollect = b.quotedFee + waitSnap.runningFeePhp + b.tipAmount

    // Hero Fare, Distance & Elapsed Card
    val hero = AlistoUiKit.surfaceCard(this, "#0B1D39", "#38BDF8", padDp = 15)
    hero.addView(TextView(this).apply {
        text = "${b.bookingRef}  •  ${b.verticalKey.replace("_", " ").uppercase()}  •  ${b.paymentMethod}"
        textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#93C5FD"))
    })
    hero.addView(TextView(this).apply {
        text = b.serviceTitle
        textSize = 17.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        setPadding(0, dp(3), 0, dp(6))
    })
    hero.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(AlistoUiKit.badgePill(this@buildContractorActiveBookingTab, "Total: PHP ${"%.2f".format(totalCollect)}", "#064E3B", "#6EE7B7").apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
        })
        if (tripDistKm != null) {
            addView(AlistoUiKit.badgePill(this@buildContractorActiveBookingTab, "Trip: ${formatTripKm(tripDistKm)}", "#1E3A8A", "#93C5FD").apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
        }
        addView(AlistoUiKit.badgePill(this@buildContractorActiveBookingTab, computeElapsedLabel(b), "#1E293B", "#CBD5E1"))
    })

    // Live 5-minute free waiting countdown banner once ARRIVED
    if (b.status == "ARRIVED" || waitSnap.runningFeePhp > 0.0) {
        val waitBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.roundedBg(
                this@buildContractorActiveBookingTab,
                if (waitSnap.isOverFree) "#450A0A" else "#064E3B",
                10,
                if (waitSnap.isOverFree) "#F43F5E" else "#10B981"
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        waitBanner.addView(TextView(this).apply {
            text = waitSnap.bannerText
            textSize = 12.5f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (waitSnap.isOverFree) "#FDA4AF" else "#6EE7B7"))
        })
        if (!b.customerGoingOutAt.isNullOrBlank()) {
            waitBanner.addView(TextView(this).apply {
                text = "✓ Customer tapped \"I am going out\""
                textSize = 11.5f; setTextColor(Color.parseColor("#D1FAE5")); setPadding(0, dp(3), 0, 0)
            })
        }
        hero.addView(waitBanner)
    }
    container.addView(hero)

    // Customer Card with Inline Chat, Call, Track + Dedicated Profile-Only View Button
    val custCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#334155", padDp = 14)
    custCard.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(LinearLayout(this@buildContractorActiveBookingTab).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showCustomerProfileModal(b) }
            addView(TextView(this@buildContractorActiveBookingTab).apply {
                text = "CUSTOMER"
                textSize = 10.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
            })
            addView(TextView(this@buildContractorActiveBookingTab).apply {
                text = "${b.clientNickname}  •  Trust ${"%.0f".format(b.clientTrustScore)}%"
                textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
                setPadding(0, dp(2), 0, 0)
            })
        })
        addView(AlistoUiKit.chipButton(this@buildContractorActiveBookingTab, "View Profile", true, "#1E293B", "#7DD3FC") {
            showCustomerProfileModal(b)
        })
    })

    // Direct Inline Action Row: Chat | Call | Track
    val custActions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    custActions.addView(
        AlistoUiKit.pillButton(this, "Chat", "#0F294A", "#7DD3FC", "#38BDF8", heightDp = 40, textSizeSp = 12.5f) {
            openContractorChatDialog(b)
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } }
    )
    custActions.addView(
        AlistoUiKit.pillButton(this, "Call", "#064E3B", "#6EE7B7", "#10B981", heightDp = 40, textSizeSp = 12.5f) {
            if (b.clientPhone.isNotBlank()) {
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${b.clientPhone}"))) } catch (_: Exception) {}
            } else {
                Toast.makeText(this@buildContractorActiveBookingTab, "Customer phone not listed — use In-App Chat.", Toast.LENGTH_SHORT).show()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) } }
    )
    custActions.addView(
        AlistoUiKit.pillButton(this, "Track", "#2A1B08", AlistoUiKit.GOLD_ACCENT, "#B45309", heightDp = 40, textSizeSp = 12.5f) {
            activeTab = "DRIVE"
            showActiveContractorPortal(user)
        }.apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
    )
    custCard.addView(custActions)
    container.addView(custCard)

    // Route & Navigation Card (Pickup Locked, Waypoints & Drop-off Editable + Tappable)
    val routeCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 15)
    routeCard.addView(TextView(this).apply {
        text = "ROUTE & NAVIGATION" + if (tripDistKm != null) "  •  ${formatTripKm(tripDistKm)} PICKUP → DROP-OFF" else ""
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
            text = "DROP-OFF LOCATION" + if (b.dropoffGpsAdjusted) " • GPS ADJUSTED" else ""
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

    // Status Progression Actions
    val actionCard = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 15)
    actionCard.addView(TextView(this).apply {
        text = "UPDATE BOOKING STATUS"
        textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.GOLD_ACCENT))
        setPadding(0, 0, 0, dp(10))
    })

    when (b.status) {
        "ACCEPTED" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Heading to Pickup (On The Way)", "#0284C7", "#FFFFFF", heightDp = 48, textSizeSp = 14f) {
                transitionJob(b.id, "ON_THE_WAY")
            })
        }
        "ON_THE_WAY" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Arrived at Pickup Location", AlistoUiKit.GOLD_ACCENT, AlistoUiKit.BG_DEEP, heightDp = 48, textSizeSp = 14f) {
                transitionJob(b.id, "ARRIVED")
            })
        }
        "ARRIVED" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Start Trip / Service (Ongoing)", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, heightDp = 48, textSizeSp = 14f) {
                transitionJob(b.id, "ONGOING")
            })
        }
        "ONGOING", "IN_PROGRESS" -> {
            actionCard.addView(AlistoUiKit.pillButton(this, "Complete Booking & Payment Summary", AlistoUiKit.EMERALD, AlistoUiKit.BG_DEEP, "#6EE7B7", heightDp = 50, textSizeSp = 14.5f) {
                verifyDropoffProximityThenComplete(b, user)
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
// 2. IN-APP CONTRACTOR <-> CUSTOMER CHAT DIALOG
// ==========================================================================
internal fun ContractorMainActivity.openContractorChatDialog(b: BookingRecord) {
    val wrap = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(10))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    val history = AlistoKernelClient.chatTranscripts[b.id].orEmpty()
    if (history.isNotEmpty()) {
        val logBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AlistoUiKit.roundedBg(this@openContractorChatDialog, "#0F172A", 10, "#1E293B")
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        history.takeLast(6).forEach { m ->
            logBox.addView(TextView(this).apply {
                text = "${m.senderName}: ${m.body}"
                textSize = 12.5f; setTextColor(Color.parseColor("#E2E8F0")); setPadding(0, dp(2), 0, dp(2))
            })
        }
        wrap.addView(logBox)
    }
    val msgInput = AlistoUiKit.inputField(this, "Message ${b.clientNickname} (arrival, gate, items)...")
    wrap.addView(msgInput)

    AlertDialog.Builder(this)
        .setTitle("Chat with ${b.clientNickname} • ${b.bookingRef}")
        .setView(wrap)
        .setNegativeButton("Close", null)
        .setPositiveButton("Send") { _, _ ->
            val body = msgInput.text.toString().trim()
            if (body.isBlank()) return@setPositiveButton
            uiScope.launch {
                val res = AlistoKernelClient.sendModeratedChatMessageRpc(b.id, body)
                res.onSuccess {
                    ContractorSoundEngine.playStepUpdatedTone(this@openContractorChatDialog)
                    Toast.makeText(this@openContractorChatDialog, "Message sent to ${b.clientNickname}", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    AlertDialog.Builder(this@openContractorChatDialog)
                        .setTitle("Chat Notice").setMessage(err.message ?: "Unable to send message.").setPositiveButton("OK", null).show()
                }
            }
        }
        .show()
}

// ==========================================================================
// 3. CUSTOMER PROFILE MODAL (CLEAN PROFILE-ONLY WITH CLOSE BUTTON)
// ==========================================================================
internal fun ContractorMainActivity.showCustomerProfileModal(b: BookingRecord) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(10))
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

    AlertDialog.Builder(this)
        .setTitle("Customer Profile")
        .setView(box)
        .setNegativeButton("Close", null)
        .show()
}

// ==========================================================================
// 4. EDITABLE WAYPOINTS & DROP-OFF LOCATION DIALOGS
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
// 5. 800m DROP-OFF GPS VERIFICATION (EXEMPTING MARCO & CLARISSE)
//    + COMPLETION PAYMENT SUMMARY & DISPUTE WORKFLOW
// ==========================================================================
internal fun ContractorMainActivity.verifyDropoffProximityThenComplete(b: BookingRecord, user: AuthenticatedUser) {
    val isExempt = user.username.trim().lowercase(Locale.US) in DROPOFF_GPS_EXEMPT_USERNAMES
    val requiresDropoffCheck = !isExempt && b.verticalKey in listOf("delivery_logistics", "ride_hailing") &&
        (b.dropoffLat != 0.0 || b.dropoffLng != 0.0)

    if (!requiresDropoffCheck) {
        showCompletionPaymentSummaryDialog(b, user)
        return
    }

    val liveLat = AlistoLocationTracker.lastLat
    val liveLng = AlistoLocationTracker.lastLng
    if (liveLat == null || liveLng == null) {
        Toast.makeText(this, "Waiting for live GPS fix to verify drop-off proximity...", Toast.LENGTH_LONG).show()
        return
    }

    val distArr = FloatArray(1)
    Location.distanceBetween(liveLat, liveLng, b.dropoffLat, b.dropoffLng, distArr)
    val distMeters = distArr[0].toDouble()

    if (distMeters <= DROPOFF_PROXIMITY_LIMIT_M) {
        showCompletionPaymentSummaryDialog(b, user)
        return
    }

    val distLabel = if (distMeters >= 1000.0) "${"%.2f".format(distMeters / 1000.0)} km" else "${distMeters.toInt()} m"
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(10))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    box.addView(TextView(this).apply {
        text = "You are $distLabel away from the pinned drop-off location (max 800m)."
        textSize = 14.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#FDE68A"))
    })
    box.addView(TextView(this).apply {
        text = "To complete this booking at your actual location, update the drop-off pin to your current realtime GPS coordinates. The app will automatically recalculate the route distance, customer fare, and platform commission."
        textSize = 13f; setTextColor(Color.parseColor("#CBD5E1")); setPadding(0, dp(8), 0, 0)
    })

    AlertDialog.Builder(this)
        .setTitle("Drop-Off Location Verification")
        .setView(box)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Update Drop-Off to Live GPS & Recalculate") { _, _ ->
            uiScope.launch {
                val resolvedAddr = reverseGeocodeOptional(liveLat, liveLng)
                val res = AlistoKernelClient.adjustDropoffGpsRpc(b.id, liveLat, liveLng, resolvedAddr)
                res.onSuccess {
                    ContractorSoundEngine.playStepUpdatedTone(this@verifyDropoffProximityThenComplete)
                    val refreshed = AlistoKernelClient.bookings.firstOrNull { it.id == b.id } ?: b
                    Toast.makeText(
                        this@verifyDropoffProximityThenComplete,
                        "Drop-off updated to GPS! Adjusted Fare: PHP ${"%.2f".format(refreshed.quotedFee)}",
                        Toast.LENGTH_LONG
                    ).show()
                    showCompletionPaymentSummaryDialog(refreshed, user)
                }.onFailure { err ->
                    Toast.makeText(this@verifyDropoffProximityThenComplete, err.message ?: "GPS recalculation failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
        .show()
}

internal fun ContractorMainActivity.showCompletionPaymentSummaryDialog(b: BookingRecord, user: AuthenticatedUser) {
    val rules = AlistoKernelClient.currentPricingRules
    val waitSnap = computeContractorWaitingSnapshot(b)
    val waitFee = if (b.waitingFeePhp > 0.0) b.waitingFeePhp else waitSnap.runningFeePhp
    val waitMins = if (b.waitingBillableMinutes > 0) b.waitingBillableMinutes else waitSnap.billableMinutes
    val tripDistKm = computeTripDistanceKm(b)
    val totalAmt = b.quotedFee + waitFee + b.tipAmount
    val estComm = if (b.finalCommissionPhp > 0.0) {
        b.finalCommissionPhp
    } else if (b.commissionLocked == 0.0) {
        0.0
    } else {
        (b.quotedFee + waitFee) * rules.commissionRate
    }
    val estNet = (totalAmt - estComm).coerceAtLeast(0.0)
    val payLabel = if (b.paymentMethod.equals("WALLET_CREDITS", ignoreCase = true)) "ALISTO WALLET CREDITS (PREPAID)" else "CASH ON HAND"
    val completedNow = formatManilaDateTime(Instant.now())
    val elapsed = computeElapsedLabel(b)

    val scroll = ScrollView(this)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(12))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    box.addView(TextView(this).apply {
        text = "PHP ${"%.2f".format(totalAmt)}"
        textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
    })
    box.addView(TextView(this).apply {
        text = buildString {
            append("Fare: PHP ${"%.2f".format(b.quotedFee)}")
            if (waitFee > 0.0) append("  •  Wait (${waitMins}m): PHP ${"%.2f".format(waitFee)}")
            append("  •  Tip: PHP ${"%.2f".format(b.tipAmount)}")
        }
        textSize = 12.5f; setTextColor(Color.parseColor("#CBD5E1")); setPadding(0, dp(2), 0, dp(10))
    })

    fun detailLine(k: String, v: String, colorHex: String = "#F8FAFC") {
        box.addView(TextView(this).apply {
            text = "$k: $v"
            textSize = 13f; setTextColor(Color.parseColor(colorHex)); setPadding(0, dp(3), 0, dp(3))
        })
    }
    detailLine("Booking Ref", b.bookingRef)
    detailLine("Payment Type", payLabel)
    detailLine("Total Distance Travelled", tripDistKm?.let { "${formatTripKm(it)} (Pickup → Drop-Off)" } ?: "On-site / Point service")
    if (b.dropoffGpsAdjusted) detailLine("Drop-Off Update", "Recalculated from Realtime GPS", "#7DD3FC")
    detailLine("Completion Date/Time", completedNow)
    detailLine("Elapsed Time", elapsed)
    detailLine("Platform Commission (Excl. Tips)", "PHP ${"%.2f".format(estComm)}", "#FDA4AF")
    detailLine("Your Net Earning", "PHP ${"%.2f".format(estNet)}", AlistoUiKit.EMERALD)

    box.addView(TextView(this).apply {
        text = "Tap Confirm to mark Paid & Completed (5s cooldown before next auto-assign), or tap Dispute to flag an issue to Admin."
        textSize = 11.5f; setTextColor(Color.parseColor("#94A3B8")); setPadding(0, dp(10), 0, 0)
    })
    scroll.addView(box)

    AlertDialog.Builder(this)
        .setTitle("Payment & Completion Summary")
        .setView(scroll)
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
                        "Booking completed! You are now available (5s cooldown).",
                        Toast.LENGTH_LONG
                    ).show()
                    activeTab = "DRIVE"
                    routeContractorByAccountState()
                }.onFailure { err ->
                    Toast.makeText(this@showCompletionPaymentSummaryDialog, err.message ?: "Completion failed.", Toast.LENGTH_LONG).show()
                }
            }
        }
        .show()
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
// 6. COMPLETED BOOKING DETAILS & EARNINGS BREAKDOWN MODAL
// ==========================================================================
internal fun ContractorMainActivity.showCompletedBookingBreakdownModal(b: BookingRecord) {
    val tripDistKm = computeTripDistanceKm(b)
    val totalFareAndWait = b.quotedFee + b.waitingFeePhp
    val grossTotal = totalFareAndWait + b.tipAmount
    val commission = if (b.finalCommissionPhp > 0.0) b.finalCommissionPhp else b.commissionLocked
    val netEarning = (grossTotal - commission).coerceAtLeast(0.0)

    val scroll = ScrollView(this)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(10))
        setBackgroundColor(Color.parseColor("#0B1324"))
    }
    box.addView(TextView(this).apply {
        text = "Net Earning: PHP ${"%.2f".format(netEarning)}"
        textSize = 24f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.EMERALD))
    })
    box.addView(TextView(this).apply {
        text = "${b.bookingRef} • ${b.status.replace("_", " ")} • ${b.paymentMethod}"
        textSize = 12f; setTextColor(Color.parseColor("#7DD3FC")); setPadding(0, dp(3), 0, dp(12))
    })

    fun line(k: String, v: String, colorHex: String = "#E2E8F0", bold: Boolean = false) {
        box.addView(TextView(this).apply {
            text = "$k: $v"
            textSize = 13.5f
            if (bold) setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(colorHex))
            setPadding(0, dp(3), 0, dp(3))
        })
    }
    line("Service", b.serviceTitle)
    line("Customer", b.clientNickname)
    line("Pickup", b.pickupAddress.ifBlank { "${b.barangayZone}, ${b.municipality}" })
    if (b.dropoffAddress.isNotBlank() && b.dropoffAddress != b.pickupAddress) {
        line("Drop-Off", b.dropoffAddress)
    }
    if (tripDistKm != null) line("Total Distance", "${formatTripKm(tripDistKm)} (Pickup → Drop-Off)", "#7DD3FC")
    line("Base / Route Fare", "PHP ${"%.2f".format(b.quotedFee)}")
    if (b.waitingFeePhp > 0.0) line("Waiting Time (${b.waitingBillableMinutes}m)", "PHP ${"%.2f".format(b.waitingFeePhp)}", "#FDE68A")
    line("Total Tip (100% Protected)", "PHP ${"%.2f".format(b.tipAmount)}", AlistoUiKit.GOLD_ACCENT)
    line("Total Amount Collected", "PHP ${"%.2f".format(grossTotal)}", "#F8FAFC", bold = true)
    line("Platform Commission", "- PHP ${"%.2f".format(commission)}", "#FDA4AF", bold = true)
    line("Your Net Earning", "PHP ${"%.2f".format(netEarning)}", AlistoUiKit.EMERALD, bold = true)
    if (!b.completedAt.isNullOrBlank()) {
        line("Completed At", b.completedAt!!)
    }

    scroll.addView(box)
    AlertDialog.Builder(this)
        .setTitle("Completed Booking Breakdown")
        .setView(scroll)
        .setNegativeButton("Close", null)
        .show()
}

// ==========================================================================
// 7. WALLET ACTIVITY HISTORY & DOWNLOADABLE CSV (WITH ATTACHED BOOKING INFO)
// ==========================================================================
internal fun ContractorMainActivity.appendWalletLedgerSection(container: LinearLayout, user: AuthenticatedUser) {
    val card = AlistoUiKit.surfaceCard(this, "#0B1324", "#1E293B", padDp = 16)
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }
    head.addView(TextView(this).apply {
        text = "Wallet Activity & Booking Ledger"
        textSize = 15.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(AlistoUiKit.TEXT_PRIMARY))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    })
    val csvBtn = AlistoUiKit.chipButton(this, "Download CSV", true, "#064E3B", "#6EE7B7") {
        exportWalletEntriesCsv(cachedWalletEntries, user)
    }
    head.addView(csvBtn)
    card.addView(head)

    val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val statusLine = TextView(this).apply {
        text = "Loading recent wallet activity..."
        textSize = 12f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(4), 0, dp(6))
    }
    listHost.addView(statusLine)
    card.addView(listHost)
    container.addView(card)

    uiScope.launch {
        val res = AlistoKernelClient.fetchMyWalletLedgerRpc(150)
        listHost.removeAllViews()
        res.onSuccess { entries ->
            cachedWalletEntries = entries
            if (entries.isEmpty()) {
                listHost.addView(TextView(this@appendWalletLedgerSection).apply {
                    text = "No wallet transactions recorded yet."
                    textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED))
                })
            } else {
                entries.take(35).forEach { e ->
                    val row = LinearLayout(this@appendWalletLedgerSection).apply {
                        orientation = LinearLayout.VERTICAL
                        background = AlistoUiKit.roundedBg(this@appendWalletLedgerSection, "#0F172A", 10, "#1E293B")
                        setPadding(dp(11), dp(9), dp(11), dp(9))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) }
                    }
                    val amtSign = if (e.amount > 0) "+" else ""
                    val amtColor = when {
                        e.amount > 0 -> AlistoUiKit.EMERALD
                        e.amount < 0 -> "#FDA4AF"
                        else -> "#CBD5E1"
                    }
                    row.addView(LinearLayout(this@appendWalletLedgerSection).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@appendWalletLedgerSection).apply {
                            text = e.entryType.replace("_", " ")
                            textSize = 11.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#7DD3FC"))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(TextView(this@appendWalletLedgerSection).apply {
                            text = "${amtSign}PHP ${"%.2f".format(e.amount)}"
                            textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(amtColor))
                        })
                    })
                    if (!e.bookingRef.isNullOrBlank()) {
                        row.addView(TextView(this@appendWalletLedgerSection).apply {
                            text = "${e.bookingRef} • ${e.serviceTitle ?: e.verticalKey.orEmpty()} • Fare PHP ${"%.2f".format(e.quotedFee)} • Tip PHP ${"%.2f".format(e.tipAmount)} • Comm PHP ${"%.2f".format(e.commissionPhp)}"
                            textSize = 11.5f; setTextColor(Color.parseColor(AlistoUiKit.GOLD_SOFT)); setPadding(0, dp(3), 0, 0)
                        })
                    }
                    if (e.notes.isNotBlank()) {
                        row.addView(TextView(this@appendWalletLedgerSection).apply {
                            text = e.notes
                            textSize = 11.5f; setTextColor(Color.parseColor("#CBD5E1")); setPadding(0, dp(2), 0, 0)
                        })
                    }
                    row.addView(TextView(this@appendWalletLedgerSection).apply {
                        text = "${e.createdAt.take(19).replace("T", " ")}  •  Bal: PHP ${"%.2f".format(e.balanceAfter)}  •  Held: PHP ${"%.2f".format(e.heldAfter)}"
                        textSize = 10.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(3), 0, 0)
                    })
                    listHost.addView(row)
                }
            }
        }.onFailure { err ->
            listHost.addView(TextView(this@appendWalletLedgerSection).apply {
                text = err.message ?: "Unable to load wallet ledger."
                textSize = 12f; setTextColor(Color.parseColor("#FDA4AF"))
            })
        }
    }
}

private fun ContractorMainActivity.exportWalletEntriesCsv(entries: List<WalletLedgerEntry>, user: AuthenticatedUser) {
    if (entries.isEmpty()) {
        Toast.makeText(this, "No wallet activity to download yet.", Toast.LENGTH_SHORT).show()
        return
    }
    fun esc(s: String?) = "\"${(s ?: "").replace("\"", "\"\"")}\""
    val sb = StringBuilder()
    sb.appendLine("Timestamp,Entry Type,Amount PHP,Balance After PHP,Held After PHP,Booking Ref,Service,Payment Method,Fare PHP,Waiting Fee PHP,Tip PHP,Commission PHP,Distance KM,Pickup,Drop-Off,Notes")
    entries.forEach { e ->
        sb.appendLine(
            listOf(
                esc(e.createdAt), esc(e.entryType), "%.2f".format(e.amount), "%.2f".format(e.balanceAfter), "%.2f".format(e.heldAfter),
                esc(e.bookingRef), esc(e.serviceTitle), esc(e.paymentMethod), "%.2f".format(e.quotedFee), "%.2f".format(e.waitingFeePhp),
                "%.2f".format(e.tipAmount), "%.2f".format(e.commissionPhp), e.actualDistanceKm?.let { "%.2f".format(it) } ?: "",
                esc(e.pickupAddress), esc(e.dropoffAddress), esc(e.notes)
            ).joinToString(",")
        )
    }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    val fileName = "alisto_wallet_${user.username}_$stamp.csv"

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
                Toast.makeText(this, "Saved $fileName to Downloads!", Toast.LENGTH_LONG).show()
                return
            }
        }
        // Fallback share sheet for older Android versions
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(share, "Save or Share Wallet Ledger CSV"))
    } catch (e: Exception) {
        Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ==========================================================================
// 8. VERTICAL DISPATCH BOARD (OPEN, PENDING [DELIVERY/RIDES], ONGOING,
//    SCHEDULED, COMPLETED — WITH PICKUP & TRIP DISTANCE + BREAKDOWN MODAL)
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
    val card = AlistoUiKit.surfaceCard(this, AlistoUiKit.BG_CARD, AlistoUiKit.BORDER, padDp = 15)
    val distKm = distanceToPickupKm(b, user)
    val tripDistKm = computeTripDistanceKm(b)
    val isCompleted = b.status in listOf("COMPLETED", "COMPLETED_WITH_DISPUTE")
    val isDisputed = b.status == "COMPLETED_WITH_DISPUTE"
    val (sBg, sFg) = if (isDisputed) "#450A0A" to "#FDA4AF" else AlistoUiKit.statusColors(b.status)

    if (isCompleted) {
        card.setOnClickListener { showCompletedBookingBreakdownModal(b) }
    }

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
        if (tripDistKm != null) append("\nTrip Distance: ${formatTripKm(tripDistKm)} (Pickup → Drop-Off)")
    }
    card.addView(TextView(this).apply {
        text = routeText
        textSize = 12.5f; setTextColor(Color.parseColor(AlistoUiKit.TEXT_MUTED)); setPadding(0, dp(4), 0, dp(8))
    })

    val totalDisplay = b.quotedFee + b.waitingFeePhp + b.tipAmount
    card.addView(LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, "PHP ${"%.2f".format(totalDisplay)}", AlistoUiKit.BG_INPUT, AlistoUiKit.EMERALD).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
        })
        if (b.tipAmount > 0.0) {
            addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, "Tip ₱${"%.0f".format(b.tipAmount)}", AlistoUiKit.BG_INPUT, AlistoUiKit.GOLD_ACCENT).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) }
            })
        }
        addView(AlistoUiKit.badgePill(this@buildBoardBookingCard, b.paymentMethod, AlistoUiKit.BG_INPUT, "#CBD5E1"))
    })

    if (isCompleted) {
        val comm = if (b.finalCommissionPhp > 0.0) b.finalCommissionPhp else b.commissionLocked
        val net = (totalDisplay - comm).coerceAtLeast(0.0)
        card.addView(TextView(this).apply {
            text = "Net Earning: PHP ${"%.2f".format(net)} (Comm: PHP ${"%.2f".format(comm)}) • Tap for full breakdown →"
            textSize = 12f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#7DD3FC"))
        })
    } else if (b.contractorId == user.id && b.status in ACTIVE_JOB_STATUSES) {
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
                                put("pickup_lat", b.pickupLat)
                                put("pickup_lng", b.pickupLng)
                                put("dropoff_lat", b.dropoffLat)
                                put("dropoff_lng", b.dropoffLng)
                                if (b.actualDistanceKm != null) put("actual_distance_km", b.actualDistanceKm)
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
