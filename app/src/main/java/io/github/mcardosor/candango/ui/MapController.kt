package io.github.mcardosor.candango.ui

import android.graphics.PointF
import io.github.mcardosor.candango.data.Bus
import io.github.mcardosor.candango.data.LatLon
import io.github.mcardosor.candango.data.Stop
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Linha desenhada no mapa. */
data class MapLine(val points: List<LatLon>, val color: String, val dashed: Boolean = false)

/** Marcador simples (origem, destino). A minha localização tem camada própria. */
data class MapMarker(val pos: LatLon, val kind: String, val label: String = "")

/** Ônibus desenhado no mapa. */
data class MapBus(val bus: Bus, val color: String, val highlight: Boolean)

class MapController(private val map: MapLibreMap, private val style: Style) {

    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        val BRASILIA = LatLng(-15.7939, -47.8828)
        private const val FONT = "Noto Sans Bold"
        private const val ME_COLOR = "#1E88E5"
        private const val MAX_FIT_ZOOM = 17.0
    }

    private val allStopsSrc = GeoJsonSource("todas-paradas")
    private val linesSrc = GeoJsonSource("rotas")
    private val stopsSrc = GeoJsonSource("paradas")
    private val busesSrc = GeoJsonSource("onibus")
    private val markersSrc = GeoJsonSource("marcadores")
    private val meSrc = GeoJsonSource("eu")

    init {
        style.addSource(allStopsSrc)
        style.addSource(linesSrc)
        style.addSource(stopsSrc)
        style.addSource(busesSrc)
        style.addSource(markersSrc)
        style.addSource(meSrc)

        // todas as paradas, só com zoom de rua (tocáveis)
        style.addLayer(CircleLayer("todas-paradas-camada", "todas-paradas").withProperties(
            circleRadius(4f), circleColor("#FFFFFF"), circleStrokeColor("#0B8A5F"), circleStrokeWidth(2f),
        ).apply { minZoom = 14.5f })

        style.addLayer(LineLayer("rotas-contorno", "rotas").withProperties(
            lineColor("#FFFFFF"), lineWidth(9f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(org.maplibre.android.style.expressions.Expression.eq(get("dashed"), literal(false))))
        style.addLayer(LineLayer("rotas-linha", "rotas").withProperties(
            lineColor(toColor(get("color"))), lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        ).withFilter(org.maplibre.android.style.expressions.Expression.eq(get("dashed"), literal(false))))
        style.addLayer(LineLayer("rotas-pe", "rotas").withProperties(
            lineColor(toColor(get("color"))), lineWidth(4f), lineDasharray(arrayOf(0.5f, 1.5f)), lineCap(Property.LINE_CAP_ROUND),
        ).withFilter(org.maplibre.android.style.expressions.Expression.eq(get("dashed"), literal(true))))

        style.addLayer(CircleLayer("paradas-camada", "paradas").withProperties(
            circleRadius(5f), circleColor("#FFFFFF"), circleStrokeColor(toColor(get("color"))), circleStrokeWidth(2.5f),
        ))

        style.addLayer(CircleLayer("onibus-camada", "onibus").withProperties(
            circleRadius(org.maplibre.android.style.expressions.Expression.switchCase(get("highlight"), literal(11f), literal(7f))),
            circleColor(toColor(get("color"))), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
        ))
        style.addLayer(SymbolLayer("onibus-rotulo", "onibus").withProperties(
            textField(get("label")), textFont(arrayOf(FONT)), textSize(11f), textColor("#1B1B1F"),
            textHaloColor("#FFFFFF"), textHaloWidth(1.5f), textOffset(arrayOf(0f, 1.5f)),
            textAllowOverlap(false), textIgnorePlacement(false),
        ).apply { minZoom = 12.5f })

        style.addLayer(CircleLayer("marcadores-camada", "marcadores").withProperties(
            circleRadius(8f),
            circleColor(org.maplibre.android.style.expressions.Expression.match(
                get("kind"), literal("#455A64"),
                org.maplibre.android.style.expressions.Expression.stop("origem", "#2E7D32"),
                org.maplibre.android.style.expressions.Expression.stop("destino", "#C62828"),
            )),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
        ))
        style.addLayer(SymbolLayer("marcadores-rotulo", "marcadores").withProperties(
            textField(get("label")), textFont(arrayOf(FONT)), textSize(12f), textColor("#1B1B1F"),
            textHaloColor("#FFFFFF"), textHaloWidth(2f), textOffset(arrayOf(0f, -1.6f)),
        ))

        // "você está aqui": halo translúcido + ponto azul maior que os ônibus, sempre por cima de tudo
        style.addLayer(CircleLayer("eu-halo", "eu").withProperties(
            circleRadius(22f), circleColor(ME_COLOR), circleOpacity(0.18f),
            circleStrokeColor(ME_COLOR), circleStrokeWidth(1f),
        ))
        style.addLayer(CircleLayer("eu-ponto", "eu").withProperties(
            circleRadius(9f), circleColor(ME_COLOR), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3.5f),
        ))

        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
    }

    fun setLines(lines: List<MapLine>) {
        linesSrc.setGeoJson(FeatureCollection.fromFeatures(lines.filter { it.points.size >= 2 }.map { l ->
            Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty("color", l.color)
                addBooleanProperty("dashed", l.dashed)
            }
        }))
    }

    fun setAllStops(stops: List<Stop>) {
        allStopsSrc.setGeoJson(FeatureCollection.fromFeatures(stops.map { s ->
            Feature.fromGeometry(Point.fromLngLat(s.pos.lon, s.pos.lat)).apply { addNumberProperty("idx", s.idx) }
        }))
    }

    fun setStops(stops: List<Stop>, color: String) = setColoredStops(stops.map { it to color })

    /** paradas destacadas, cada uma com a cor da sua linha */
    fun setColoredStops(stops: List<Pair<Stop, String>>) {
        stopsSrc.setGeoJson(FeatureCollection.fromFeatures(stops.map { (s, color) ->
            Feature.fromGeometry(Point.fromLngLat(s.pos.lon, s.pos.lat)).apply {
                addStringProperty("color", color)
                addNumberProperty("idx", s.idx)
            }
        }))
    }

    fun setBuses(buses: List<MapBus>) {
        busesSrc.setGeoJson(FeatureCollection.fromFeatures(buses.map { b ->
            Feature.fromGeometry(Point.fromLngLat(b.bus.pos.lon, b.bus.pos.lat)).apply {
                addStringProperty("color", b.color)
                addStringProperty("label", b.bus.linha ?: "")
                addStringProperty("linha", b.bus.linha ?: "")
                addBooleanProperty("highlight", b.highlight)
            }
        }))
    }

    fun setMarkers(markers: List<MapMarker>) {
        markersSrc.setGeoJson(FeatureCollection.fromFeatures(markers.map { m ->
            Feature.fromGeometry(Point.fromLngLat(m.pos.lon, m.pos.lat)).apply {
                addStringProperty("kind", m.kind)
                addStringProperty("label", m.label)
            }
        }))
    }

    fun setMe(p: LatLon?) {
        meSrc.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(p?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) })
        ))
    }

    /** O que há sob o toque: uma parada (índice) ou a linha de um ônibus. */
    fun hitTest(latLng: LatLng): Pair<Int?, String?> {
        val pt: PointF = map.projection.toScreenLocation(latLng)
        val box = android.graphics.RectF(pt.x - 24, pt.y - 24, pt.x + 24, pt.y + 24)
        val bus = map.queryRenderedFeatures(box, "onibus-camada").firstOrNull()?.getStringProperty("linha")?.takeIf { it.isNotEmpty() }
        val stop = map.queryRenderedFeatures(box, "paradas-camada", "todas-paradas-camada").firstOrNull()?.getNumberProperty("idx")?.toInt()
        return stop to bus
    }

    /** Enquadra os pontos na faixa visível do mapa, entre o cartão de cima e o painel de baixo. */
    fun fit(points: List<LatLon>, bottomPaddingPx: Int, topPaddingPx: Int) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            center(points[0], 15.0, bottomPaddingPx, topPaddingPx)
            return
        }
        val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        val cam = map.getCameraForLatLngBounds(b, intArrayOf(80, topPaddingPx, 80, bottomPaddingPx)) ?: return
        // pontos muito próximos (ex.: você a 50 m da parada) dariam zoom exagerado
        val zoom = cam.zoom.coerceAtMost(MAX_FIT_ZOOM)
        map.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(cam).zoom(zoom).padding(0.0, topPaddingPx.toDouble(), 0.0, bottomPaddingPx.toDouble()).build()
        ))
    }

    fun center(p: LatLon, zoom: Double, bottomPaddingPx: Int, topPaddingPx: Int) {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(zoom)
                .padding(0.0, topPaddingPx.toDouble(), 0.0, bottomPaddingPx.toDouble()).build()
        ))
    }

    val cameraCenter: LatLon
        get() = map.cameraPosition.target?.let { LatLon(it.latitude, it.longitude) } ?: LatLon(BRASILIA.latitude, BRASILIA.longitude)
}
