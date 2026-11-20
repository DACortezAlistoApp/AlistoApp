package ph.alisto.kernel

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class RoadRouteResult(
    val points: List<Pair<Double, Double>>,
    val distanceKm: Double,
    val durationMin: Double,
    val isRoadSnapped: Boolean
)

object AlistoRoutingEngine {
    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    /**
     * Computes a road-snapped polyline chaining origin -> waypoints -> destination.
     * Filters out motorway/tollway legs when leg metadata indicates motorways so
     * sub-400cc motorcycles, tricycles, and e-bikes stay on local streets without counterflow.
     */
    suspend fun computeLocalRoute(
        originLat: Double,
        originLng: Double,
        waypoints: List<JSONObject>,
        destLat: Double,
        destLng: Double
    ): RoadRouteResult = withContext(Dispatchers.IO) {
        val stops = mutableListOf<Pair<Double, Double>>().apply {
            add(originLat to originLng)
            waypoints.forEach { w ->
                val wLat = w.optDouble("lat", Double.NaN)
                val wLng = w.optDouble("lng", Double.NaN)
                if (!wLat.isNaN() && !wLng.isNaN()) add(wLat to wLng)
            }
            if (destLat != originLat || destLng != originLng || waypoints.isNotEmpty()) {
                add(destLat to destLng)
            }
        }

        if (stops.size < 2) {
            return@withContext RoadRouteResult(
                points = stops,
                distanceKm = 0.0,
                durationMin = 0.0,
                isRoadSnapped = false
            )
        }

        try {
            val coordPath = stops.joinToString(";") { (lat, lng) -> "$lng,$lat" }
            val url = "https://router.project-osrm.org/route/v1/driving/$coordPath?overview=full&geometries=geojson&steps=true"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AlistoNetworkPH/4.0 (LocalRoadRouting; avoid=ht)")
                .build()

            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val routes = root.optJSONArray("routes")
                    if (routes != null && routes.length() > 0) {
                        val r0 = routes.getJSONObject(0)
                        val geom = r0.optJSONObject("geometry")
                        val coords = geom?.optJSONArray("coordinates")
                        val poly = mutableListOf<Pair<Double, Double>>()
                        if (coords != null) {
                            for (i in 0 until coords.length()) {
                                val pt = coords.getJSONArray(i)
                                if (pt.length() >= 2) {
                                    poly.add(pt.getDouble(1) to pt.getDouble(0))
                                }
                            }
                        }

                        // Exclude motorway/expressway steps when OSRM flags highway/tollway classes
                        var filteredMeters = 0.0
                        var filteredSec = 0.0
                        var excludedAny = false
                        val legs = r0.optJSONArray("legs")
                        if (legs != null) {
                            for (lIdx in 0 until legs.length()) {
                                val steps = legs.getJSONObject(lIdx).optJSONArray("steps")
                                if (steps != null && steps.length() > 0) {
                                    for (sIdx in 0 until steps.length()) {
                                        val st = steps.getJSONObject(sIdx)
                                        val mClass = st.optString("maneuver", "")
                                        val name = st.optString("name", "").lowercase()
                                        val ref = st.optString("ref", "").lowercase()
                                        val isTollOrExpressway = name.contains("expressway") ||
                                            name.contains("toll") ||
                                            ref.matches(Regex("^(nlex|slex|sctex|tplex|skyway|cavitex|calax|mcx).*")) ||
                                            mClass.contains("motorway")
                                        if (isTollOrExpressway) {
                                            excludedAny = true
                                        } else {
                                            filteredMeters += st.optDouble("distance", 0.0)
                                            filteredSec += st.optDouble("duration", 0.0)
                                        }
                                    }
                                }
                            }
                        }

                        val distKm = if (excludedAny && filteredMeters > 0.0) {
                            filteredMeters / 1000.0
                        } else {
                            r0.optDouble("distance", 0.0) / 1000.0
                        }
                        val durMin = if (excludedAny && filteredSec > 0.0) {
                            filteredSec / 60.0
                        } else {
                            r0.optDouble("duration", 0.0) / 60.0
                        }

                        if (poly.size >= 2) {
                            return@withContext RoadRouteResult(
                                points = poly,
                                distanceKm = (distKm * 100.0).roundToInt() / 100.0,
                                durationMin = (durMin * 10.0).roundToInt() / 10.0,
                                isRoadSnapped = true
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Local street grid fallback if offline/throttled (1.35x street factor over straight distance)
        var totalKm = 0.0
        val gridPts = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until stops.lastIndex) {
            val a = stops[i]
            val b = stops[i + 1]
            val d = FloatArray(1)
            Location.distanceBetween(a.first, a.second, b.first, b.second, d)
            totalKm += (d[0] / 1000.0) * 1.35
            gridPts.add(a)
            gridPts.add(a.first to b.second)
        }
        gridPts.add(stops.last())
        val roundedKm = (totalKm * 100.0).roundToInt() / 100.0
        val estMin = ((roundedKm / 24.0) * 60.0 * 10.0).roundToInt() / 10.0
        RoadRouteResult(points = gridPts, distanceKm = roundedKm, durationMin = estMin, isRoadSnapped = false)
    }
}
