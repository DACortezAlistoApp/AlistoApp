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
    val phone: String,
    val email: String,
    val role: String,
    val municipality: String,
    val barangayZone: String,
    val kycVerified: Boolean,
    val lguPesoEndorsed: Boolean,
    val creditBalance: Double,
    val heldCredits: Double,
    val customCommissionRate: Double?
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
    var status: String,
    var contractorName: String? = null
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

    val verticals = mutableListOf<ServiceVertical>()
    val bookings = mutableListOf<BookingRecord>()
    val ledgerHistory = mutableListOf<LedgerEntry>()
    val chatTranscripts = mutableMapOf<String, MutableList<ChatMessage>>()

    var contractorAvailableCredits: Double = 0.00
    var contractorHeldCredits: Double = 0.00
    var contractorKycVerified: Boolean = false
    var contractorLguEndorsed: Boolean = false
    var effectiveCommissionRate: Double = 0.12

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

            http.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                val json = JSONObject(bodyStr)
                val status = json.optString("status")
                if (status != "SUCCESS" || !json.has("user")) {
                    return@withContext Result.failure(
                        SecurityException(json.optString("message", "Invalid credentials."))
                    )
                }
                val u = json.getJSONObject("user")
                val role = u.getString("role")

                // Enforce strict role isolation per APK (super_admin permitted on both for architect testing)
                if (role != expectedAppRole && role != "super_admin") {
                    return@withContext Result.failure(
                        SecurityException("ROLE ISOLATION BLOCK: Your account role is [$role]. This application requires [$expectedAppRole] privileges.")
                    )
                }

                val customComm = if (u.isNull("custom_commission_rate")) null else u.optDouble("custom_commission_rate")
                val authUser = AuthenticatedUser(
                    id = u.getString("id"),
                    username = u.optString("username", ""),
                    fullName = u.getString("full_name"),
                    phone = u.getString("phone"),
                    email = u.optString("email", ""),
                    role = role,
                    municipality = u.optString("municipality", "Angeles City"),
                    barangayZone = u.optString("barangay_zone", "Sto. Rosario"),
                    kycVerified = u.optBoolean("kyc_verified", true),
                    lguPesoEndorsed = u.optBoolean("lgu_peso_endorsed", false),
                    creditBalance = u.optDouble("credit_balance", 0.0),
                    heldCredits = u.optDouble("held_credits", 0.0),
                    customCommissionRate = customComm
                )
                currentSessionUser = authUser
                contractorAvailableCredits = authUser.creditBalance
                contractorHeldCredits = authUser.heldCredits
                contractorKycVerified = authUser.kycVerified
                contractorLguEndorsed = authUser.lguPesoEndorsed
                if (customComm != null) {
                    effectiveCommissionRate = customComm
                }

                syncServiceVerticals()
                return@withContext Result.success(authUser)
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    fun signOut() {
        currentSessionUser = null
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
                                status = o.getString("status"),
                                contractorName = if (o.isNull("contractor_id")) null else "Assigned LGU Contractor"
                            )
                        )
                    }
                }
            }

            // Sync logged-in user's fresh wallet & KYC status
            val uReq = Request.Builder()
                .url("$supabaseUrl/rest/v1/users?id=eq.${activeUser.id}&select=*")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            http.newCall(uReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        val u = arr.getJSONObject(0)
                        contractorAvailableCredits = u.optDouble("credit_balance", 0.0)
                        contractorHeldCredits = u.optDouble("held_credits", 0.0)
                        contractorKycVerified = u.optBoolean("kyc_verified", true)
                        contractorLguEndorsed = u.optBoolean("lgu_peso_endorsed", false)
                        if (!u.isNull("custom_commission_rate")) {
                            effectiveCommissionRate = u.optDouble("custom_commission_rate", 0.12)
                        }
                    }
                }
            }

            // Sync Append-Only Credit Ledger for logged-in user
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
        val activeUser = currentSessionUser
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
        try {
            val payload = JSONObject().apply {
                put("p_client_id", activeUser.id)
                put("p_vertical_key", verticalKey)
                put("p_municipality", municipality)
                put("p_barangay_zone", barangayZone)
                put("p_skill_category", skillCategory)
                put("p_service_title", serviceTitle)
                put("p_service_notes", "Dispatched by ${activeUser.fullName} (@${activeUser.username})")
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
                val bodyStr = resp.body?.string() ?: "{}"
                val json = JSONObject(bodyStr)
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
        val activeUser = currentSessionUser
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
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
                val status = json.optString("status")
                if (status != "SUCCESS") {
                    return@withContext Result.failure(IllegalStateException(json.optString("message", "Unable to claim job.")))
                }
            }
            recordTelemetryPingRpc(bookingId, activeUser.id)
            syncBookingsAndWallet()
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("Claim failed."))
    }

    suspend fun advanceBookingStateRpc(bookingId: String, nextStatus: String): Result<BookingRecord> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
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
            recordTelemetryPingRpc(bookingId, activeUser.id)
            syncBookingsAndWallet()
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("State transition error."))
    }

    private fun recordTelemetryPingRpc(bookingId: String, contractorId: String) {
        try {
            val lat = 15.1450 + (Math.random() * 0.012)
            val lng = 120.5887 + (Math.random() * 0.012)
            val payload = JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", contractorId)
                put("p_lat", lat)
                put("p_lng", lng)
                put("p_speed_kph", 28.5)
                put("p_heading", 45.0)
            }
            val req = Request.Builder()
                .url("$supabaseUrl/rest/v1/rpc/record_booking_telemetry_rpc")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    suspend fun topUpCreditsQrPhRpc(amount: Double): Result<Double> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
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

            http.newCall(req).execute().close()
            syncBookingsAndWallet()
            return@withContext Result.success(contractorAvailableCredits)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun sendModeratedChatMessageRpc(
        bookingId: String,
        messageBody: String
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        val activeUser = currentSessionUser
            ?: return@withContext Result.failure(IllegalStateException("Not authenticated."))
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
            val msg = ChatMessage(activeUser.fullName, messageBody.trim(), "Live")
            val list = chatTranscripts.getOrPut(bookingId) { mutableListOf() }
            list.add(msg)
            return@withContext Result.success(msg)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
