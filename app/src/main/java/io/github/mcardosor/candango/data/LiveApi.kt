package io.github.mcardosor.candango.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Ônibus visto no GPS da SEMOB. */
data class Bus(
    val id: String,
    val linha: String?,
    val pos: LatLon,
    val timeMs: Long,
    val speedKmh: Double?,
    val heading: Double?,
)

/** Lugar retornado pela busca de endereços. */
data class Place(val name: String, val detail: String, val pos: LatLon)

object LiveApi {
    private const val GPS_URL = "https://www.sistemas.dftrans.df.gov.br/service/gps/operacoes"
    private const val PHOTON_URL = "https://photon.komoot.io/api/"
    private const val USER_AGENT = "Candango/0.2 (+https://github.com/Mcardosor/candango)"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable private class GpsOp(val veiculos: List<GpsVeic> = emptyList())
    @Serializable private class GpsVeic(
        val numero: String = "",
        val linha: String? = null,
        val horario: Long = 0,
        val localizacao: Loc? = null,
        val velocidade: Vel? = null,
        val direcao: Double? = null,
    )
    @Serializable private class Loc(val latitude: Double = 0.0, val longitude: Double = 0.0)
    @Serializable private class Vel(val valor: Double? = null)

    suspend fun fetchBuses(): List<Bus> = withContext(Dispatchers.IO) {
        val ops = json.decodeFromString<List<GpsOp>>(get(GPS_URL))
        ops.flatMap { it.veiculos }.mapNotNull { v ->
            val loc = v.localizacao ?: return@mapNotNull null
            if (loc.latitude == 0.0) return@mapNotNull null
            Bus(
                id = v.numero,
                linha = v.linha?.takeIf { it.isNotBlank() },
                pos = LatLon(loc.latitude, loc.longitude),
                timeMs = v.horario,
                speedKmh = v.velocidade?.valor,
                heading = v.direcao,
            )
        }
    }

    /** Busca de endereços (Photon/OpenStreetMap), limitada ao Distrito Federal. */
    suspend fun searchPlaces(query: String): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$PHOTON_URL?q=$q&limit=8&lang=default&lat=-15.79&lon=-47.88&bbox=-48.30,-16.06,-47.30,-15.49"
        val root = json.parseToJsonElement(get(url)).jsonObject
        root["features"]?.jsonArray?.mapNotNull { f ->
            val o = f.jsonObject
            val c = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val p = o["properties"]?.jsonObject ?: JsonObject(emptyMap())
            fun s(k: String) = p[k]?.jsonPrimitive?.content
            val name = s("name") ?: listOfNotNull(s("street"), s("housenumber")).joinToString(", ").ifBlank { null }
                ?: return@mapNotNull null
            val detail = listOfNotNull(s("street")?.takeIf { it != name }, s("district"), s("city"))
                .distinct().joinToString(" · ")
            Place(name, detail, LatLon(c[1].jsonPrimitive.double, c[0].jsonPrimitive.double))
        }?.distinctBy { it.name to it.detail } ?: emptyList() // o OSM repete o mesmo lugar (bairro, rua, ponto)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
