package ph.alisto.kernel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

class AlistoInteractiveMapView(context: Context) : FrameLayout(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webView = WebView(context)
    private var mapReady = false
    private var pendingPayloadJson: String? = null
    private var tapCallback: ((Double, Double) -> Unit)? = null

    init {
        setBackgroundColor(Color.parseColor("#070D19"))
        addView(
            webView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#070D19"))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true

        webView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMapTapped(lat: Double, lng: Double) {
                mainHandler.post {
                    tapCallback?.invoke(lat, lng)
                }
            }
        }, "AlistoMapBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                pendingPayloadJson?.let { pushPayloadToJs(it) }
            }
        }
    }

    fun loadInteractiveMap(
        centerLat: Double,
        centerLng: Double,
        enableTapToPin: Boolean,
        onMapTap: ((Double, Double) -> Unit)? = null
    ) {
        tapCallback = onMapTap
        mapReady = false

        val html = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #070d19; }
    .leaflet-popup-content-wrapper { background: #0f172a; color: #f8fafc; border: 1px solid #334155; font-family: sans-serif; font-size: 12px; }
    .leaflet-popup-tip { background: #0f172a; }
    .wp-label { background: #fbbf24; color: #070d19; font-weight: 800; border-radius: 999px; width: 22px; height: 22px; line-height: 22px; text-align: center; border: 2px solid #070d19; font-size: 11px; }
  </style>
</head>
<body>
  <div id="map"></div>
  <script>
    const map = L.map('map', { zoomControl: true }).setView([$centerLat, $centerLng], 14);
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      maxZoom: 19,
      attribution: '&copy; OSM &copy; CARTO'
    }).addTo(map);

    const layerGroup = L.layerGroup().addTo(map);

    if (${if (enableTapToPin) "true" else "false"}) {
      map.on('click', function(e) {
        if (window.AlistoMapBridge) {
          window.AlistoMapBridge.onMapTapped(e.latlng.lat, e.latlng.lng);
        }
      });
    }

    window.updateAlistoLayers = function(data) {
      layerGroup.clearLayers();
      if (!data) return;

      (data.heatClusters || []).forEach(function(c) {
        const radius = Math.min(2200, 320 + ((c.demand || 1) * 200));
        const col = (c.demand || 1) >= 5 ? '#ef4444' : ((c.demand || 1) >= 3 ? '#f59e0b' : '#38bdf8');
        L.circle([c.lat, c.lng], {
          radius: radius, color: col, weight: 1.5, fillColor: col, fillOpacity: 0.24
        }).bindPopup('<b>Demand Zone:</b> ' + c.label + '<br/>Requests: ' + c.demand).addTo(layerGroup);
      });

      if (data.origin && typeof data.origin.lat === 'number') {
        L.circleMarker([data.origin.lat, data.origin.lng], {
          radius: 8, color: '#ffffff', weight: 2, fillColor: '#10b981', fillOpacity: 0.95
        }).bindPopup('<b>Origin / Job Site Pin</b><br/>' + (data.origin.address || '')).addTo(layerGroup);
      }

      const routePts = [];
      if (data.origin && typeof data.origin.lat === 'number') {
        routePts.push([data.origin.lat, data.origin.lng]);
      }
      (data.waypoints || []).forEach(function(w) {
        routePts.push([w.lat, w.lng]);
        const icon = L.divIcon({ className: 'wp-icon', html: '<div class="wp-label">' + w.stop_number + '</div>', iconSize: [22, 22] });
        L.marker([w.lat, w.lng], { icon: icon })
          .bindPopup('<b>Stop #' + w.stop_number + '</b><br/>' + (w.address || ''))
          .addTo(layerGroup);
      });
      if (routePts.length >= 2) {
        L.polyline(routePts, { color: '#fbbf24', weight: 3, dashArray: '6,6' }).addTo(layerGroup);
      }

      (data.bookings || []).forEach(function(b) {
        L.circleMarker([b.lat, b.lng], {
          radius: 7, color: '#fbbf24', weight: 2, fillColor: '#f59e0b', fillOpacity: 0.85
        }).bindPopup('<b>' + b.ref + ' [' + b.status + ']</b><br/>' + b.title + '<br/>PHP ' + b.fee).addTo(layerGroup);
      });

      (data.telemetry || []).forEach(function(t) {
        L.circleMarker([t.lat, t.lng], {
          radius: 9, color: '#ffffff', weight: 2.5, fillColor: '#00f2fe', fillOpacity: 1.0
        }).bindPopup('<b>LIVE TRACKING • ' + t.ref + '</b><br/>' + t.status + ' • ' + t.speed + ' km/h').addTo(layerGroup);
      });
    };
  </script>
</body>
</html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://alisto.local/", html, "text/html", "UTF-8", null)
    }

    fun updateMapLayers(
        originLat: Double? = null,
        originLng: Double? = null,
        originAddress: String = "",
        waypoints: List<JSONObject> = emptyList(),
        bookings: List<BookingRecord> = emptyList(),
        telemetryPins: List<LiveTelemetryPin> = emptyList(),
        heatClusters: List<HeatmapCluster> = emptyList()
    ) {
        val root = JSONObject()
        if (originLat != null && originLng != null) {
            root.put("origin", JSONObject().apply {
                put("lat", originLat)
                put("lng", originLng)
                put("address", originAddress)
            })
        }

        val wpArr = JSONArray()
        waypoints.forEach { wpArr.put(it) }
        root.put("waypoints", wpArr)

        val bArr = JSONArray()
        bookings.filter { it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED") }.forEach { b ->
            bArr.put(JSONObject().apply {
                put("ref", b.bookingRef)
                put("status", b.status)
                put("title", b.serviceTitle)
                put("fee", "%.2f".format(b.quotedFee))
                put("lat", b.pickupLat)
                put("lng", b.pickupLng)
            })
        }
        root.put("bookings", bArr)

        val tArr = JSONArray()
        telemetryPins.forEach { t ->
            tArr.put(JSONObject().apply {
                put("ref", t.bookingRef)
                put("status", t.bookingStatus)
                put("speed", "%.1f".format(t.speedKph))
                put("lat", t.lat)
                put("lng", t.lng)
            })
        }
        root.put("telemetry", tArr)

        val hArr = JSONArray()
        heatClusters.forEach { h ->
            hArr.put(JSONObject().apply {
                put("label", h.label)
                put("demand", h.demandCount)
                put("lat", h.lat)
                put("lng", h.lng)
            })
        }
        root.put("heatClusters", hArr)

        val jsonStr = root.toString()
        pendingPayloadJson = jsonStr
        if (mapReady) {
            pushPayloadToJs(jsonStr)
        }
    }

    private fun pushPayloadToJs(jsonStr: String) {
        mainHandler.post {
            webView.evaluateJavascript("if (window.updateAlistoLayers) { window.updateAlistoLayers($jsonStr); }", null)
        }
    }
}
