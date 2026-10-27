package io.github.mcardosor.candango.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lugar salvo (Casa, Trabalho ou destino recente). */
@Serializable
data class SavedPlace(val name: String, val lat: Double, val lon: Double) {
    val pos: LatLon get() = LatLon(lat, lon)
}

@Serializable
private data class FavData(
    val places: Map<String, SavedPlace> = emptyMap(),
    val lines: List<String> = emptyList(),
    val recents: List<SavedPlace> = emptyList(),
)

/** Favoritos e destinos recentes, guardados só neste aparelho. Mesmo formato da versão web. */
class Favorites(ctx: Context) {
    companion object {
        const val HOME = "casa"
        const val WORK = "trabalho"
        private const val KEY = "favoritos"
        private const val MAX_RECENTS = 5
        private const val SAME_PLACE_M = 30.0
    }

    private val prefs = ctx.getSharedPreferences("candango", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // estado do Compose: a tela se atualiza sozinha quando um favorito muda.
    // Dado salvo corrompido não pode derrubar o app.
    private var data: FavData by mutableStateOf(
        runCatching { json.decodeFromString<FavData>(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(FavData()),
    )

    private fun save() = prefs.edit().putString(KEY, json.encodeToString(FavData.serializer(), data)).apply()

    fun place(key: String): SavedPlace? = data.places[key]

    fun setPlace(key: String, p: SavedPlace?) {
        data = data.copy(places = if (p == null) data.places - key else data.places + (key to p))
        save()
    }

    val lines: List<String> get() = data.lines

    fun hasLine(numero: String) = numero in data.lines

    fun toggleLine(numero: String) {
        data = data.copy(lines = if (numero in data.lines) data.lines - numero else data.lines + numero)
        save()
    }

    val recents: List<SavedPlace> get() = data.recents

    fun addRecent(p: SavedPlace) {
        val rest = data.recents.filter { Geo.dist(it.pos, p.pos) >= SAME_PLACE_M }
        data = data.copy(recents = (listOf(p) + rest).take(MAX_RECENTS))
        save()
    }
}
