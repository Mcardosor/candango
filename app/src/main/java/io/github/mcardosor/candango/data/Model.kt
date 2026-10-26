package io.github.mcardosor.candango.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

class Stop(val idx: Int, val cod: String, val pos: LatLon) {
    val label get() = "Parada $cod"
}

class Line(val numero: String, val descricao: String, val tarifa: Double?) {
    lateinit var dirs: List<Dir>
}

/** Resultado de projetar um ponto sobre o trajeto. */
data class Projection(val distM: Double, val alongM: Int, val bearing: Double)

/** Um sentido (IDA, VOLTA ou CIRCULAR) de uma linha. */
class Dir(
    val line: Line,
    val sentido: String,
    private val encodedShape: String,
    val lengthM: Int,
    val durMin: Int,
    val durEstimada: Boolean,
    val stopIdx: IntArray,
    val stopAlong: IntArray,
    /** máscara de dias (seg..dom, "S"/"N") -> partidas em minutos desde 00:00 */
    val times: Map<String, IntArray>,
) {
    val shape: List<LatLon> by lazy { decodePolyline(encodedShape) }

    /** Distância acumulada no trajeto simplificado, já escalada para o comprimento oficial. */
    private val cum: DoubleArray by lazy {
        val c = DoubleArray(shape.size)
        for (i in 1 until shape.size) c[i] = c[i - 1] + Geo.dist(shape[i - 1], shape[i])
        val scale = if (c.last() > 0) lengthM / c.last() else 1.0
        for (i in c.indices) c[i] *= scale
        c
    }

    val speedMPerMin: Double get() = lengthM.toDouble() / durMin.coerceAtLeast(1)

    fun minutesBetween(alongFrom: Int, alongTo: Int): Double = (alongTo - alongFrom) / speedMPerMin

    fun project(p: LatLon): Projection {
        var best = Projection(Double.MAX_VALUE, 0, 0.0)
        val k = cos(Math.toRadians(p.lat))
        for (i in 1 until shape.size) {
            val a = shape[i - 1]
            val b = shape[i]
            val bx = (b.lon - a.lon) * k
            val by = b.lat - a.lat
            val px = (p.lon - a.lon) * k
            val py = p.lat - a.lat
            val len2 = bx * bx + by * by
            val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = sqrt((px - t * bx) * (px - t * bx) + (py - t * by) * (py - t * by)) * Geo.M_PER_DEG
            if (d < best.distM) {
                best = Projection(d, (cum[i - 1] + t * (cum[i] - cum[i - 1])).roundToInt(), Geo.bearing(a, b))
            }
        }
        return best
    }

    /** Trecho do trajeto entre duas distâncias ao longo dele. */
    fun shapeBetween(fromM: Int, toM: Int): List<LatLon> {
        val out = ArrayList<LatLon>()
        for (i in 1 until shape.size) {
            val a = cum[i - 1]
            val b = cum[i]
            if (b < fromM) continue
            if (a > toM) break
            if (out.isEmpty()) out += interp(i, fromM.toDouble().coerceAtLeast(a))
            out += if (b <= toM) shape[i] else interp(i, toM.toDouble())
        }
        return out
    }

    private fun interp(i: Int, m: Double): LatLon {
        val a = shape[i - 1]
        val b = shape[i]
        val span = cum[i] - cum[i - 1]
        val t = if (span > 0) ((m - cum[i - 1]) / span).coerceIn(0.0, 1.0) else 0.0
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Partidas do terminal num dia (minutos desde 00:00 daquele dia). */
    fun departures(day: LocalDate): List<Int> {
        val dayIdx = day.dayOfWeek.value - 1 // segunda = 0
        return times.filterKeys { it.getOrNull(dayIdx) == 'S' }.values
            .flatMap { it.asList() }.distinct().sorted()
    }

    /** Horário estimado de passagem num ponto a [alongM] metros do início, para as partidas de [day]. */
    fun passingTimes(day: LocalDate, alongM: Int): List<Int> {
        val offset = (alongM / speedMPerMin).roundToInt()
        return departures(day).map { it + offset }
    }

    val label: String
        get() = when (sentido) {
            "IDA" -> "Ida"
            "VOLTA" -> "Volta"
            else -> "Circular"
        }
}

object Geo {
    const val M_PER_DEG = 111_320.0

    fun dist(a: LatLon, b: LatLon): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        val dx = (b.lon - a.lon) * k
        val dy = b.lat - a.lat
        return sqrt(dx * dx + dy * dy) * M_PER_DEG
    }

    /** Rumo de a para b em graus (0 = norte, sentido horário). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val k = cos(Math.toRadians((a.lat + b.lat) / 2))
        val deg = Math.toDegrees(atan2((b.lon - a.lon) * k, b.lat - a.lat))
        return (deg + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

fun decodePolyline(s: String): List<LatLon> {
    val out = ArrayList<LatLon>()
    var i = 0
    var lat = 0
    var lon = 0
    fun next(): Int {
        var shift = 0
        var result = 0
        while (true) {
            val b = s[i++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return if (result and 1 != 0) (result shr 1).inv() else result shr 1
    }
    while (i < s.length) {
        lat += next()
        lon += next()
        out += LatLon(lat / 1e5, lon / 1e5)
    }
    return out
}

// ---------- carregamento de assets/dados.json (gerado por tools/build-data.mjs) ----------

@Serializable
private class DadosJson(
    val geradoEm: String,
    val stops: List<JsonArray>,
    val lines: List<LineJson>,
)

@Serializable
private class LineJson(val n: String, val d: String, val tarifa: Double? = null, val dirs: List<DirJson>)

@Serializable
private class DirJson(
    val s: String,
    val shape: String,
    val len: Int,
    val dur: Int,
    val durEstimada: Boolean = true,
    val stops: List<List<Int>>,
    val times: Map<String, List<Int>>,
)

object Repo {
    lateinit var geradoEm: String
    lateinit var stops: List<Stop>
    lateinit var lines: List<Line>
    lateinit var lineByNumero: Map<String, Line>
    /** para cada parada, os sentidos de linha que passam nela e a posição (m) ao longo do trajeto */
    lateinit var dirsAtStop: List<List<Pair<Dir, Int>>>

    @Volatile var isLoaded = false
        private set

    fun load(ctx: Context) {
        if (isLoaded) return
        loadFromText(ctx.assets.open("dados.json").bufferedReader().use { it.readText() })
    }

    fun loadFromText(text: String) {
        val json = Json { ignoreUnknownKeys = true }.decodeFromString<DadosJson>(text)
        geradoEm = json.geradoEm
        stops = json.stops.mapIndexed { i, a ->
            Stop(i, a[0].jsonPrimitive.content, LatLon(a[1].jsonPrimitive.double, a[2].jsonPrimitive.double))
        }
        val atStop = List(stops.size) { ArrayList<Pair<Dir, Int>>() }
        lines = json.lines.map { lj ->
            val line = Line(lj.n, lj.d, lj.tarifa)
            line.dirs = lj.dirs.map { dj ->
                val dir = Dir(
                    line, dj.s, dj.shape, dj.len, dj.dur, dj.durEstimada,
                    IntArray(dj.stops.size) { dj.stops[it][0] },
                    IntArray(dj.stops.size) { dj.stops[it][1] },
                    dj.times.mapValues { it.value.toIntArray() },
                )
                for (k in dir.stopIdx.indices) atStop[dir.stopIdx[k]] += dir to dir.stopAlong[k]
                dir
            }
            line
        }
        lineByNumero = lines.associateBy { it.numero }
        dirsAtStop = atStop
        isLoaded = true
    }

    // grade de ~220 m para buscas de proximidade rápidas
    private const val CELL_DEG = 0.002
    private val grid: Map<Long, List<Stop>> by lazy {
        stops.groupBy { cellKey(it.pos.lat, it.pos.lon) }
    }

    private fun cellKey(lat: Double, lon: Double): Long =
        (Math.floor(lat / CELL_DEG).toLong() shl 32) xor (Math.floor(lon / CELL_DEG).toLong() and 0xffffffffL)

    fun stopsNear(p: LatLon, radiusM: Double): List<Pair<Stop, Double>> {
        val r = (radiusM / (Geo.M_PER_DEG * 0.96) / CELL_DEG).toInt() + 1 // cos(15,8°) ≈ 0,96
        val cy = Math.floor(p.lat / CELL_DEG).toLong()
        val cx = Math.floor(p.lon / CELL_DEG).toLong()
        val out = ArrayList<Pair<Stop, Double>>()
        for (dy in -r..r) for (dx in -r..r) {
            val key = ((cy + dy) shl 32) xor ((cx + dx) and 0xffffffffL)
            for (s in grid[key] ?: continue) {
                val d = Geo.dist(s.pos, p)
                if (d <= radiusM) out += s to d
            }
        }
        return out.sortedBy { it.second }
    }

    private val neighborCache = HashMap<Int, List<Pair<Stop, Double>>>()

    /** Paradas a até [TRANSFER_RADIUS_M] de uma parada (inclui ela mesma), para baldeação a pé. */
    fun transferNeighbors(stop: Stop): List<Pair<Stop, Double>> = synchronized(neighborCache) {
        neighborCache.getOrPut(stop.idx) { stopsNear(stop.pos, TRANSFER_RADIUS_M) }
    }

    // terminais grandes (Rodoviária, Gama, Taguatinga) têm plataformas de chegada e saída a 200–400 m umas das outras
    const val TRANSFER_RADIUS_M = 400.0
}
