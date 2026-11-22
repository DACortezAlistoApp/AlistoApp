package ph.alisto.kernel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.lang.ref.WeakReference
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
import kotlin.math.max

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

    private var lastGpsFixMs: Long = 0L
    private var lastGpsAccuracyM: Float = 999f
    private var lastAcceptedMs: Long = 0L
    private var smoothedLat: Double? = null
    private var smoothedLng: Double? = null

    var lastLat: Double? = null
        private set
    var lastLng: Double? = null
        private set
    var lastAccuracyM: Float = 25f
        private set
    var lastProviderName: String = "unknown"
        private set

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private var activeGpsDialogRef: WeakReference<AlertDialog>? = null
    private var gpsPermRequestedOnce: Boolean = false

    fun hasPreciseGpsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val hwEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val sysEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { lm.isLocationEnabled } catch (_: Exception) { hwEnabled }
        } else {
            hwEnabled
        }
        return hwEnabled && sysEnabled
    }

    fun requestPermission(activity: Activity) {
        gpsPermRequestedOnce = true
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            REQ_LOCATION_PERMISSION
        )
    }

    /**
     * Enforces explicit Precise GPS permission and active GPS hardware.
     * Returns true only when both ACCESS_FINE_LOCATION and device GPS are enabled.
     */
    fun ensureExplicitGpsGate(activity: Activity, appLabel: String = "Alisto"): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val hasFine = hasPreciseGpsPermission(activity)
        val gpsOn = isGpsEnabled(activity)

        if (hasFine && gpsOn) {
            try { activeGpsDialogRef?.get()?.dismiss() } catch (_: Exception) {}
            activeGpsDialogRef = null
            return true
        }

        if (activeGpsDialogRef?.get()?.isShowing == true) return false

        if (!hasFine) {
            val permanentlyDenied = gpsPermRequestedOnce &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            val dlg = AlertDialog.Builder(activity)
                .setTitle("Precise GPS Required")
                .setMessage(
                    "$appLabel explicitly requires Precise GPS location access for accurate pickup pins, live route tracking, and verified arrival/drop-off distance."
                )
                .setCancelable(false)
                .setPositiveButton(if (permanentlyDenied) "Open App Settings" else "Allow Precise GPS") { _, _ ->
                    if (permanentlyDenied) {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            })
                        } catch (_: Exception) {
                            requestPermission(activity)
                        }
                    } else {
                        requestPermission(activity)
                    }
                }
                .create()
            activeGpsDialogRef = WeakReference(dlg)
            dlg.show()
            return false
        }

        // Permission granted, but device GPS toggle is OFF
        val dlg = AlertDialog.Builder(activity)
            .setTitle("Turn On Device GPS")
            .setMessage(
                "Your phone's GPS is currently turned off. Please enable Location / GPS so $appLabel can lock your live coordinates."
            )
            .setCancelable(false)
            .setPositiveButton("Enable GPS") { _, _ ->
                try {
                    activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            }
            .create()
        activeGpsDialogRef = WeakReference(dlg)
        dlg.show()
        return false
    }

    @SuppressLint("MissingPermission")
    fun getBestLastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            val ageMs = now - loc.time
            if (ageMs > 180_000L && best != null) continue
            if (best == null || (loc.accuracy < best.accuracy && ageMs < 90_000L)) {
                best = loc
            }
        }
        best?.let {
            if (smoothedLat == null) {
                smoothedLat = it.latitude
                smoothedLng = it.longitude
            }
            lastLat = smoothedLat ?: it.latitude
            lastLng = smoothedLng ?: it.longitude
            lastAccuracyM = it.accuracy.coerceAtLeast(8f)
            lastProviderName = it.provider ?: "cached"
        }
        return best
    }

    @SuppressLint("MissingPermission")
    fun startLiveUpdates(
        context: Context,
        minTimeMs: Long = 2_500L,
        minDistanceM: Float = 2f,
        onLocation: (lat: Double, lng: Double, accuracyM: Float) -> Unit
    ) {
        if (!hasPermission(context)) return
        stopLiveUpdates()

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        activeManager = lm

        getBestLastKnown(context)?.let {
            onLocation(lastLat ?: it.latitude, lastLng ?: it.longitude, lastAccuracyM)
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val filtered = filterAndSmooth(location) ?: return
                onLocation(filtered.first, filtered.second, filtered.third)
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

    /**
     * 5-stage anti-drift filter:
     * 1) GPS-over-Network priority, 2) Accuracy ceiling, 3) Spike guard,
     * 4) Stationary dead-zone, 5) Accuracy-weighted smoothing.
     */
    private fun filterAndSmooth(loc: Location): Triple<Double, Double, Float>? {
        val now = System.currentTimeMillis()
        val acc = loc.accuracy
        if (acc <= 0f) return null

        val isGps = loc.provider == LocationManager.GPS_PROVIDER
        if (isGps) {
            lastGpsFixMs = now
            lastGpsAccuracyM = acc
        } else {
            // Suppress cell-tower / Wi-Fi jumps whenever fresh satellite GPS is locked
            if ((now - lastGpsFixMs) < 15_000L && lastGpsAccuracyM <= 30f) return null
        }

        // Reject noisy indoor fixes once we already have a reasonable lock
        val curLat = smoothedLat
        val curLng = smoothedLng
        if (curLat != null && curLng != null && lastAccuracyM <= 35f && acc > 55f) {
            return null
        }

        if (curLat == null || curLng == null || (now - lastAcceptedMs) > 120_000L) {
            if (acc > 150f) return null
            smoothedLat = loc.latitude
            smoothedLng = loc.longitude
            lastLat = loc.latitude
            lastLng = loc.longitude
            lastAccuracyM = acc.coerceAtLeast(6f)
            lastProviderName = loc.provider ?: "gps"
            lastAcceptedMs = now
            return Triple(loc.latitude, loc.longitude, lastAccuracyM)
        }

        val distArr = FloatArray(1)
        Location.distanceBetween(curLat, curLng, loc.latitude, loc.longitude, distArr)
        val distM = distArr[0].toDouble()
        val dtSec = ((now - lastAcceptedMs) / 1000.0).coerceAtLeast(1.0)

        // Reject impossible teleport spikes from weak non-GPS sources
        if (!isGps && distM > max(85.0, dtSec * 45.0) && acc > 25f) return null

        // Stationary dead-zone prevents the blue dot from jittering while standing still
        val deadZoneM = (acc * 0.45f).coerceIn(7f, 18f)
        if (distM < deadZoneM) {
            if (acc < lastAccuracyM) {
                lastAccuracyM = acc.coerceAtLeast(6f)
            }
            return null
        }

        // Accuracy-weighted smoothing factor
        val alpha = when {
            distM > 35.0 && isGps -> 0.85
            acc <= 12f -> 0.65
            acc <= 25f -> 0.45
            else -> 0.28
        }

        val nextLat = curLat + alpha * (loc.latitude - curLat)
        val nextLng = curLng + alpha * (loc.longitude - curLng)
        smoothedLat = nextLat
        smoothedLng = nextLng
        lastLat = nextLat
        lastLng = nextLng
        lastAccuracyM = acc.coerceAtLeast(6f)
        lastProviderName = loc.provider ?: "gps"
        lastAcceptedMs = now
        return Triple(nextLat, nextLng, lastAccuracyM)
    }

    fun stopLiveUpdates() {
        try {
            activeListener?.let { activeManager?.removeUpdates(it) }
        } catch (_: Exception) {
        }
        activeListener = null
        activeManager = null
    }

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
