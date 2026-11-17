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

    const val DEFAULT_CLIENT_UUID = "44444444-4444-4444-4444-444444444444"
    const val DEFAULT_CONTRACTOR_UUID = "33333333-3333-3333-3333-333333333333"

    val verticals = mutableListOf<ServiceVertical>()
    val bookings = mutableListOf<BookingRecord>()
    val ledgerHistory = mutableListOf<LedgerEntry>()
    val chatTranscripts = mutableMapOf<String, MutableList<ChatMessage>>()

    var contractorAvailableCredits: Double = 600.00
    var contractorHeldCredits: Double = 0.00
    var contractorKycVerified: Boolean = true
    var contractorLguEndorsed: Boolean = true

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
        try {
            // Sync active bookings
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
                                contractorName = if (o.isNull("contractor_id")) null else "Engr. Marco Pineda (LGU Verified)"
                            )
                        )
                    }
                }
            }

            // Sync Contractor Wallet
            val uReq = Request.Builder()
                .url("$supabaseUrl/rest/v1/users?id=eq.$DEFAULT_CONTRACTOR_UUID&select=*")
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .get()
                .build()

            http.newCall(uReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string() ?: "[]")
                    if (arr.length() > 0) {
                        val u = arr.getJSONObject(0)
                        contractorAvailableCredits = u.optDouble("credit_balance", 600.0)
                        contractorHeldCredits = u.optDouble("held_credits", 0.0)
                        contractorKycVerified = u.optBoolean("kyc_verified", true)
                        contractorLguEndorsed = u.optBoolean("lgu_peso_endorsed", true)
                    }
                }
            }

            // Sync Append-Only Credit Ledger
            val lReq = Request.Builder()
                .url("$supabaseUrl/rest/v1/credit_ledger?user_id=eq.$DEFAULT_CONTRACTOR_UUID&select=*&order=created_at.desc")
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
        clientId: String,
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
        try {
            val payload = JSONObject().apply {
                put("p_client_id", clientId)
                put("p_vertical_key", verticalKey)
                put("p_municipality", municipality)
                put("p_barangay_zone", barangayZone)
                put("p_skill_category", skillCategory)
                put("p_service_title", serviceTitle)
                put("p_service_notes", "Dispatched via Alisto Native Android Kotlin App")
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

    suspend fun claimBookingRpc(bookingId: String, contractorId: String): Result<BookingRecord> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_contractor_id", contractorId)
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
            syncBookingsAndWallet()
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("Claim failed."))
    }

    suspend fun advanceBookingStateRpc(bookingId: String, nextStatus: String): Result<BookingRecord> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_actor_id", DEFAULT_CONTRACTOR_UUID)
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
            syncBookingsAndWallet()
            val updated = bookings.find { it.id == bookingId }
            if (updated != null) return@withContext Result.success(updated)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        return@withContext Result.failure(IllegalStateException("State transition error."))
    }

    suspend fun topUpCreditsQrPhRpc(amount: Double): Result<Double> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("p_user_id", DEFAULT_CONTRACTOR_UUID)
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
        senderLabel: String,
        messageBody: String
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        val senderUuid = if (senderLabel.contains("Contractor", ignoreCase = true)) {
            DEFAULT_CONTRACTOR_UUID
        } else {
            DEFAULT_CLIENT_UUID
        }
        try {
            val payload = JSONObject().apply {
                put("p_booking_id", bookingId)
                put("p_sender_id", senderUuid)
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
            val msg = ChatMessage(senderLabel, messageBody.trim(), "Live")
            val list = chatTranscripts.getOrPut(bookingId) { mutableListOf() }
            list.add(msg)
            return@withContext Result.success(msg)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
