package ph.alisto.kernel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
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
    private var pendingUserLocJson: String? = null
    private var pendingDropPinJson: String? = null
    private var tapCallback: ((Double, Double) -> Unit)? = null
    private var centerMoveCallback: ((Double, Double) -> Unit)? = null

    init {
        setBackgroundColor(Color.parseColor("#070A12"))
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupWebView()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.parseColor("#070A12"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "AlistoNetworkPH/4.0 (Android Mobile; https://dacortezalistoapp.github.io/AlistoApp/)"
        }

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
                mainHandler.post { tapCallback?.invoke(lat, lng) }
            }

            @JavascriptInterface
            fun onMapCenterMoved(lat: Double, lng: Double) {
                mainHandler.post { centerMoveCallback?.invoke(lat, lng) }
            }
        }, "AlistoMapBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                pendingPayloadJson?.let { pushPayloadToJs(it) }
                pendingUserLocJson?.let { pushUserLocToJs(it) }
                pendingDropPinJson?.let { pushDropPinToJs(it) }
            }
        }
    }

    fun recenter(lat: Double, lng: Double, zoom: Int = 16) {
        if (!mapReady) return
        mainHandler.post {
            webView.evaluateJavascript("if (window.recenterAlistoMap) { window.recenterAlistoMap($lat, $lng, $zoom); }", null)
        }
    }

    fun updateUserLocation(lat: Double, lng: Double, accuracyM: Float = 25f, flyTo: Boolean = false) {
        val obj = JSONObject().apply {
            put("lat", lat); put("lng", lng); put("accuracy", accuracyM.toDouble()); put("flyTo", flyTo)
        }.toString()
        pendingUserLocJson = obj
        if (mapReady) pushUserLocToJs(obj)
    }

    fun setDropPinMode(
        active: Boolean,
        label: String = "Drag map to place pin",
        colorHex: String = "#10B981",
        onCenterChanged: ((Double, Double) -> Unit)? = null
    ) {
        centerMoveCallback = if (active) onCenterChanged else null
        val obj = JSONObject().apply {
            put("active", active)
            put("label", label)
            put("color", colorHex)
        }.toString()
        pendingDropPinJson = obj
        if (mapReady) pushDropPinToJs(obj)
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
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <style>
    html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #070a12; overflow: hidden; }
    .alisto-dark-tiles { filter: invert(100%) hue-rotate(180deg) brightness(93%) contrast(90%); }
    .leaflet-control-attribution { background: rgba(7,10,18,0.72) !important; color: #94a3b8 !important; font-size: 9px !important; }
    .leaflet-control-attribution a { color: #38bdf8 !important; }
    .leaflet-control-layers { background: rgba(15,23,42,0.92) !important; color: #f8fafc !important; border: 1px solid #334155 !important; border-radius: 12px !important; }
    .leaflet-control-layers-toggle { filter: invert(90%); }
    .leaflet-popup-content-wrapper { background: #0f172a; color: #f8fafc; border: 1px solid #334155; border-radius: 12px; font-family: sans-serif; font-size: 12px; }
    .leaflet-popup-tip { background: #0f172a; }
    .wp-label { background: #fbbf24; color: #070a12; font-weight: 800; border-radius: 999px; width: 24px; height: 24px; line-height: 24px; text-align: center; border: 2px solid #070a12; font-size: 11px; }
    .gps-wrap { position: relative; width: 24px; height: 24px; }
    .gps-pulse { position: absolute; inset: 0; border-radius: 999px; background: rgba(56,189,248,0.45); animation: gpsPulse 1.8s ease-out infinite; }
    .gps-core { position: absolute; top: 5px; left: 5px; width: 14px; height: 14px; border-radius: 999px; background: #2563eb; border: 2.5px solid #ffffff; box-shadow: 0 0 8px rgba(37,99,235,0.85); }
    @keyframes gpsPulse { 0% { transform: scale(0.7); opacity: 0.9; } 100% { transform: scale(2.4); opacity: 0; } }
    #dropPinOverlay { display: none; position: fixed; left: 50%; top: 50%; transform: translate(-50%, -100%); z-index: 999; pointer-events: none; text-align: center; }
    #dropPinTag { background: rgba(13,20,36,0.94); color: #f8fafc; font-family: sans-serif; font-size: 11px; font-weight: 700; padding: 4px 10px; border-radius: 999px; border: 1px solid #38bdf8; white-space: nowrap; margin-bottom: 4px; box-shadow: 0 4px 12px rgba(0,0,0,0.5); }
    #dropPinHead { width: 20px; height: 20px; border-radius: 999px; border: 3px solid #ffffff; margin: 0 auto; box-shadow: 0 3px 10px rgba(0,0,0,0.6); }
    #dropPinStem { width: 3px; height: 14px; background: #ffffff; margin: -2px auto 0; border-radius: 2px; }
    #dropPinShadow { width: 12px; height: 5px; border-radius: 999px; background: rgba(0,0,0,0.55); margin: -2px auto 0; }
  </style>
</head>
<body>
  <div id="map"></div>
  <div id="dropPinOverlay">
    <div id="dropPinTag">Drag map to set pin</div>
    <div id="dropPinHead"></div>
    <div id="dropPinStem"></div>
    <div id="dropPinShadow"></div>
  </div>
  <script>
    const map = L.map('map', { zoomControl: false, attributionControl: true }).setView([$centerLat, $centerLng], 15);
    const osmDark = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, className: 'alisto-dark-tiles', attribution: '&copy; OpenStreetMap' });
    const osmLight = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap' });
    const esriDark = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19, className: 'alisto-dark-tiles', attribution: 'Tiles &copy; Esri' });
    const esriSat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19, attribution: 'Tiles &copy; Esri' });

    let osmErr = 0;
    osmDark.on('tileerror', function() {
      osmErr++;
      if (osmErr === 3 && map.hasLayer(osmDark)) { map.removeLayer(osmDark); esriDark.addTo(map); }
    });

    osmDark.addTo(map);
    L.control.layers({ 'Dark Street': osmDark, 'Esri Dark': esriDark, 'Light Street': osmLight, 'Satellite': esriSat }, null, { position: 'bottomright' }).addTo(map);

    const layerGroup = L.layerGroup().addTo(map);
    const userLocGroup = L.layerGroup().addTo(map);
    let dropPinActive = false;

    if (typeof ResizeObserver !== 'undefined') { new ResizeObserver(function() { map.invalidateSize(); }).observe(document.getElementById('map')); }
    setTimeout(function() { map.invalidateSize(); }, 250);

    window.recenterAlistoMap = function(lat, lng, zoom) { map.flyTo([lat, lng], zoom || 16, { duration: 0.55 }); };

    window.setDropPinModeJs = function(cfg) {
      dropPinActive = !!(cfg && cfg.active);
      const ov = document.getElementById('dropPinOverlay');
      const tag = document.getElementById('dropPinTag');
      const head = document.getElementById('dropPinHead');
      if (!ov) return;
      ov.style.display = dropPinActive ? 'block' : 'none';
      if (dropPinActive) {
        tag.textContent = cfg.label || 'Drag map to set pin';
        tag.style.borderColor = cfg.color || '#10b981';
        head.style.background = cfg.color || '#10b981';
        const c = map.getCenter();
        if (window.AlistoMapBridge) window.AlistoMapBridge.onMapCenterMoved(c.lat, c.lng);
      }
    };

    map.on('moveend', function() {
      if (dropPinActive && window.AlistoMapBridge) {
        const c = map.getCenter();
        window.AlistoMapBridge.onMapCenterMoved(c.lat, c.lng);
      }
    });

    window.updateUserGpsDot = function(loc) {
      userLocGroup.clearLayers();
      if (!loc || typeof loc.lat !== 'number' || typeof loc.lng !== 'number') return;
      const acc = Math.min(Math.max(loc.accuracy || 20, 10), 160);
      L.circle([loc.lat, loc.lng], { radius: acc, color: '#38bdf8', weight: 1, fillColor: '#2563eb', fillOpacity: 0.16, interactive: false }).addTo(userLocGroup);
      const gpsIcon = L.divIcon({ className: 'gps-icon', html: '<div class="gps-wrap"><div class="gps-pulse"></div><div class="gps-core"></div></div>', iconSize: [24, 24], iconAnchor: [12, 12] });
      L.marker([loc.lat, loc.lng], { icon: gpsIcon, zIndexOffset: 900 }).bindPopup('<b>Your Live GPS</b> (&plusmn;' + Math.round(acc) + 'm)').addTo(userLocGroup);
      if (loc.flyTo && !dropPinActive) map.flyTo([loc.lat, loc.lng], 16, { duration: 0.6 });
    };

    if (${if (enableTapToPin) "true" else "false"}) {
      map.on('click', function(e) {
        if (dropPinActive) {
          map.panTo(e.latlng, { animate: true });
        } else if (window.AlistoMapBridge) {
          window.AlistoMapBridge.onMapTapped(e.latlng.lat, e.latlng.lng);
        }
      });
    }

    window.updateAlistoLayers = function(data) {
      layerGroup.clearLayers();
      if (!data) return;

      (data.heatClusters || []).forEach(function(c) {
        const r = Math.min(2200, 320 + ((c.demand || 1) * 200));
        const col = (c.demand || 1) >= 5 ? '#ef4444' : ((c.demand || 1) >= 3 ? '#f59e0b' : '#38bdf8');
        L.circle([c.lat, c.lng], { radius: r, color: col, weight: 1.5, fillColor: col, fillOpacity: 0.24 })
          .bindPopup('<b>Demand Zone:</b> ' + c.label + '<br/>Requests: ' + c.demand).addTo(layerGroup);
      });

      const routePts = [];
      if (data.origin && typeof data.origin.lat === 'number') {
        routePts.push([data.origin.lat, data.origin.lng]);
        L.circleMarker([data.origin.lat, data.origin.lng], { radius: 9, color: '#ffffff', weight: 3, fillColor: '#10b981', fillOpacity: 0.98 })
          .bindPopup('<b>Pickup / Job Site</b><br/>' + (data.origin.address || '')).addTo(layerGroup);
      }

      (data.waypoints || []).forEach(function(w) {
        routePts.push([w.lat, w.lng]);
        const icon = L.divIcon({ className: 'wp-icon', html: '<div class="wp-label">' + w.stop_number + '</div>', iconSize: [24, 24] });
        L.marker([w.lat, w.lng], { icon: icon }).bindPopup('<b>Stop #' + w.stop_number + '</b><br/>' + (w.address || '')).addTo(layerGroup);
      });

      if (data.dropoff && typeof data.dropoff.lat === 'number') {
        routePts.push([data.dropoff.lat, data.dropoff.lng]);
        L.circleMarker([data.dropoff.lat, data.dropoff.lng], { radius: 9, color: '#ffffff', weight: 3, fillColor: '#f43f5e', fillOpacity: 0.98 })
          .bindPopup('<b>Drop-Off / Destination</b><br/>' + (data.dropoff.address || '')).addTo(layerGroup);
      }

      const snapped = (data.routePoints || []).filter(function(pt){ return Array.isArray(pt) && pt.length >= 2; }); if (snapped.length >= 2) L.polyline(snapped, { color: '#00f2fe', weight: 4.5, opacity: 0.95 }).addTo(layerGroup);

      (data.bookings || []).forEach(function(b) {
        L.circleMarker([b.lat, b.lng], { radius: 7, color: '#fbbf24', weight: 2, fillColor: '#f59e0b', fillOpacity: 0.9 })
          .bindPopup('<b>' + b.ref + ' [' + b.status + ']</b><br/>' + b.title + '<br/>PHP ' + b.fee).addTo(layerGroup);
      });

      (data.telemetry || []).forEach(function(t) {
        L.circleMarker([t.lat, t.lng], { radius: 10, color: '#ffffff', weight: 3, fillColor: '#00f2fe', fillOpacity: 1.0 })
          .bindPopup('<b>LIVE GPS &bull; ' + t.ref + '</b><br/>' + t.status + ' &bull; ' + t.speed + ' km/h').addTo(layerGroup);
      });
    };
  </script>
</body>
</html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://dacortezalistoapp.github.io/AlistoApp/", html, "text/html", "UTF-8", null)
    }

    fun updateMapLayers(
        originLat: Double? = null,
        originLng: Double? = null,
        originAddress: String = "",
        dropoffLat: Double? = null,
        dropoffLng: Double? = null,
        dropoffAddress: String = "",
        waypoints: List<JSONObject> = emptyList(),
        bookings: List<BookingRecord> = emptyList(),
        routePoints: List<Pair<Double, Double>> = emptyList(),
        telemetryPins: List<LiveTelemetryPin> = emptyList(),
        heatClusters: List<HeatmapCluster> = emptyList()
    ) {
        val root = JSONObject()
        if (originLat != null && originLng != null) {
            root.put("origin", JSONObject().apply {
                put("lat", originLat); put("lng", originLng); put("address", originAddress)
            })
        }
        if (dropoffLat != null && dropoffLng != null) {
            root.put("dropoff", JSONObject().apply {
                put("lat", dropoffLat); put("lng", dropoffLng); put("address", dropoffAddress)
            })
        }
        root.put("waypoints", JSONArray().apply { waypoints.forEach { put(it) } })
        root.put("routePoints", JSONArray().apply { routePoints.forEach { pt -> put(JSONArray().apply { put(pt.first); put(pt.second) }) } })
        root.put("bookings", JSONArray().apply {
            bookings.filter { it.status !in listOf("COMPLETED", "CANCELLED", "EXPIRED") }.forEach { b ->
                put(JSONObject().apply {
                    put("ref", b.bookingRef); put("status", b.status); put("title", b.serviceTitle)
                    put("fee", "%.2f".format(b.quotedFee)); put("lat", b.pickupLat); put("lng", b.pickupLng)
                })
            }
        })
        root.put("telemetry", JSONArray().apply {
            telemetryPins.forEach { t ->
                put(JSONObject().apply {
                    put("ref", t.bookingRef); put("status", t.bookingStatus)
                    put("speed", "%.1f".format(t.speedKph)); put("lat", t.lat); put("lng", t.lng)
                })
            }
        })
        root.put("heatClusters", JSONArray().apply {
            heatClusters.forEach { h ->
                put(JSONObject().apply { put("label", h.label); put("demand", h.demandCount); put("lat", h.lat); put("lng", h.lng) })
            }
        })

        val jsonStr = root.toString()
        pendingPayloadJson = jsonStr
        if (mapReady) pushPayloadToJs(jsonStr)
    }

    private fun pushPayloadToJs(jsonStr: String) {
        mainHandler.post {
            webView.evaluateJavascript("if (window.updateAlistoLayers) { window.updateAlistoLayers($jsonStr); }", null)
        }
    }

    private fun pushUserLocToJs(jsonStr: String) {
        mainHandler.post {
            webView.evaluateJavascript("if (window.updateUserGpsDot) { window.updateUserGpsDot($jsonStr); }", null)
        }
    }

    private fun pushDropPinToJs(jsonStr: String) {
        mainHandler.post {
            webView.evaluateJavascript("if (window.setDropPinModeJs) { window.setDropPinModeJs($jsonStr); }", null)
        }
    }
}
