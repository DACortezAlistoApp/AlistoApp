package ph.alisto.kernel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class LandmarkResult(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double
)

object AlistoLocationTracker {
    const val REQ_LOCATION_PERMISSION = 4102

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var activeManager: LocationManager? = null
    private var activeListener: LocationListener? = null

    var lastLat: Double? = null
        private set
    var lastLng: Double? = null
        private set
    var lastAccuracyM: Float = 25f
        private set

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun requestPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_LOCATION_PERMISSION
        )
    }

    @SuppressLint("MissingPermission")
    fun getBestLastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null || loc.accuracy < best.accuracy || (loc.time - best.time) > 120_000L) {
                best = loc
            }
        }
        best?.let {
            lastLat = it.latitude
            lastLng = it.longitude
            lastAccuracyM = it.accuracy.coerceAtLeast(8f)
        }
        return best
    }

    @SuppressLint("MissingPermission")
    fun startLiveUpdates(
        context: Context,
        minTimeMs: Long = 3_000L,
        minDistanceM: Float = 3f,
        onLocation: (lat: Double, lng: Double, accuracyM: Float) -> Unit
    ) {
        if (!hasPermission(context)) return
        stopLiveUpdates()

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        activeManager = lm

        // Push best cached fix right away so the blue dot appears immediately
        getBestLastKnown(context)?.let {
            onLocation(it.latitude, it.longitude, it.accuracy.coerceAtLeast(8f))
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLat = location.latitude
                lastLng = location.longitude
                lastAccuracyM = location.accuracy.coerceAtLeast(8f)
                onLocation(location.latitude, location.longitude, lastAccuracyM)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        activeListener = listener

        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stopLiveUpdates() {
        try {
            activeListener?.let { activeManager?.removeUpdates(it) }
        } catch (_: Exception) {
        }
        activeListener = null
        activeManager = null
    }

    /**
     * Searches Philippine landmarks and street addresses using OpenStreetMap Nominatim
     * (biased around biasLat/biasLng) with native Android Geocoder as a fallback.
     */
    suspend fun searchLandmarks(
        context: Context,
        rawQuery: String,
        biasLat: Double = 15.1450,
        biasLng: Double = 120.5887
    ): List<LandmarkResult> = withContext(Dispatchers.IO) {
        val q = rawQuery.trim()
        if (q.length < 2) return@withContext emptyList()

        val results = mutableListOf<LandmarkResult>()
        try {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val left = biasLng - 0.35
            val right = biasLng + 0.35
            val top = biasLat + 0.35
            val bottom = biasLat - 0.35
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=ph&addressdetails=1&limit=6&viewbox=$left,$top,$right,$bottom&q=$encoded"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AlistoNetworkPH/4.0 (Android Mobile; https://dacortezalistoapp.github.io/AlistoApp/)")
                .header("Accept-Language", "en-PH,en;q=0.9")
                .build()

            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                        val lng = obj.optString("lon").toDoubleOrNull() ?: continue
                        val display = obj.optString("display_name", q)
                        val name = obj.optString("name", "").ifBlank { display.substringBefore(",").trim() }
                        results.add(LandmarkResult(title = name, subtitle = display, lat = lat, lng = lng))
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (results.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(context, Locale("en", "PH"))
                    .getFromLocationName("$q, Philippines", 5)
                    .orEmpty()
                addresses.forEach { a ->
                    val line = a.getAddressLine(0) ?: q
                    val title = a.featureName?.takeIf { it.isNotBlank() && it != line } ?: line.substringBefore(",")
                    results.add(LandmarkResult(title = title, subtitle = line, lat = a.latitude, lng = a.longitude))
                }
            } catch (_: Exception) {
            }
        }
        results
    }
}
