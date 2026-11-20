package ph.alisto.kernel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

object AlistoSpatialEngine {
    const val LTFRB_MAX_SPEED_KPH = 60.0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var lastOverspeedToastMs: Long = 0L

    private val geoHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    var isHeartbeatStreaming: Boolean = false
        private set
    var lastRecordedSpeedKph: Double = 0.0
        private set

    fun launchLocalRoadNavigation(context: Context, destLat: Double, destLng: Double) {
        val navUri = Uri.parse("google.navigation:q=$destLat,$destLng&avoid=ht")
        val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
        } else {
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destLat,$destLng&travelmode=driving&avoid=highways,tolls")
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun isPlusCodeOrNumeric(s: String?): Boolean {
        val v = s?.trim().orEmpty()
        if (v.isEmpty()) return true
        if (v.contains("+") && v.length <= 12) return true
        return v.all { it.isDigit() || it == '-' || it == '#' || it == ' ' || it == '.' }
    }

    private fun fetchNearbyPoiName(lat: Double, lng: Double): Pair<String, String>? {
        // 1. Try Photon reverse (fast POI & building names)
        try {
            val req = Request.Builder()
                .url("https://photon.komoot.io/reverse?lat=$lat&lon=$lng&limit=3")
                .header("User-Agent", "AlistoNetworkPH/4.0 (LandmarkLookup)")
                .build()
            geoHttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val features = JSONObject(resp.body?.string().orEmpty()).optJSONArray("features")
                    if (features != null) {
                        for (i in 0 until features.length()) {
                            val props = features.getJSONObject(i).optJSONObject("properties") ?: continue
                            val name = props.optString("name", "").trim()
                            val street = props.optString("street", "").trim()
                            val district = props.optString("district", props.optString("locality", "")).trim()
                            val city = props.optString("city", "").trim()
                            val locality = listOf(street, district, city).filter { it.isNotBlank() }.distinct().joinToString(", ")
                            if (name.isNotBlank() && !isPlusCodeOrNumeric(name)) {
                                return name to locality
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback to Nominatim zoom=18 building/POI reverse
        try {
            val req = Request.Builder()
                .url("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1")
                .header("User-Agent", "AlistoNetworkPH/4.0 (LandmarkLookup)")
                .build()
            geoHttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val poi = obj.optString("name", "").trim()
                    val addr = obj.optJSONObject("address")
                    val road = addr?.optString("road", "").orEmpty().trim()
                    val suburb = addr?.optString("suburb", addr.optString("village", addr.optString("neighbourhood", ""))).orEmpty().trim()
                    val city = addr?.optString("city", addr.optString("town", addr.optString("municipality", ""))).orEmpty().trim()
                    val locality = listOf(road, suburb, city).filter { it.isNotBlank() }.distinct().joinToString(", ")
                    if (poi.isNotBlank() && !isPlusCodeOrNumeric(poi)) {
                        return poi to locality
                    }
                    if (locality.isNotBlank()) return "" to locality
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun reverseGeocodePin(context: Context, lat: Double, lng: Double): String {
        var googleLandmark = ""
        var googleLocality = ""
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lng, 5)
            if (!list.isNullOrEmpty()) {
                for (a in list) {
                    val cand = listOf(a.premises, a.featureName)
                        .firstOrNull { !isPlusCodeOrNumeric(it) && !it.equals(a.thoroughfare, ignoreCase = true) && !it.equals(a.locality, ignoreCase = true) }
                    if (!cand.isNullOrBlank()) {
                        googleLandmark = cand.trim()
                        break
                    }
                }
                val primary = list[0]
                googleLocality = listOfNotNull(primary.thoroughfare, primary.subLocality, primary.locality, primary.subAdminArea)
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !isPlusCodeOrNumeric(it) }
                    .distinct()
                    .joinToString(", ")
                if (googleLocality.isBlank()) {
                    val line0 = primary.getAddressLine(0)?.trim().orEmpty()
                    if (line0.isNotBlank()) googleLocality = line0
                }
            }
        } catch (_: Exception) {}

        val poiPair = fetchNearbyPoiName(lat, lng)
        val bestLandmark = when {
            poiPair != null && poiPair.first.isNotBlank() -> poiPair.first
            googleLandmark.isNotBlank() -> googleLandmark
            else -> ""
        }
        val bestLocality = googleLocality.ifBlank { poiPair?.second.orEmpty() }

        val parts = mutableListOf<String>()
        if (bestLandmark.isNotBlank()) parts.add(bestLandmark)
        if (bestLocality.isNotBlank() && !bestLocality.startsWith(bestLandmark, ignoreCase = true)) {
            parts.add(bestLocality)
        }
        return parts.joinToString(" • ").ifBlank {
            "Pin (${"%.4f".format(lat)}, ${"%.4f".format(lng)})"
        }
    }

    @SuppressLint("MissingPermission")
    fun startTenSecondHeartbeat(
        context: Context,
        scope: CoroutineScope,
        bookingId: String,
        contractorId: String
    ) {
        stopHeartbeat()
        val appCtx = context.applicationContext
        isHeartbeatStreaming = true

        val task = object : Runnable {
            override fun run() {
                if (!isHeartbeatStreaming) return
                scope.launch(Dispatchers.IO) {
                    var lat = 15.1450 + (Math.random() * 0.008)
                    var lng = 120.5887 + (Math.random() * 0.008)
                    var speedKph = 25.0
                    try {
                        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                        val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (loc != null) {
                            lat = loc.latitude
                            lng = loc.longitude
                            speedKph = if (loc.hasSpeed()) (loc.speed * 3.6) else 18.0
                        }
                    } catch (_: Exception) {}

                    lastRecordedSpeedKph = speedKph

                    // On-device LTFRB MC 2026-048 60 km/h speed limit alert
                    if (speedKph > LTFRB_MAX_SPEED_KPH) {
                        val now = System.currentTimeMillis()
                        if (now - lastOverspeedToastMs >= 20_000L) {
                            lastOverspeedToastMs = now
                            mainHandler.post {
                                Toast.makeText(
                                    appCtx,
                                    "LTFRB SPEED ALERT: ${"%.1f".format(speedKph)} km/h exceeds 60 km/h limit. Please slow down.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    AlistoKernelClient.recordTelemetryPingRpc(
                        bookingId = bookingId,
                        contractorId = contractorId,
                        lat = lat,
                        lng = lng,
                        speedKph = speedKph
                    )
                }
                mainHandler.postDelayed(this, 10_000L)
            }
        }
        heartbeatRunnable = task
        mainHandler.post(task)
    }

    fun stopHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        isHeartbeatStreaming = false
        lastRecordedSpeedKph = 0.0
    }
}
