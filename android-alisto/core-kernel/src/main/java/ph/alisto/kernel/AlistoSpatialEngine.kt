package ph.alisto.kernel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

object AlistoSpatialEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    var isHeartbeatStreaming: Boolean = false
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

    fun reverseGeocodePin(context: Context, lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lng, 1)
            if (!list.isNullOrEmpty()) {
                val addr = list[0]
                listOfNotNull(addr.thoroughfare, addr.subLocality, addr.locality, addr.subAdminArea)
                    .joinToString(", ")
                    .ifBlank { "Pin (${"%.4f".format(lat)}, ${"%.4f".format(lng)})" }
            } else {
                "Pin (${"%.4f".format(lat)}, ${"%.4f".format(lng)})"
            }
        } catch (_: Exception) {
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
                            speedKph = (loc.speed * 3.6).coerceAtLeast(18.0)
                        }
                    } catch (_: Exception) {}

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
    }
}
