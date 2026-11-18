package ph.alisto.kernel

import kotlinx.coroutines.Dispatchers
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
    val vipGrindBadge: Boolean
)

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
    var isOnline: Boolean
)

data class ActiveSubscriptionPass(
    val tierCode: String,
    val feePaid: Double,
    val dailyCapMinutes: Int,
    val expiresAt: String
)

data class ServiceVertical(
    val key: String,
    val displayName: String,
    var isEnabled: Boolean,
    val dispatchMode: String,
    val requiresSub400cc: Boolean,
    val description: String
)

data class BookingRecord(
    val id: String,
    val bookingRef: String,
    val verticalKey: String,
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
    var status: String
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

    var currentSessionUser: AuthenticatedUser? = null
    var currentContractorProfile: ContractorProfileState? = null
    var currentSubscriptionPass: ActiveSubscriptionPass? = null

    val verticals = mutableListOf<ServiceVertical>()
    val bookings = mutableListOf<BookingRecord>()
    val ledgerHistory = mutableListOf<LedgerEntry>()
    val chatTranscripts = mutableMapOf<String, MutableList<ChatMessage>>()

    var contractorAvailableCredits: Double = 0.00
    var contractorHeldCredits: Double = 0.00
    var effectiveCommissionRate: Double = 0.12

    /**
     * Single-Trip App Boot State RPC (Section 6.4 of V2 Spec)
     * Invoked immediately after Android Keystore BiometricPrompt unlock.
     */
    suspend fun fetchAppBootStateRpc(userId: String, expectedRole: String): Result<AuthenticatedUser> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("p_user_id", userId) }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/get_app_boot_state")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") != "SUCCESS" || json.isNull("user")) {
                    return@withContext Result.failure(IllegalStateException("Boot state user not found."))
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
                    vipGrindBadge = u.optBoolean("vip_grind_badge", false)
                )
                currentSessionUser = userObj
                contractorAvailableCredits = userObj.creditBalance
                contractorHeldCredits = userObj.heldCredits

                if (!json.isNull("contractor_profile")) {
                    val cp = json.getJSONObject("contractor_profile")
                    val skills = mutableListOf<String>()
                    cp.optJSONArray("skill_tags")?.let { arr ->
                        for (i in 0 until arr.length()) skills.add(arr.getString(i))
                    }
                    val flagged = mutableListOf<String>()
                    cp.optJSONArray("flagged_docs")?.let { arr ->
                        for (i in 0 until arr.length()) flagged.add(arr.getString(i))
                    }
                    val shifts = mutableListOf<String>()
                    cp.optJSONArray("active_shift_verticals")?.let { arr ->
                        for (i in 0 until arr.length()) shifts.add(arr.getString(i))
                    }
                    currentContractorProfile = ContractorProfileState(
                        offersSkills = cp.optBoolean("offers_skills", true),
                        skillTags = skills,
                        offersDelivery = cp.optBoolean("offers_delivery", false),
                        offersRideHailing = cp.optBoolean("offers_ride_hailing", false),
                        vehicleType = cp.optString("vehicle_type", "motorcycle"),
                        dispatchRadiusM = cp.optInt("dispatch_radius_m", 1500),
                        flaggedDocs = flagged,
                        resubmissionNotes = if (cp.isNull("resubmission_notes")) null else cp.optString("resubmission_notes"),
                        activeShiftVerticals = shifts,
                        isOnline = cp.optBoolean("is_online", true)
                    )
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

                syncServiceVerticals()
                return@withContext Result.success(userObj)
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun authenticateUserRpc(
        identifier: String,
        password: String,
        expectedAppRole: String
    ): Result<AuthenticatedUser> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("p_identifier", identifier.trim())
                put("p_password", password)
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/authenticate_user_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            var uid = ""
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
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
                uid = u.getString("id")
            }
            return@withContext fetchAppBootStateRpc(uid, expectedAppRole)
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
            val payload = JSONObject().apply {
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
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/register_customer_portal_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            var newUid = ""
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") != "SUCCESS" || !json.has("user")) {
                    return@withContext Result.failure(IllegalStateException(json.optString("message", "Customer sign-up failed.")))
                }
                newUid = json.getJSONObject("user").getString("id")
            }
            return@withContext fetchAppBootStateRpc(newUid, "customer")
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
            val payload = JSONObject().apply {
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
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/register_contractor_portal_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            var newUid = ""
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") != "SUCCESS" || !json.has("user")) {
                    return@withContext Result.failure(IllegalStateException(json.optString("message", "Contractor sign-up failed.")))
                }
                newUid = json.getJSONObject("user").getString("id")
            }
            return@withContext fetchAppBootStateRpc(newUid, "contractor")
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun purchaseSubscriptionPassRpc(tierCode: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated"))
        try {
            val payload = JSONObject().apply {
                put("p_contractor_id", activeUser.id)
                put("p_tier_code", tierCode)
                put("p_payment_ref", "APK-PASS-${System.currentTimeMillis()}")
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/purchase_subscription_pass_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") != "SUCCESS") {
                    return@withContext Result.failure(IllegalStateException(json.optString("message", "Pass activation failed.")))
                }
            }
            fetchAppBootStateRpc(activeUser.id, "contractor")
            return@withContext Result.success(true)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    fun signOut() {
        currentSessionUser = null
        currentContractorProfile = null
        currentSubscriptionPass = null
        bookings.clear()
        ledgerHistory.clear()
        chatTranscripts.clear()
    }

    suspend fun syncServiceVerticals(): List<ServiceVertical> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/service_verticals?select=*&order=vertical_key.asc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    if (arr.length() > 0) {
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
                }
            }
        } catch (_: Exception) {}
        syncBookingsAndWallet()
        return@withContext verticals
    }

    suspend fun syncBookingsAndWallet() = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext
        try {
            val bReq = Request.Builder()
                .url("$supabaseUrl/rest/v1/bookings?select=*&order=created_at.desc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            http.newCall(bReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    bookings.clear()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        bookings.add(
                            BookingRecord(
                                id = o.getString("id"),
                                bookingRef = o.getString("booking_ref"),
                                verticalKey = o.getString("vertical_key"),
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
                                status = o.getString("status")
                            )
                        )
                    }
                }
            }

            val lReq = Request.Builder()
                .url("$supabaseUrl/rest/v1/credit_ledger?user_id=eq.${activeUser.id}&select=*&order=created_at.desc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            http.newCall(lReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
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
        } catch (_: Exception) {}
    }

    suspend fun createBookingRpc(
        verticalKey: String,
        municipality: String,
        barangayZone: String,
        skillCategory: String,
        serviceTitle: String,
        pickupAddress: String,
        dropoffAddress: String,
        quotedFee: Double,
        tipAmount: Double
    ): Result<BookingRecord> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val payload = JSONObject().apply {
                put("p_client_id", activeUser.id)
                put("p_vertical_key", verticalKey)
                put("p_municipality", municipality)
                put("p_barangay_zone", barangayZone)
                put("p_skill_category", skillCategory)
                put("p_service_title", serviceTitle)
                put("p_service_notes", "Dispatched by ${activeUser.nickname} (${activeUser.phone})")
                put("p_pickup_address", pickupAddress)
                put("p_dropoff_address", dropoffAddress)
                put("p_quoted_fee", quotedFee)
                put("p_tip_amount", tipAmount)
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/create_booking_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") == "BLOCKED_VERTICAL_DISABLED") {
                    return@withContext Result.failure(IllegalStateException(json.optString("message")))
                }
            }
            syncBookingsAndWallet()
            val newest = bookings.firstOrNull()
            if (newest != null) return@withContext Result.success(newest)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("Unable to dispatch booking."))
    }

    suspend fun claimBookingRpc(bookingId: String): Result<BookingRecord> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val payload = JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", activeUser.id)
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/claim_booking_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") != "SUCCESS") {
                    return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to claim job.")))
                }
            }
            fetchAppBootStateRpc(activeUser.id, "contractor")
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("Claim failed."))
    }

    suspend fun advanceBookingStateRpc(bookingId: String, nextStatus: String): Result<BookingRecord> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val payload = JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_actor_id", activeUser.id)
                put("p_next_status", nextStatus)
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/transition_booking_status_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                if (json.optString("status") != "SUCCESS") {
                    return@withContext Result.failure(IllegalStateException(json.optString("message", "Transition failed.")))
                }
            }
            fetchAppBootStateRpc(activeUser.id, "contractor")
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("State transition error."))
    }

    suspend fun topUpCreditsQrPhRpc(amount: Double): Result<Double> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val payload = JSONObject().apply {
                put("p_user_id", activeUser.id)
                put("p_amount", amount)
                put("p_qrph_ref", "QRPH-${System.currentTimeMillis()}")
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/topup_prepaid_credits_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            var bonusGranted = 0.0
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                bonusGranted = json.optDouble("bonus_granted", 0.0)
            }
            fetchAppBootStateRpc(activeUser.id, "contractor")
            return@withContext Result.success(bonusGranted)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun sendModeratedChatMessageRpc(
        bookingId: String,
        messageBody: String
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val payload = JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_sender_id", activeUser.id)
                put("p_message_body", messageBody)
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/send_booking_message_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val status = json.optString("status")
                if (status == "BLOCKED_MODERATION" || status == "LOCKED_STATE" || status == "ERROR") {
                    return@withContext Result.failure(SecurityException(json.optString("message", "Message blocked by moderation trigger.")))
                }
            }
            val msg = ChatMessage(activeUser.nickname, messageBody.trim(), "Live")
            val list = chatTranscripts.getOrPut(bookingId) { mutableListOf() }
            list.add(msg)
            return@withContext Result.success(msg)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
