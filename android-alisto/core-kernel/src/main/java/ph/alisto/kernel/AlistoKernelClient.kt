package ph.alisto.kernel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AuthenticatedUser(
    val id: String,
    val username: String,
    val fullName: String,
    val nickname: String,
    val phone: String,
    val email: String,
    val role: String,
    val accountStatus: String,
    val municipality: String,
    val barangayZone: String,
    val addressText: String,
    val homeLat: Double,
    val homeLng: Double,
    val kycVerified: Boolean,
    val lguPesoEndorsed: Boolean,
    val creditBalance: Double,
    val heldCredits: Double,
    val customCommissionRate: Double?,
    val vipGrindBadge: Boolean,
    val thumbsUpCount: Int = 5,
    val thumbsDownCount: Int = 0,
    val customerTrustScore: Double = 100.0,
    val trustDepositRequired: Boolean = false,
    val commissionArrearsPhp: Double = 0.0,
    val noShowStrikes: Int = 0
) {
    val isDelinquent: Boolean
        get() = trustDepositRequired || commissionArrearsPhp > 0.0 || noShowStrikes >= 2 || customerTrustScore < 70.0
}

data class ContractorProfileState(
    val offersSkills: Boolean,
    val skillTags: List<String>,
    val offersDelivery: Boolean,
    val offersRideHailing: Boolean,
    val vehicleType: String,
    val dispatchRadiusM: Int,
    val flaggedDocs: List<String>,
    val resubmissionNotes: String?,
    var activeShiftVerticals: List<String>,
    var isOnline: Boolean,
    var autoAcceptEnabled: Boolean = false,
    val authorizedPlateNo: String? = null,
    val isElectricVehicle: Boolean = false,
    val engineDisplacementCc: Int = 0,
    val electricMotorKw: Double? = null,
    val ltfrbCompliant: Boolean = false
)

data class ActiveSubscriptionPass(
    val tierCode: String,
    val feePaid: Double,
    val dailyCapMinutes: Int,
    val expiresAt: String
)

data class ServicePricingClass(
    val classCode: String,
    val verticalKey: String,
    val className: String,
    val vehicleOrTradeType: String,
    val dispatchRadiusM: Int,
    val baseOrCalloutFee: Double,
    val baseIncludedKm: Double,
    val perKmRate: Double,
    val economyMultiplier: Double,
    val arrivalCancellationFee: Double,
    val ratePer500m: Double = 0.0,
    val deadheadPerKmRate: Double = 2.0,
    val stopFeePhp: Double = 25.0
) {
    fun computeEffectiveFare(
        distanceKm: Double = 0.0,
        deadheadKm: Double = 0.0,
        extraStops: Int = 0,
        pabiliGoodsPhp: Double = 0.0
    ): Double {
        val mult = economyMultiplier.coerceAtLeast(0.1)
        if (verticalKey == "skills_marketplace") {
            return Math.round(baseOrCalloutFee * mult * 100.0) / 100.0
        }
        val dist = distanceKm.coerceAtLeast(0.0)
        val dh = deadheadKm.coerceAtLeast(0.0)
        val tripCharge = if (ratePer500m > 0.0 || perKmRate > 0.0) {
            when {
                dist <= 0.5 -> if (ratePer500m > 0.0) ratePer500m else baseOrCalloutFee
                dist >= 1.0 -> perKmRate * dist
                else -> maxOf(ratePer500m, perKmRate * dist)
            }
        } else {
            val extraKm = (dist - baseIncludedKm).coerceAtLeast(0.0)
            baseOrCalloutFee + (extraKm * perKmRate)
        }
        val dhCharge = if (dh > 0.0) deadheadPerKmRate * dh else 0.0
        val stopCharge = extraStops.coerceAtLeast(0) * stopFeePhp
        val cashoutAllowance = when {
            pabiliGoodsPhp <= 0.0 || pabiliGoodsPhp >= 500.0 -> 0.0
            pabiliGoodsPhp <= 250.0 -> 10.0
            else -> 15.0
        }
        return Math.round(((tripCharge + dhCharge + stopCharge) * mult + cashoutAllowance) * 100.0) / 100.0
    }
}

data class ServiceVertical(
    val key: String,
    val displayName: String,
    var isEnabled: Boolean,
    val dispatchMode: String,
    val requiresSub400cc: Boolean,
    val description: String
)

data class BookingBidRecord(
    val id: String,
    val bookingId: String,
    val contractorId: String,
    val contractorName: String,
    val contractorUsername: String,
    val contractorRatingAvg: Double,
    val contractorRatingCount: Int,
    val contractorVipBadge: Boolean,
    val reviewHighlights: List<String>,
    val bidAmountPhp: Double,
    val estimatedEtaMin: Int,
    val bidNotes: String,
    val status: String
)

data class BookingRecord(
    val id: String,
    val bookingRef: String,
    val verticalKey: String,
    val jobSubtype: String,
    val clientId: String,
    val contractorId: String?,
    val municipality: String,
    val barangayZone: String,
    val skillCategory: String,
    val serviceTitle: String,
    val pickupAddress: String,
    val dropoffAddress: String,
    val quotedFee: Double,
    val tipAmount: Double,
    var commissionLocked: Double,
    var status: String,
    val clientNickname: String = "Customer",
    val clientThumbsUp: Int = 5,
    val clientThumbsDown: Int = 0,
    val clientTrustScore: Double = 100.0,
    val contractorProfilePhotoUrl: String? = null,
    val contractorPhone: String? = null,
    val contractorVehicleType: String? = null,
    val contractorVehicleMake: String? = null,
    val contractorVehicleModel: String? = null,
    val contractorVehicleColor: String? = null,
    val contractorPlateNo: String? = null,
    val contractorLiveLat: Double? = null,
    val contractorLiveLng: Double? = null,
    val clientLiveLat: Double? = null,
    val clientLiveLng: Double? = null,
    val contractorFullName: String? = null,
    val contractorRatingAvg: Double = 5.0,
    val contractorRatingCount: Int = 1,
    val contractorVipBadge: Boolean = false,
    val pickupLat: Double = 15.1450,
    val pickupLng: Double = 120.5887,
    val dropoffLat: Double = 15.1450,
    val dropoffLng: Double = 120.5887,
    val pickupNote: String = "",
    val dropoffNote: String = "",
    val bookingNotes: String = "",
    val allowAutoAssign: Boolean = true
)

data class ReleaseManifestInfo(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minSupportedVersionCode: Int,
    val customerApkUrl: String,
    val contractorApkUrl: String,
    val releaseNotes: String,
    val requiredPrivacyVersion: String
)

data class ConsentStateInfo(
    val requiredVersion: String,
    val acceptedVersion: String?,
    val requiresConsent: Boolean
)

data class UnreadNotificationRecord(
    val id: String,
    val bookingId: String?,
    val eventType: String,
    val title: String,
    val body: String,
    val createdAt: String
)

data class LiveTelemetryPin(
    val bookingId: String,
    val bookingRef: String,
    val bookingStatus: String,
    val contractorId: String,
    val lat: Double,
    val lng: Double,
    val speedKph: Double,
    val pingTime: String
)

data class HeatmapCluster(
    val label: String,
    val lat: Double,
    val lng: Double,
    val demandCount: Int
)

data class PaymentModeInfo(
    val paymentMode: String = "MANUAL",
    val checkoutEdgeUrl: String = "",
    val manualInstructions: String = "Pay via Alisto Clinic Counter or Official GCash / QR Ph, then submit your reference number for Admin verification.",
    val paymongoConfigured: Boolean = true
)

data class PaymentTxRecord(
    val txRef: String,
    val paymentMode: String,
    val purpose: String,
    val tierCode: String?,
    val amountPhp: Double,
    val providerReference: String,
    val status: String
)

data class LedgerEntry(
    val timestamp: String,
    val entryType: String,
    val amount: String,
    val balanceAfter: Double,
    val heldAfter: Double,
    val notes: String
)

data class ChatMessage(
    val senderName: String,
    val body: String,
    val sentAt: String
)

object AlistoKernelClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    var supabaseUrl: String = BuildConfig.SUPABASE_URL
    var supabaseKey: String = BuildConfig.SUPABASE_ANON_KEY

    var currentSessionToken: String? = null
    var currentSessionUser: AuthenticatedUser? = null
    var currentContractorProfile: ContractorProfileState? = null
    var currentSubscriptionPass: ActiveSubscriptionPass? = null

    val verticals = mutableListOf<ServiceVertical>()
    val pricingClasses = mutableListOf<ServicePricingClass>()
    val bookings = mutableListOf<BookingRecord>()
    val bookingBids = mutableListOf<BookingBidRecord>()
    val ledgerHistory = mutableListOf<LedgerEntry>()
    var currentPaymentModeInfo: PaymentModeInfo = PaymentModeInfo()
    var currentReleaseManifest: ReleaseManifestInfo? = null
    var currentConsentState: ConsentStateInfo? = null
    val paymentTransactions = mutableListOf<PaymentTxRecord>()
    val unreadNotifications = mutableListOf<UnreadNotificationRecord>()
    val liveTelemetryPins = mutableListOf<LiveTelemetryPin>()
    val heatmapClusters = mutableListOf<HeatmapCluster>()
    val chatTranscripts = mutableMapOf<String, MutableList<ChatMessage>>()

    var contractorAvailableCredits: Double = 0.00
    var contractorHeldCredits: Double = 0.00
    var effectiveCommissionRate: Double = 0.12

    private fun postRpc(rpcName: String, payload: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$supabaseUrl/rest/v1/rpc/$rpcName")
            .addHeader("apikey", supabaseKey)
            .addHeader("Authorization", "Bearer $supabaseKey")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty().trim()
            return if (raw.startsWith("{")) JSONObject(raw) else JSONObject().apply {
                put("status", if (resp.isSuccessful) "SUCCESS" else "ERROR")
                put("message", if (raw.isNotBlank()) raw else "HTTP ${resp.code}")
            }
        }
    }

    // Loads full scoped mobile state in one authenticated round-trip under strict RLS
    suspend fun fetchAppBootStateRpc(sessionToken: String, expectedRole: String): Result<AuthenticatedUser> = withContext(Dispatchers.IO) {
        if (sessionToken.length < 32) {
            return@withContext Result.failure(SecurityException("Session token missing or outdated. Please sign in."))
        }
        try {
            val json = postRpc("secure_get_mobile_state_rpc", JSONObject().apply {
                put("p_session_token", sessionToken)
            })
            if (json.optString("status") != "SUCCESS" || json.isNull("user")) {
                return@withContext Result.failure(SecurityException(json.optString("message", "Session expired. Please sign in again.")))
            }

            val u = json.getJSONObject("user")
            val role = u.getString("role")
            if (role != expectedRole && role != "super_admin") {
                return@withContext Result.failure(SecurityException("Portal Isolation: Role [$role] cannot enter [$expectedRole] portal."))
            }

            val customComm = if (u.isNull("custom_commission_rate")) null else u.optDouble("custom_commission_rate")
            val userObj = AuthenticatedUser(
                id = u.getString("id"),
                username = u.optString("username", ""),
                fullName = u.getString("full_name"),
                nickname = u.optString("nickname", u.getString("full_name")),
                phone = u.getString("phone"),
                email = u.optString("email", ""),
                role = role,
                accountStatus = if (u.optBoolean("account_frozen", false)) "SUSPENDED" else u.optString("account_status", "ACTIVE"),
                municipality = u.optString("municipality", "Angeles City"),
                barangayZone = u.optString("barangay_zone", "Sto. Rosario"),
                addressText = u.optString("address_text", ""),
                homeLat = u.optDouble("home_lat", 15.1450),
                homeLng = u.optDouble("home_lng", 120.5887),
                kycVerified = u.optBoolean("kyc_verified", true),
                lguPesoEndorsed = u.optBoolean("lgu_peso_endorsed", false),
                creditBalance = u.optDouble("credit_balance", 0.0),
                heldCredits = u.optDouble("held_credits", 0.0),
                customCommissionRate = customComm,
                vipGrindBadge = u.optBoolean("vip_grind_badge", false),
                thumbsUpCount = u.optInt("thumbs_up_count", 5),
                thumbsDownCount = u.optInt("thumbs_down_count", 0),
                customerTrustScore = u.optDouble("customer_trust_score", 100.0),
                trustDepositRequired = u.optBoolean("trust_deposit_required", false),
                commissionArrearsPhp = u.optDouble("commission_arrears_php", 0.0),
                noShowStrikes = u.optInt("no_show_strikes", 0)
            )
            currentSessionToken = sessionToken
            currentSessionUser = userObj
            contractorAvailableCredits = userObj.creditBalance
            contractorHeldCredits = userObj.heldCredits

            if (!json.isNull("contractor_profile")) {
                val cp = json.getJSONObject("contractor_profile")
                val skills = mutableListOf<String>()
                cp.optJSONArray("skill_tags")?.let { arr -> for (i in 0 until arr.length()) skills.add(arr.getString(i)) }
                val flagged = mutableListOf<String>()
                cp.optJSONArray("flagged_docs")?.let { arr -> for (i in 0 until arr.length()) flagged.add(arr.getString(i)) }
                val shifts = mutableListOf<String>()
                cp.optJSONArray("active_shift_verticals")?.let { arr -> for (i in 0 until arr.length()) shifts.add(arr.getString(i)) }
                currentContractorProfile = ContractorProfileState(
                    offersSkills = cp.optBoolean("offers_skills", true),
                    skillTags = skills,
                    offersDelivery = cp.optBoolean("offers_delivery", false),
                    offersRideHailing = cp.optBoolean("offers_ride_hailing", false),
                    vehicleType = cp.optString("vehicle_type", "motorcycle"),
                    dispatchRadiusM = cp.optInt("dispatch_radius_m", 1500),
                    flaggedDocs = flagged,
                    resubmissionNotes = if (cp.isNull("resubmission_notes")) null else cp.optString("resubmission_notes"),
                    activeShiftVerticals = if (shifts.isEmpty()) listOf("skills_marketplace") else shifts,
                    isOnline = cp.optBoolean("is_online", true),
                    autoAcceptEnabled = cp.optBoolean("auto_accept_enabled", false),
                    authorizedPlateNo = if (cp.isNull("authorized_plate_no")) null else cp.optString("authorized_plate_no"),
                    isElectricVehicle = cp.optBoolean("is_electric_vehicle", false),
                    engineDisplacementCc = cp.optInt("engine_displacement_cc", 0),
                    electricMotorKw = if (cp.isNull("electric_motor_kw")) null else cp.optDouble("electric_motor_kw"),
                    ltfrbCompliant = cp.optBoolean("ltfrb_compliant", false)
                )
            } else {
                currentContractorProfile = null
            }

            if (!json.isNull("active_subscription")) {
                val sub = json.getJSONObject("active_subscription")
                currentSubscriptionPass = ActiveSubscriptionPass(
                    tierCode = sub.getString("tier_code"),
                    feePaid = sub.optDouble("fee_paid", 0.0),
                    dailyCapMinutes = sub.optInt("daily_cap_minutes", 1440),
                    expiresAt = sub.optString("expires_at", "")
                )
                effectiveCommissionRate = 0.00
            } else {
                currentSubscriptionPass = null
                effectiveCommissionRate = customComm ?: 0.12
            }

            hydrateCollectionsFromSnapshot(json)
            return@withContext Result.success(userObj)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    private fun hydrateCollectionsFromSnapshot(json: JSONObject) {
        json.optJSONArray("verticals_list")?.let { arr ->
            verticals.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                verticals.add(
                    ServiceVertical(
                        key = o.getString("vertical_key"),
                        displayName = o.getString("display_name"),
                        isEnabled = o.getBoolean("is_enabled"),
                        dispatchMode = o.getString("dispatch_mode"),
                        requiresSub400cc = o.optBoolean("requires_sub_400cc_routing", false),
                        description = o.optString("description", "")
                    )
                )
            }
        }

        json.optJSONArray("pricing_matrix")?.let { arr ->
            pricingClasses.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                pricingClasses.add(
                    ServicePricingClass(
                        classCode = o.getString("class_code"),
                        verticalKey = o.getString("vertical_key"),
                        className = o.getString("class_name"),
                        vehicleOrTradeType = o.optString("vehicle_or_trade_type", ""),
                        dispatchRadiusM = o.optInt("dispatch_radius_m", 1500),
                        baseOrCalloutFee = o.optDouble("base_or_callout_fee", 350.0),
                        baseIncludedKm = o.optDouble("base_included_km", 0.0),
                        perKmRate = o.optDouble("per_km_rate", 0.0),
                        economyMultiplier = o.optDouble("economy_multiplier", 1.0),
                        arrivalCancellationFee = o.optDouble("arrival_cancellation_fee", 100.0),
                        ratePer500m = o.optDouble("rate_per_500m", 0.0),
                        deadheadPerKmRate = if (o.isNull("deadhead_per_km_rate") || !o.has("deadhead_per_km_rate")) 2.0 else o.optDouble("deadhead_per_km_rate", 2.0),
                        stopFeePhp = if (o.isNull("stop_fee_php") || !o.has("stop_fee_php")) 25.0 else o.optDouble("stop_fee_php", 25.0)
                    )
                )
            }
        }

        json.optJSONArray("bookings")?.let { arr ->
            bookings.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                bookings.add(
                    BookingRecord(
                        id = o.getString("id"),
                        bookingRef = o.getString("booking_ref"),
                        verticalKey = o.getString("vertical_key"),
                        jobSubtype = o.optString("job_subtype", "ON_SITE_SKILL"),
                        clientId = o.getString("client_id"),
                        contractorId = if (o.isNull("contractor_id")) null else o.getString("contractor_id"),
                        municipality = o.optString("municipality", "Angeles City"),
                        barangayZone = o.getString("barangay_zone"),
                        skillCategory = o.optString("skill_category", "General Service"),
                        serviceTitle = o.getString("service_title"),
                        pickupAddress = o.getString("pickup_address"),
                        dropoffAddress = o.optString("dropoff_address", ""),
                        quotedFee = o.getDouble("quoted_fee"),
                        tipAmount = o.optDouble("tip_amount", 0.0),
                        commissionLocked = o.optDouble("commission_locked", 0.0),
                        status = o.getString("status"),
                        clientNickname = o.optString("client_nickname", "Customer"),
                        clientThumbsUp = o.optInt("client_thumbs_up", 5),
                        clientThumbsDown = o.optInt("client_thumbs_down", 0),
                        clientTrustScore = o.optDouble("client_trust_score", 100.0),
                        contractorFullName = if (o.isNull("contractor_full_name")) null else o.optString("contractor_full_name"),
                        contractorRatingAvg = o.optDouble("contractor_rating_avg", 5.0),
                        contractorRatingCount = o.optInt("contractor_rating_count", 1),
                        contractorVipBadge = o.optBoolean("contractor_vip_badge", false),
                        contractorProfilePhotoUrl = if (o.isNull("contractor_profile_photo_url")) null else o.optString("contractor_profile_photo_url").ifBlank { null },
                        contractorPhone = if (o.isNull("contractor_phone")) null else o.optString("contractor_phone").ifBlank { null },
                        contractorVehicleType = if (o.isNull("contractor_vehicle_type")) null else o.optString("contractor_vehicle_type").ifBlank { null },
                        contractorVehicleMake = if (o.isNull("contractor_vehicle_make")) null else o.optString("contractor_vehicle_make").ifBlank { null },
                        contractorVehicleModel = if (o.isNull("contractor_vehicle_model")) null else o.optString("contractor_vehicle_model").ifBlank { null },
                        contractorVehicleColor = if (o.isNull("contractor_vehicle_color")) null else o.optString("contractor_vehicle_color").ifBlank { null },
                        contractorPlateNo = if (o.isNull("contractor_plate_no")) null else o.optString("contractor_plate_no").ifBlank { null },
                        contractorLiveLat = if (o.isNull("contractor_live_lat") || !o.has("contractor_live_lat")) null else o.optDouble("contractor_live_lat"),
                        contractorLiveLng = if (o.isNull("contractor_live_lng") || !o.has("contractor_live_lng")) null else o.optDouble("contractor_live_lng"),
                        clientLiveLat = if (o.isNull("client_live_lat") || !o.has("client_live_lat")) null else o.optDouble("client_live_lat"),
                        clientLiveLng = if (o.isNull("client_live_lng") || !o.has("client_live_lng")) null else o.optDouble("client_live_lng"),
                        pickupLat = o.optDouble("pickup_lat", 15.1450),
                        pickupLng = o.optDouble("pickup_lng", 120.5887),
                        dropoffLat = o.optDouble("dropoff_lat", o.optDouble("pickup_lat", 15.1450)),
                        dropoffLng = o.optDouble("dropoff_lng", o.optDouble("pickup_lng", 120.5887)),
                        pickupNote = extractNoteTag(o, "pickup_note", "PICKUP_NOTE"),
                        dropoffNote = extractNoteTag(o, "dropoff_note", "DROPOFF_NOTE"),
                        bookingNotes = extractNoteTag(o, "booking_notes", "BOOKING_NOTE"),
                        allowAutoAssign = if (o.has("allow_auto_assign")) {
                            o.optBoolean("allow_auto_assign", true)
                        } else {
                            extractNoteTag(o, "booking_notes", "BOOKING_NOTE").isBlank()
                        }
                    )
                )
            }
        }

        json.optJSONArray("booking_bids")?.let { arr ->
            bookingBids.clear()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val highlights = mutableListOf<String>()
                b.optJSONArray("review_highlights")?.let { hArr ->
                    for (k in 0 until hArr.length()) highlights.add(hArr.getString(k))
                }
                bookingBids.add(
                    BookingBidRecord(
                        id = b.getString("id"),
                        bookingId = b.getString("booking_id"),
                        contractorId = b.getString("contractor_id"),
                        contractorName = b.optString("contractor_name", "Verified Contractor"),
                        contractorUsername = b.optString("contractor_username", "contractor"),
                        contractorRatingAvg = b.optDouble("contractor_rating_avg", 5.0),
                        contractorRatingCount = b.optInt("contractor_rating_count", 1),
                        contractorVipBadge = b.optBoolean("contractor_vip_badge", false),
                        reviewHighlights = highlights,
                        bidAmountPhp = b.getDouble("bid_amount_php"),
                        estimatedEtaMin = b.optInt("estimated_eta_min", 20),
                        bidNotes = b.optString("bid_notes", ""),
                        status = b.optString("status", "ACTIVE")
                    )
                )
            }
        }

        json.optJSONObject("release_manifest")?.let { rel ->
            currentReleaseManifest = ReleaseManifestInfo(
                latestVersionCode = rel.optInt("latest_version_code", 4),
                latestVersionName = rel.optString("latest_version_name", "4.0.0-api36"),
                minSupportedVersionCode = rel.optInt("min_supported_version_code", 1),
                customerApkUrl = rel.optString("customer_apk_url", ""),
                contractorApkUrl = rel.optString("contractor_apk_url", ""),
                releaseNotes = rel.optString("release_notes", ""),
                requiredPrivacyVersion = rel.optString("required_privacy_version", "RA10173-2026-v1")
            )
        }

        json.optJSONObject("consent_state")?.let { cs ->
            currentConsentState = ConsentStateInfo(
                requiredVersion = cs.optString("required_version", "RA10173-2026-v1"),
                acceptedVersion = if (cs.isNull("accepted_version")) null else cs.optString("accepted_version"),
                requiresConsent = cs.optBoolean("requires_consent", false)
            )
        }

        json.optJSONObject("payment_mode_info")?.let { pm ->
            currentPaymentModeInfo = PaymentModeInfo(
                paymentMode = pm.optString("payment_mode", "MANUAL"),
                checkoutEdgeUrl = pm.optString("checkout_edge_url", ""),
                manualInstructions = pm.optString("manual_payment_instructions", "Submit your payment receipt reference for Admin verification."),
                paymongoConfigured = pm.optBoolean("paymongo_configured", true)
            )
        }

        json.optJSONArray("payment_transactions")?.let { arr ->
            paymentTransactions.clear()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                paymentTransactions.add(
                    PaymentTxRecord(
                        txRef = t.optString("tx_ref", ""),
                        paymentMode = t.optString("payment_mode", "MANUAL"),
                        purpose = t.optString("purpose", "WALLET_TOPUP"),
                        tierCode = if (t.isNull("tier_code")) null else t.optString("tier_code"),
                        amountPhp = t.optDouble("amount_php", 0.0),
                        providerReference = t.optString("provider_reference", "—"),
                        status = t.optString("status", "PENDING")
                    )
                )
            }
        }

        json.optJSONArray("unread_notifications")?.let { arr ->
            unreadNotifications.clear()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                unreadNotifications.add(
                    UnreadNotificationRecord(
                        id = n.getString("id"),
                        bookingId = if (n.isNull("booking_id")) null else n.optString("booking_id"),
                        eventType = n.optString("event_type", "ALERT"),
                        title = n.optString("title", "Alisto Alert"),
                        body = n.optString("body", ""),
                        createdAt = n.optString("created_at", "")
                    )
                )
            }
        }

        json.optJSONArray("live_telemetry_pins")?.let { arr ->
            liveTelemetryPins.clear()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                liveTelemetryPins.add(
                    LiveTelemetryPin(
                        bookingId = p.getString("booking_id"),
                        bookingRef = p.optString("booking_ref", ""),
                        bookingStatus = p.optString("booking_status", "ON_THE_WAY"),
                        contractorId = p.optString("contractor_id", ""),
                        lat = p.optDouble("lat", 15.1450),
                        lng = p.optDouble("lng", 120.5887),
                        speedKph = p.optDouble("speed_kph", 0.0),
                        pingTime = p.optString("ping_time", "")
                    )
                )
            }
        }

        json.optJSONArray("heatmap_clusters")?.let { arr ->
            heatmapClusters.clear()
            for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                val lat = when {
                    h.has("centroid_lat") -> h.optDouble("centroid_lat", 15.1450)
                    h.has("center_lat") -> h.optDouble("center_lat", 15.1450)
                    h.has("avg_lat") -> h.optDouble("avg_lat", 15.1450)
                    else -> h.optDouble("lat", 15.1450)
                }
                val lng = when {
                    h.has("centroid_lng") -> h.optDouble("centroid_lng", 120.5887)
                    h.has("center_lng") -> h.optDouble("center_lng", 120.5887)
                    h.has("avg_lng") -> h.optDouble("avg_lng", 120.5887)
                    else -> h.optDouble("lng", 120.5887)
                }
                val demand = when {
                    h.has("open_requests") -> h.optInt("open_requests", 1)
                    h.has("active_demand") -> h.optInt("active_demand", 1)
                    h.has("request_count") -> h.optInt("request_count", 1)
                    else -> h.optInt("booking_count", 1)
                }
                val lbl = h.optString("barangay_zone", h.optString("municipality", "Active Zone"))
                heatmapClusters.add(HeatmapCluster(lbl, lat, lng, demand))
            }
        }

        json.optJSONArray("ledger_history")?.let { arr ->
            ledgerHistory.clear()
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                ledgerHistory.add(
                    LedgerEntry(
                        timestamp = l.optString("created_at", "Recent"),
                        entryType = l.getString("entry_type"),
                        amount = l.getDouble("amount").toString(),
                        balanceAfter = l.getDouble("balance_after"),
                        heldAfter = l.getDouble("held_after"),
                        notes = l.optString("notes", "")
                    )
                )
            }
        }
    }

    suspend fun syncBookingsAndWallet() = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext
        val role = currentSessionUser?.role ?: "customer"
        fetchAppBootStateRpc(tok, if (role == "super_admin") "customer" else role)
    }

    suspend fun authenticateUserRpc(
        identifier: String,
        password: String,
        expectedAppRole: String
    ): Result<AuthenticatedUser> = withContext(Dispatchers.IO) {
        try {
            val json = postRpc("authenticate_user_secure_rpc", JSONObject().apply {
                put("p_identifier", identifier.trim())
                put("p_password", password)
                put("p_device_label", "android_${expectedAppRole}_apk")
            })
            if (json.optString("status") != "SUCCESS" || !json.has("user")) {
                return@withContext Result.failure(SecurityException(json.optString("message", "Invalid credentials.")))
            }
            val u = json.getJSONObject("user")
            val role = u.getString("role")
            if (role != expectedAppRole && role != "super_admin") {
                return@withContext Result.failure(
                    SecurityException("PORTAL ISOLATION BLOCK: Account role [$role] cannot sign into the [$expectedAppRole] app.")
                )
            }
            val token = json.optString("session_token", "")
            if (token.length < 32) {
                return@withContext Result.failure(SecurityException("Server failed to mint session token."))
            }
            return@withContext fetchAppBootStateRpc(token, expectedAppRole)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun registerCustomerPortalRpc(
        fullName: String,
        nickname: String,
        phone: String,
        email: String,
        password: String,
        municipality: String,
        barangayZone: String,
        addressText: String,
        homeLat: Double,
        homeLng: Double,
        profilePhotoUrl: String?
    ): Result<AuthenticatedUser> = withContext(Dispatchers.IO) {
        try {
            val json = postRpc("register_customer_portal_rpc", JSONObject().apply {
                put("p_full_name", fullName)
                put("p_nickname", nickname)
                put("p_phone", phone)
                put("p_email", email)
                put("p_password", password)
                put("p_municipality", municipality)
                put("p_barangay_zone", barangayZone)
                put("p_address_text", addressText)
                put("p_home_lat", homeLat)
                put("p_home_lng", homeLng)
                put("p_profile_photo_url", profilePhotoUrl)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Customer sign-up failed.")))
            }
            return@withContext authenticateUserRpc(phone.ifBlank { email }, password, "customer")
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun registerContractorPortalRpc(
        firstName: String,
        lastName: String,
        birthDate: String,
        phone: String,
        email: String,
        password: String,
        municipality: String,
        barangayZone: String,
        addressText: String,
        homeLat: Double,
        homeLng: Double,
        offersSkills: Boolean,
        skillTags: List<String>,
        offersDelivery: Boolean,
        offersRideHailing: Boolean,
        vehicleType: String,
        profilePhotoUrl: String?,
        licenseDocUrl: String?,
        orcrDocUrl: String?,
        vehiclePhotoUrl: String?
    ): Result<AuthenticatedUser> = withContext(Dispatchers.IO) {
        try {
            val json = postRpc("register_contractor_portal_rpc", JSONObject().apply {
                put("p_first_name", firstName)
                put("p_last_name", lastName)
                put("p_birth_date", birthDate)
                put("p_phone", phone)
                put("p_email", email)
                put("p_password", password)
                put("p_municipality", municipality)
                put("p_barangay_zone", barangayZone)
                put("p_address_text", addressText)
                put("p_home_lat", homeLat)
                put("p_home_lng", homeLng)
                put("p_offers_skills", offersSkills)
                put("p_skill_tags", JSONArray(skillTags))
                put("p_offers_delivery", offersDelivery)
                put("p_offers_ride_hailing", offersRideHailing)
                put("p_vehicle_type", vehicleType)
                put("p_profile_photo_url", profilePhotoUrl)
                put("p_license_doc_url", licenseDocUrl)
                put("p_orcr_doc_url", orcrDocUrl)
                put("p_vehicle_photo_url", vehiclePhotoUrl)
                put("p_skill_cert_urls", JSONArray())
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Contractor sign-up failed.")))
            }
            return@withContext authenticateUserRpc(phone.ifBlank { email }, password, "contractor")
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun purchaseSubscriptionPassRpc(tierCode: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session token missing"))
        try {
            val json = postRpc("secure_purchase_subscription_pass_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_contractor_id", activeUser.id)
                put("p_tier_code", tierCode)
                put("p_payment_ref", "APK-PASS-${System.currentTimeMillis()}")
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Pass activation requires Admin clinic or verified PayMongo settlement.")))
            }
            fetchAppBootStateRpc(tok, "contractor")
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    fun signOut() {
        val tok = currentSessionToken
        if (!tok.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    postRpc("logout_session_rpc", JSONObject().apply { put("p_session_token", tok) })
                } catch (_: Exception) {}
            }
        }
        currentSessionToken = null
        currentSessionUser = null
        currentContractorProfile = null
        currentSubscriptionPass = null
        bookings.clear()
        bookingBids.clear()
        ledgerHistory.clear()
        chatTranscripts.clear()
    }

    suspend fun claimBookingRpc(bookingId: String): Result<BookingRecord> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            val json = postRpc("claim_booking_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", activeUser.id)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to claim job.")))
            }
            fetchAppBootStateRpc(tok, "contractor")
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("Claim failed."))
    }

    suspend fun advanceBookingStateRpc(bookingId: String, nextStatus: String): Result<BookingRecord> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            val json = postRpc("transition_booking_status_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_actor_id", activeUser.id)
                put("p_next_status", nextStatus)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Transition failed.")))
            }
            fetchAppBootStateRpc(tok, "contractor")
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("State transition error."))
    }

    suspend fun topUpCreditsQrPhRpc(amount: Double): Result<Double> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            val json = postRpc("secure_topup_prepaid_credits_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_target_user_id", activeUser.id)
                put("p_amount", amount)
                put("p_qrph_ref", "QRPH-${System.currentTimeMillis()}")
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Wallet top-ups require Admin clinic or verified PayMongo QR Ph settlement.")))
            }
            val bonusGranted = json.optDouble("bonus_granted", 0.0)
            fetchAppBootStateRpc(tok, "contractor")
            return@withContext Result.success(bonusGranted)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun sendModeratedChatMessageRpc(bookingId: String, messageBody: String): Result<ChatMessage> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val json = postRpc("send_booking_message_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_sender_id", activeUser.id)
                put("p_message_body", messageBody)
            })
            val status = json.optString("status")
            if (status == "BLOCKED_MODERATION" || status == "LOCKED_STATE" || status == "ERROR") {
                return@withContext Result.failure(SecurityException(json.optString("message", "Message blocked by moderation trigger.")))
            }
            val msg = ChatMessage(activeUser.nickname, messageBody.trim(), "Live")
            chatTranscripts.getOrPut(bookingId) { mutableListOf() }.add(msg)
            return@withContext Result.success(msg)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    fun recordTelemetryPingRpc(
        bookingId: String,
        contractorId: String,
        lat: Double = 15.1450 + (Math.random() * 0.01),
        lng: Double = 120.5887 + (Math.random() * 0.01),
        speedKph: Double = 26.5
    ) {
        try {
            postRpc("record_booking_telemetry_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", contractorId)
                put("p_lat", lat)
                put("p_lng", lng)
                put("p_speed_kph", speedKph)
                put("p_heading", 45.0)
            })
        } catch (_: Exception) {}
    }

    suspend fun createBookingV2Rpc(
        verticalKey: String,
        jobSubtype: String,
        municipality: String,
        barangayZone: String,
        skillCategory: String,
        serviceTitle: String,
        pickupAddress: String,
        dropoffAddress: String,
        waypointsJson: JSONArray,
        quotedFee: Double,
        tipAmount: Double,
        errandItemCostPhp: Double,
        deadheadReturnKm: Double,
        pickupLat: Double? = null,
        pickupLng: Double? = null,
        dropoffLat: Double? = null,
        dropoffLng: Double? = null,
        pickupNote: String = "",
        dropoffNote: String = "",
        bookingNotes: String = "",
        vehicleType: String = "",
        paymentMethod: String = "CASH"
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            fun cleanTag(v: String) = v.trim().replace("]", ")").replace("[", "(")
            val noteParts = mutableListOf("Nationwide Local-Road (avoid=ht) • Client: ${activeUser.nickname}")
            if (pickupNote.isNotBlank()) noteParts.add("[PICKUP_NOTE:${cleanTag(pickupNote)}]")
            if (dropoffNote.isNotBlank()) noteParts.add("[DROPOFF_NOTE:${cleanTag(dropoffNote)}]")
            if (bookingNotes.isNotBlank()) noteParts.add("[BOOKING_NOTE:${cleanTag(bookingNotes)}]")
            if (vehicleType.isNotBlank()) noteParts.add("[VEHICLE:${cleanTag(vehicleType)}]")

            val pLat = pickupLat ?: activeUser.homeLat
            val pLng = pickupLng ?: activeUser.homeLng
            val dLat = dropoffLat ?: pLat
            val dLng = dropoffLng ?: pLng

            val json = postRpc("create_booking_v2_rpc", JSONObject().apply {
                put("p_client_id", activeUser.id)
                put("p_vertical_key", verticalKey)
                put("p_job_subtype", jobSubtype)
                put("p_municipality", municipality)
                put("p_barangay_zone", barangayZone)
                put("p_skill_category", skillCategory)
                put("p_service_title", serviceTitle)
                put("p_service_notes", noteParts.joinToString(" "))
                put("p_pickup_address", pickupAddress)
                put("p_dropoff_address", dropoffAddress)
                put("p_pickup_lat", pLat)
                put("p_pickup_lng", pLng)
                put("p_dropoff_lat", dLat)
                put("p_dropoff_lng", dLng)
                put("p_waypoints", waypointsJson)
                put("p_quoted_fee", quotedFee)
                put("p_tip_amount", tipAmount)
                put("p_errand_item_cost", errandItemCostPhp)
                put("p_deadhead_return_km", deadheadReturnKm)
                put("p_payment_method", paymentMethod)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Booking blocked.")))
            }
            syncBookingsAndWallet()
            return@withContext Result.success(json)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun updateLiveLocationRpc(
        lat: Double,
        lng: Double,
        activeBookingId: String? = null
    ) = withContext(Dispatchers.IO) {
        val token = currentSessionToken ?: return@withContext
        try {
            postRpc("secure_update_live_location_rpc", JSONObject().apply {
                put("p_session_token", token)
                put("p_lat", lat)
                put("p_lng", lng)
                if (!activeBookingId.isNullOrBlank()) put("p_booking_id", activeBookingId)
            })
        } catch (_: Exception) {}
    }

    suspend fun submitContractorBidRpc(
        bookingId: String,
        bidAmountPhp: Double,
        etaMin: Int,
        notes: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val json = postRpc("submit_contractor_bid_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", activeUser.id)
                put("p_bid_amount_php", bidAmountPhp)
                put("p_eta_min", etaMin)
                put("p_notes", notes)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Pre-bid wallet check failed.")))
            }
            syncBookingsAndWallet()
            return@withContext Result.success(json)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun withdrawContractorBidRpc(bookingId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val json = postRpc("withdraw_contractor_bid_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", activeUser.id)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to withdraw bid.")))
            }
            syncBookingsAndWallet()
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun acceptContractorBidRpc(bookingId: String, bidId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val json = postRpc("accept_contractor_bid_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_bid_id", bidId)
                put("p_client_id", activeUser.id)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to accept bid.")))
            }
            syncBookingsAndWallet()
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun submitCustomerStarRatingRpc(
        bookingId: String,
        contractorId: String,
        stars: Int,
        tags: List<String>,
        comments: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val json = postRpc("submit_customer_star_rating_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_client_id", activeUser.id)
                put("p_contractor_id", contractorId)
                put("p_stars", stars)
                put("p_tags", JSONArray(tags))
                put("p_comments", comments)
            })
            syncBookingsAndWallet()
            return@withContext Result.success(json)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun toggleContractorDispatchModeRpc(
        isOnline: Boolean,
        autoAccept: Boolean,
        activeShiftVerticals: List<String>? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            postRpc("toggle_contractor_dispatch_mode_rpc", JSONObject().apply {
                put("p_contractor_id", activeUser.id)
                put("p_is_online", isOnline)
                put("p_auto_accept", autoAccept)
                if (activeShiftVerticals != null) {
                    put("p_active_shift_verticals", JSONArray(activeShiftVerticals))
                }
            })
            fetchAppBootStateRpc(tok, "contractor")
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun executeMutualOrNoShowCancelRpc(
        bookingId: String,
        cancelMode: String,
        reason: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            val json = postRpc("secure_cancel_booking_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_booking_id", bookingId)
                put("p_cancel_mode", cancelMode)
                put("p_reason", reason)
            })
            syncBookingsAndWallet()
            return@withContext Result.success(json)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun submitContractorThumbReviewRpc(
        bookingId: String,
        thumbVote: String,
        feedbackNote: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val json = postRpc("submit_contractor_thumb_review_rpc", JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", activeUser.id)
                put("p_thumb_vote", thumbVote)
                put("p_feedback_note", feedbackNote)
            })
            return@withContext Result.success(json)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun submitManualPaymentRequestRpc(
        purpose: String,
        amountPhp: Double,
        tierCode: String?,
        referenceNo: String,
        notes: String = "Manual receipt submitted via Contractor APK"
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            val json = postRpc("secure_submit_manual_payment_request_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_purpose", purpose)
                put("p_amount_php", amountPhp)
                put("p_tier_code", tierCode ?: JSONObject.NULL)
                put("p_reference_no", referenceNo.trim())
                put("p_notes", notes)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Manual payment submission failed.")))
            }
            fetchAppBootStateRpc(tok, currentSessionUser?.role ?: "contractor")
            return@withContext Result.success(json)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun initiatePayMongoCheckout(
        purpose: String,
        amountPhp: Double,
        tierCode: String?
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        val edgeUrl = currentPaymentModeInfo.checkoutEdgeUrl
        if (edgeUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("PayMongo Checkout Edge URL is not configured."))
        }
        try {
            val payload = JSONObject().apply {
                put("session_token", tok)
                put("purpose", purpose)
                put("amount_php", amountPhp)
                if (!tierCode.isNullOrBlank()) put("tier_code", tierCode)
            }
            val req = Request.Builder()
                .url(edgeUrl)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty().trim()
                val json = if (raw.startsWith("{")) JSONObject(raw) else JSONObject().apply {
                    put("status", "ERROR")
                    put("message", if (raw.isNotBlank()) raw else "HTTP ${resp.code}")
                }
                if (json.optString("status") != "SUCCESS" || json.optString("checkout_url").isBlank()) {
                    return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to start PayMongo checkout.")))
                }
                fetchAppBootStateRpc(tok, currentSessionUser?.role ?: "contractor")
                return@withContext Result.success(json)
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }


    suspend fun markNotificationsReadRpc(notificationIds: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            postRpc("secure_mark_notifications_read_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_notification_ids", JSONArray(notificationIds))
            })
            unreadNotifications.removeAll { it.id in notificationIds }
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun registerDeviceTokenRpc(fcmToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            postRpc("secure_register_device_token_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_fcm_token", fcmToken)
            })
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }


    suspend fun recordPrivacyConsentRpc(
        consentVersion: String?,
        deviceLabel: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            val json = postRpc("secure_record_privacy_consent_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_consent_version", consentVersion ?: "RA10173-2026-v1")
                put("p_device_label", deviceLabel)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to record consent.")))
            }
            val accepted = json.optString("consent_version", consentVersion ?: "RA10173-2026-v1")
            currentConsentState = ConsentStateInfo(
                requiredVersion = accepted,
                acceptedVersion = accepted,
                requiresConsent = false
            )
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }


    suspend fun logSafetyRefusalRpc(
        bookingId: String,
        refusalCategory: String,
        notes: String,
        lat: Double? = null,
        lng: Double? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val tok = currentSessionToken ?: return@withContext Result.failure(SecurityException("Session expired."))
        try {
            val json = postRpc("secure_log_safety_refusal_rpc", JSONObject().apply {
                put("p_session_token", tok)
                put("p_booking_id", bookingId)
                put("p_refusal_category", refusalCategory)
                put("p_notes", notes)
                if (lat != null) put("p_lat", lat)
                if (lng != null) put("p_lng", lng)
            })
            if (json.optString("status") != "SUCCESS") {
                return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to log LTFRB safety refusal.")))
            }
            fetchAppBootStateRpc(tok, currentSessionUser?.role ?: "contractor")
            return@withContext Result.success(json)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }


    private fun extractNoteTag(o: JSONObject, colKey: String, tagKey: String): String {
        val direct = o.optString(colKey, "").trim()
        if (direct.isNotEmpty() && direct != "null") return direct
        val raw = o.optString("service_notes", "")
        val prefix = "[$tagKey:"
        val start = raw.indexOf(prefix)
        if (start == -1) return ""
        val after = start + prefix.length
        val end = raw.indexOf("]", after)
        return if (end == -1) "" else raw.substring(after, end).trim()
    }


    fun findPricingClass(verticalKey: String, vehicleType: String = ""): ServicePricingClass? {
        val byVertical = pricingClasses.filter { it.verticalKey == verticalKey }
        if (byVertical.isEmpty()) return null
        val v = vehicleType.trim().lowercase()
        if (v.isNotEmpty()) {
            val tokens = when {
                v.contains("tri") -> listOf("tricycle", "trike")
                v.contains("bike") || v.contains("ebike") -> listOf("e-bike", "ebike", "electric")
                else -> listOf("motorcycle", "moto", "scooter", "2w")
            }
            byVertical.firstOrNull { pc ->
                val hay = "${pc.vehicleOrTradeType} ${pc.className} ${pc.classCode}".lowercase()
                tokens.any { hay.contains(it) }
            }?.let { return it }
        }
        return byVertical.firstOrNull()
    }

}
