package io.github.mcardosor.candango.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mcardosor.candango.data.Bus
import io.github.mcardosor.candango.data.LatLon
import io.github.mcardosor.candango.data.Line
import io.github.mcardosor.candango.data.LiveApi
import io.github.mcardosor.candango.data.Place
import io.github.mcardosor.candango.data.PlanOption
import io.github.mcardosor.candango.data.Planner
import io.github.mcardosor.candango.data.Repo
import io.github.mcardosor.candango.data.Stop
import io.github.mcardosor.candango.data.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import kotlin.math.abs

sealed interface Screen {
    data object Home : Screen
    data object Plan : Screen
    data class Route(val option: PlanOption) : Screen
    data class LineView(val line: Line, val dirIndex: Int = 0) : Screen
    data class StopView(val stop: Stop) : Screen
}

enum class Field { ORIGIN, DEST }

data class Endpoint(val name: String, val pos: LatLon, val isMyLocation: Boolean = false)

/** Resultado da busca: lugar, linha ou parada. */
sealed interface SearchHit {
    data class PlaceHit(val place: Place) : SearchHit
    data class LineHit(val line: Line) : SearchHit
    data class StopHit(val stop: Stop) : SearchHit
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val WINDOW_MIN = 180 // "agora até daqui a 3 horas"
        private const val POLL_MS = 15_000L
        // sem azul: o azul fica reservado ao marcador "você está aqui"
        private val PALETTE = listOf(
            "#0B8A5F", "#5D4037", "#C62828", "#6A1B9A", "#EF6C00", "#00838F",
            "#AD1457", "#2E7D32", "#4527A0", "#D84315", "#00695C", "#827717",
        )

        fun colorFor(numero: String?): String =
            if (numero.isNullOrEmpty()) "#78909C" else PALETTE[abs(numero.hashCode()) % PALETTE.size]
    }

    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set

    var buses by mutableStateOf<List<Bus>>(emptyList()); private set
    var busesUpdatedAt by mutableStateOf(0L); private set
    var liveError by mutableStateOf<String?>(null); private set

    var myLocation by mutableStateOf<LatLon?>(null); private set

    var origin by mutableStateOf<Endpoint?>(null); private set
    var dest by mutableStateOf<Endpoint?>(null); private set
    /** null = sair agora */
    var startTime by mutableStateOf<ZonedDateTime?>(null); private set

    private val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    var planning by mutableStateOf(false); private set
    var planResults by mutableStateOf<List<PlanOption>>(emptyList()); private set

    var searchFor by mutableStateOf<Field?>(null); private set
    var searchLinesOnly by mutableStateOf(false); private set
    var searchQuery by mutableStateOf(""); private set
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList()); private set
    var searching by mutableStateOf(false); private set

    var pickOnMapFor by mutableStateOf<Field?>(null); private set

    private var pollJob: Job? = null
    private var searchJob: Job? = null
    private var planJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                withContext(Dispatchers.Default) { Repo.load(getApplication()) }
                android.util.Log.i("Candango", "dados carregados em ${System.currentTimeMillis() - t0} ms")
                loaded = true
            } catch (e: Exception) {
                loadError = "Falha ao carregar dados: ${e.message}"
            }
        }
    }

    // ---------- tempo real ----------

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    buses = LiveApi.fetchBuses()
                    busesUpdatedAt = System.currentTimeMillis()
                    liveError = null
                    if (screen != Screen.Home) replan(silent = true)
                } catch (e: Exception) {
                    liveError = "Sem conexão com o GPS da SEMOB"
                }
                delay(POLL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
    }

    fun onLocation(p: LatLon) {
        val first = myLocation == null
        myLocation = p
        if (first && origin == null && dest != null) replan()
    }

    // ---------- navegação ----------

    fun push(s: Screen) {
        stack += s
    }

    fun back() {
        when {
            searchFor != null || searchLinesOnly -> closeSearch()
            pickOnMapFor != null -> pickOnMapFor = null
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
        }
    }

    private fun replaceTop(s: Screen) {
        stack[stack.lastIndex] = s
    }

    fun openLine(line: Line) = push(Screen.LineView(line))
    fun openStop(stop: Stop) = push(Screen.StopView(stop))
    fun openRoute(o: PlanOption) = push(Screen.Route(o))

    fun setLineDir(i: Int) {
        val s = screen
        if (s is Screen.LineView) replaceTop(s.copy(dirIndex = i))
    }

    // ---------- origem / destino ----------

    val effectiveOrigin: Endpoint?
        get() = origin ?: myLocation?.let { Endpoint("Minha localização", it, isMyLocation = true) }

    fun setEndpoint(field: Field, e: Endpoint?) {
        if (field == Field.ORIGIN) origin = e?.takeUnless { it.isMyLocation } else dest = e
        closeSearch()
        pickOnMapFor = null
        replan()
    }

    fun swap() {
        val o = effectiveOrigin
        val d = dest
        origin = d
        dest = o
        replan()
    }

    fun setStart(t: ZonedDateTime?) {
        startTime = t
        replan()
    }

    fun startPick(field: Field) {
        closeSearch()
        pickOnMapFor = field
    }

    fun onMapPicked(p: LatLon) {
        val f = pickOnMapFor ?: Field.DEST
        setEndpoint(f, Endpoint("Ponto no mapa", p))
    }

    private fun replan(silent: Boolean = false) {
        val o = effectiveOrigin?.pos
        val d = dest?.pos
        if (o == null || d == null || !loaded) {
            planResults = emptyList()
            return
        }
        if (screen == Screen.Home) push(Screen.Plan)
        planJob?.cancel()
        planJob = viewModelScope.launch {
            if (!silent) planning = true
            val start = startTime ?: ZonedDateTime.now(Time.ZONE)
            val res = withContext(Dispatchers.Default) { Planner.plan(o, d, start, WINDOW_MIN, buses) }
            planResults = res
            planning = false
            // mantém a tela de rota aberta atualizada com o novo ETA
            val s = screen
            if (s is Screen.Route) {
                res.firstOrNull { it.key == s.option.key }?.let { replaceTop(Screen.Route(it)) }
            }
        }
    }

    fun clearTrip() {
        origin = null
        dest = null
        planResults = emptyList()
        stack.clear()
        stack += Screen.Home
    }

    // ---------- busca ----------

    fun openSearch(field: Field?) {
        searchFor = field
        searchLinesOnly = field == null
        searchQuery = ""
        searchHits = if (field == null) Repo.lines.take(40).map { SearchHit.LineHit(it) } else emptyList()
    }

    fun closeSearch() {
        searchFor = null
        searchLinesOnly = false
        searchJob?.cancel()
    }

    fun onSearchQuery(q: String) {
        searchQuery = q
        searchJob?.cancel()
        val query = q.trim()
        val local = localHits(query)
        searchHits = local
        if (searchLinesOnly || query.length < 3) return
        searchJob = viewModelScope.launch {
            delay(350)
            searching = true
            try {
                val places = LiveApi.searchPlaces(query).map { SearchHit.PlaceHit(it) }
                searchHits = places + local
            } catch (_: Exception) {
                // sem internet: fica só com linhas/paradas locais
            } finally {
                searching = false
            }
        }
    }

    private fun localHits(q: String): List<SearchHit> {
        if (q.isEmpty()) return if (searchLinesOnly) Repo.lines.take(40).map { SearchHit.LineHit(it) } else emptyList()
        val norm = q.lowercase()
        val lines = Repo.lines.filter { it.numero.startsWith(q) || it.descricao.lowercase().contains(norm) }
            .sortedBy { if (it.numero.startsWith(q)) 0 else 1 }
            .take(30).map { SearchHit.LineHit(it) }
        if (searchLinesOnly) return lines
        val stops = if (q.all { it.isDigit() }) Repo.stops.filter { it.cod == q }.map { SearchHit.StopHit(it) } else emptyList()
        return stops + lines.take(6)
    }

    fun onSearchHit(hit: SearchHit) {
        val field = searchFor
        when (hit) {
            is SearchHit.LineHit -> { closeSearch(); openLine(hit.line) }
            is SearchHit.StopHit ->
                if (field != null) setEndpoint(field, Endpoint(hit.stop.label, hit.stop.pos))
                else { closeSearch(); openStop(hit.stop) }
            is SearchHit.PlaceHit ->
                if (field != null) setEndpoint(field, Endpoint(hit.place.name, hit.place.pos))
                else closeSearch()
        }
    }

    // ---------- consultas para a UI ----------

    fun liveBusesOf(line: Line): List<Bus> = buses.filter { it.linha == line.numero }

    /** Painel da parada é sempre "a partir de agora", independente do horário escolhido no planejador. */
    fun stopDepartures(stop: Stop) =
        Planner.stopDepartures(stop, ZonedDateTime.now(Time.ZONE), WINDOW_MIN, buses)

    fun nearestStop(): Stop? = myLocation?.let { Repo.stopsNear(it, 800.0).firstOrNull()?.first }
}
