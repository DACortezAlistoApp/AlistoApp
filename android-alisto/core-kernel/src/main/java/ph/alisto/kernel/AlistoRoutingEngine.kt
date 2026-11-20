package ph.alisto.kernel

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class RoadRouteResult(
    val points: List<Pair<Double, Double>>,
    val distanceKm: Double,
    val durationMin: Double,
    val isRoadSnapped: Boolean,
    val engineUsed: String = "VALHALLA"
)

object AlistoRoutingEngine {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * Primary: Valhalla motor_scooter (use_highways=0, use_tolls=0) for legal one-way,
     * non-expressway sub-400cc routing across origin -> waypoints -> destination.
     * Secondary: OSRM driving fallback. Tertiary: local street grid fallback.
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
            return@withContext RoadRouteResult(stops, 0.0, 0.0, false, "NONE")
        }

        tryValhallaMotorScooter(stops)?.let { return@withContext it }
        tryOsrmFallback(stops)?.let { return@withContext it }
        buildGridFallback(stops)
    }

    private fun tryValhallaMotorScooter(stops: List<Pair<Double, Double>>): RoadRouteResult? {
        return try {
            val locs = JSONArray()
            stops.forEach { (lat, lng) ->
                locs.put(JSONObject().put("lat", lat).put("lon", lng).put("type", "break"))
            }
            val payload = JSONObject()
                .put("locations", locs)
                .put("costing", "motor_scooter")
                .put("costing_options", JSONObject().put(
                    "motor_scooter",
                    JSONObject()
                        .put("use_highways", 0.0)
                        .put("use_tolls", 0.0)
                        .put("use_ferry", 0.0)
                        .put("top_speed", 60)
                ))
                .put("units", "kilometers")

            val req = Request.Builder()
                .url("https://valhalla1.openstreetmap.de/route")
                .header("User-Agent", "AlistoNetworkPH/4.0 (Sub400ccLocalRoad)")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val trip = JSONObject(resp.body?.string().orEmpty()).optJSONObject("trip") ?: return null
                val legs = trip.optJSONArray("legs") ?: return null
                val poly = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until legs.length()) {
                    val shape = legs.getJSONObject(i).optString("shape", "")
                    if (shape.isNotBlank()) {
                        val decoded = decodePolyline6(shape)
                        if (poly.isNotEmpty() && decoded.isNotEmpty()) poly.addAll(decoded.drop(1))
                        else poly.addAll(decoded)
                    }
                }
                val summary = trip.optJSONObject("summary") ?: return null
                val km = (summary.optDouble("length", 0.0) * 100.0).roundToInt() / 100.0
                val min = ((summary.optDouble("time", 0.0) / 60.0) * 10.0).roundToInt() / 10.0
                if (poly.size >= 2 && km > 0.0) RoadRouteResult(poly, km, min, true, "VALHALLA") else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryOsrmFallback(stops: List<Pair<Double, Double>>): RoadRouteResult? {
        return try {
            val coordPath = stops.joinToString(";") { (lat, lng) -> "$lng,$lat" }
            val url = "https://router.project-osrm.org/route/v1/driving/$coordPath?overview=full&geometries=geojson"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "AlistoNetworkPH/4.0 (LocalRoadFallback)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val routes = JSONObject(resp.body?.string().orEmpty()).optJSONArray("routes") ?: return null
                if (routes.length() == 0) return null
                val r0 = routes.getJSONObject(0)
                val coords = r0.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
                val poly = mutableListOf<Pair<Double, Double>>()
                for (i in 0 until coords.length()) {
                    val pt = coords.getJSONArray(i)
                    if (pt.length() >= 2) poly.add(pt.getDouble(1) to pt.getDouble(0))
                }
                val km = ((r0.optDouble("distance", 0.0) / 1000.0) * 100.0).roundToInt() / 100.0
                val min = ((r0.optDouble("duration", 0.0) / 60.0) * 10.0).roundToInt() / 10.0
                if (poly.size >= 2) RoadRouteResult(poly, km, min, true, "OSRM") else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildGridFallback(stops: List<Pair<Double, Double>>): RoadRouteResult {
        var totalKm = 0.0
        val pts = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until stops.lastIndex) {
            val a = stops[i]; val b = stops[i + 1]
            val d = FloatArray(1)
            Location.distanceBetween(a.first, a.second, b.first, b.second, d)
            totalKm += (d[0] / 1000.0) * 1.35
            pts.add(a); pts.add(a.first to b.second)
        }
        pts.add(stops.last())
        val km = (totalKm * 100.0).roundToInt() / 100.0
        val min = ((km / 24.0) * 60.0 * 10.0).roundToInt() / 10.0
        return RoadRouteResult(pts, km, min, false, "GRID")
    }

    private fun decodePolyline6(encoded: String): List<Pair<Double, Double>> {
        val result = ArrayList<Pair<Double, Double>>()
        var index = 0; var lat = 0; var lng = 0
        while (index < encoded.length) {
            var b: Int; var shift = 0; var res = 0
            do { b = encoded[index++].code - 63; res = res or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            lat += if ((res and 1) != 0) (res shr 1).inv() else (res shr 1)
            shift = 0; res = 0
            do { b = encoded[index++].code - 63; res = res or ((b and 0x1f) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            lng += if ((res and 1) != 0) (res shr 1).inv() else (res shr 1)
            result.add((lat / 1e6) to (lng / 1e6))
        }
        return result
    }
}
