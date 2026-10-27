package io.github.mcardosor.candango.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mcardosor.candango.Notifier
import io.github.mcardosor.candango.TripService
import io.github.mcardosor.candango.data.Bus
import io.github.mcardosor.candango.data.Dir
import io.github.mcardosor.candango.data.Favorites
import io.github.mcardosor.candango.data.Geo
import io.github.mcardosor.candango.data.LatLon
import io.github.mcardosor.candango.data.Line
import io.github.mcardosor.candango.data.LiveApi
import io.github.mcardosor.candango.data.LiveEta
import io.github.mcardosor.candango.data.Place
import io.github.mcardosor.candango.data.PlanOption
import io.github.mcardosor.candango.data.Planner
import io.github.mcardosor.candango.data.Repo
import io.github.mcardosor.candango.data.RideLeg
import io.github.mcardosor.candango.data.SavedPlace
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
    /** viagem em andamento; o estado fica em [AppViewModel.trip] */
    data object Trip : Screen
}

/** O que está sendo escolhido na busca ou no mapa. HOME/WORK salvam Casa e Trabalho. */
enum class Field(val favKey: String? = null, val label: String = "") {
    ORIGIN, DEST,
    HOME(Favorites.HOME, "Casa"), WORK(Favorites.WORK, "Trabalho"),
}

data class Endpoint(val name: String, val pos: LatLon, val isMyLocation: Boolean = false, val isFavorite: Boolean = false)

/** Avisar quando um ônibus de [dir] estiver a [AppViewModel.ALERT_MIN] da parada. */
data class ArrivalAlert(val dir: Dir, val along: Int, val stop: Stop, val createdAt: Long = System.currentTimeMillis())

/** Último aviso disparado, mostrado no topo da tela. */
data class AppToast(val title: String, val body: String, val at: Long = System.currentTimeMillis())

/** Linha passando perto de você: a parada próxima onde ela passa primeiro. */
data class NearbyRow(val dir: Dir, val stop: Stop, val distM: Int, val atMin: Int, val live: LiveEta?)

enum class TripPhase { WALK, WAIT, RIDE, WALK_END, DONE }

data class TripState(
    val option: PlanOption,
    /** guardado no início: mudar o destino no meio da viagem não muda o fim desta */
    val dest: Endpoint?,
    val legIdx: Int = 0,
    val phase: TripPhase = TripPhase.WALK,
    val along: Int = 0,
    val boardedAt: Long = 0,
    val warned: Set<String> = emptySet(),
) {
    val leg: RideLeg get() = option.legs[legIdx]
}

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

        val ALL_COLORS = PALETTE + "#78909C"

        const val ALERT_MIN = 5
        private const val ALERT_MAX_MS = 2 * 3_600_000L // aviso esquecido não fica rodando para sempre
        const val TOAST_MS = 2 * 60_000L
        private const val NEARBY_RADIUS_M = 500.0
        private const val NEARBY_MAX_STOPS = 15
        private const val NEARBY_WINDOW_MIN = 90
        const val NEARBY_ROWS = 8
        // o GPS do celular erra 20–50 m na cidade
        private const val AT_STOP_M = 60.0
        private const val ON_ROUTE_M = 120.0
    }

    val favs = Favorites(app)

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

    val alerts = mutableStateListOf<ArrivalAlert>()
    var toast by mutableStateOf<AppToast?>(null)
    var trip by mutableStateOf<TripState?>(null); private set

    /** Com aviso ou viagem ativa, GPS e atualizações continuam com o app em segundo plano. */
    val backgroundWork: Boolean get() = alerts.isNotEmpty() || trip != null
    /** Chamado quando o último aviso/viagem acaba, para a tela desligar o GPS se o app estiver fechado. */
    var onBackgroundWorkEnded: (() -> Unit)? = null

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
                    checkAlerts()
                    updateTrip()
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
        updateTrip()
    }

    // ---------- navegação ----------

    fun push(s: Screen) {
        stack += s
    }

    fun back() {
        when {
            searchFor != null || searchLinesOnly -> closeSearch()
            pickOnMapFor != null -> pickOnMapFor = null
            stack.size > 1 -> {
                // sair da tela da viagem encerra a viagem
                if (stack.removeAt(stack.lastIndex) == Screen.Trip) endTrip()
            }
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

    /** Escolha feita na busca ou no mapa: partida, destino ou Casa/Trabalho. */
    fun choose(field: Field, e: Endpoint) {
        val key = field.favKey
        if (key != null) {
            favs.setPlace(key, SavedPlace(if (e.isMyLocation) field.label else e.name, e.pos.lat, e.pos.lon))
            closeSearch()
            pickOnMapFor = null
        } else setEndpoint(field, e)
    }

    fun setEndpoint(field: Field, e: Endpoint?) {
        if (e != null && !e.isMyLocation && !e.isFavorite) favs.addRecent(SavedPlace(e.name, e.pos.lat, e.pos.lon))
        if (field == Field.ORIGIN) origin = e?.takeUnless { it.isMyLocation } else dest = e
        closeSearch()
        pickOnMapFor = null
        replan()
    }

    fun goToFavorite(field: Field) {
        val p = favs.place(field.favKey ?: return)
        if (p == null) openSearch(field)
        else setEndpoint(Field.DEST, Endpoint(field.label, p.pos, isFavorite = true))
    }

    fun removeFavorite(field: Field) {
        favs.setPlace(field.favKey ?: return, null)
        closeSearch()
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
        choose(f, Endpoint("Ponto no mapa", p))
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
        endTrip()
    }

    // ---------- busca ----------

    fun openSearch(field: Field?) {
        searchFor = field
        searchLinesOnly = field == null
        searchQuery = ""
        searchHits = localHits("")
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
        if (q.isEmpty()) {
            if (!searchLinesOnly) return emptyList()
            // linhas favoritas primeiro
            val favLines = favs.lines.mapNotNull { Repo.lineByNumero[it] }
            return (favLines + Repo.lines.filter { !favs.hasLine(it.numero) }.take(40)).map { SearchHit.LineHit(it) }
        }
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
                if (field != null) choose(field, Endpoint(hit.stop.label, hit.stop.pos))
                else { closeSearch(); openStop(hit.stop) }
            is SearchHit.PlaceHit ->
                if (field != null) choose(field, Endpoint(hit.place.name, hit.place.pos))
                else closeSearch()
        }
    }

    // ---------- avisos de chegada ----------

    private fun warn(title: String, body: String) {
        toast = AppToast(title, body)
        Notifier.warn(getApplication(), title, body)
    }

    fun hasAlert(dir: Dir, stop: Stop) = alerts.any { it.dir === dir && it.stop === stop }

    fun toggleAlert(dir: Dir, along: Int, stop: Stop) {
        val i = alerts.indexOfFirst { it.dir === dir && it.stop === stop }
        if (i >= 0) alerts.removeAt(i) else alerts += ArrivalAlert(dir, along, stop)
        checkAlerts()
        syncBackground()
    }

    fun cancelAlert(a: ArrivalAlert) {
        alerts.remove(a)
        syncBackground()
    }

    private fun checkAlerts() {
        val now = System.currentTimeMillis()
        val done = alerts.filter { a ->
            if (now - a.createdAt > ALERT_MAX_MS) return@filter true
            val live = Planner.liveEta(a.dir, a.along, buses) ?: return@filter false
            if (live.minutes > ALERT_MIN) return@filter false
            warn("Linha ${a.dir.line.numero} chegando", "Está a ${minutesText(live.minutes)} da ${a.stop.label}")
            true
        }
        if (done.isNotEmpty()) alerts.removeAll(done)
        syncBackground()
    }

    private fun minutesText(m: Int) = if (m <= 1) "menos de 1 min" else "$m min"

    // ---------- viagem em andamento ----------

    fun startTrip(o: PlanOption) {
        trip = TripState(o, dest)
        push(Screen.Trip)
        updateTrip()
    }

    private fun endTrip() {
        if (trip == null) return
        trip = null
        stack.remove(Screen.Trip)
        syncBackground()
    }

    /** Botão manual de cada etapa ("Entrei no ônibus", "Desci"...), para quando o GPS não ajuda. */
    fun tripNext() {
        trip = trip?.let { advance(it) }
        updateTrip()
    }

    private fun advance(t: TripState): TripState {
        val legs = t.option.legs
        return when (t.phase) {
            TripPhase.WALK -> t.copy(phase = TripPhase.WAIT)
            TripPhase.WAIT -> t.copy(phase = TripPhase.RIDE, boardedAt = System.currentTimeMillis(), along = t.leg.boardAlong)
            TripPhase.RIDE ->
                if (t.legIdx < legs.lastIndex) {
                    val next = legs[t.legIdx + 1]
                    val same = Geo.dist(t.leg.alightStop.pos, next.boardStop.pos) < AT_STOP_M
                    t.copy(legIdx = t.legIdx + 1, phase = if (same) TripPhase.WAIT else TripPhase.WALK)
                } else t.copy(phase = if (t.dest != null) TripPhase.WALK_END else TripPhase.DONE)
            TripPhase.WALK_END -> t.copy(phase = TripPhase.DONE)
            TripPhase.DONE -> t
        }
    }

    /**
     * Onde a pessoa está no trajeto do ônibus. Sem GPS confiável, estima pelo tempo desde o embarque
     * (byGps = false): serve para mostrar, mas não para dar a pessoa como desembarcada.
     */
    fun rideAlong(t: TripState): Pair<Int, Boolean> {
        val leg = t.leg
        myLocation?.let { me ->
            val p = leg.dir.project(me)
            if (p.distM < ON_ROUTE_M && p.alongM >= leg.boardAlong - 200 && p.alongM <= leg.alightAlong + 400) {
                return maxOf(t.along, p.alongM) to true // ônibus não anda para trás
            }
        }
        // no trânsito o ônibus atrasa: a estimativa para antes da parada de descida
        val est = (leg.boardAlong + (System.currentTimeMillis() - t.boardedAt) / 60_000.0 * leg.dir.speedMPerMin).toInt()
            .coerceAtMost(leg.alightAlong - AT_STOP_M.toInt() - 1)
        return maxOf(t.along, est) to false
    }

    /** Paradas que ainda faltam até a de descida (inclusive). */
    fun stopsAhead(leg: RideLeg, along: Int): List<Stop> {
        val d = leg.dir
        return d.stopIdx.indices
            .filter { d.stopAlong[it] > along + 30 && d.stopAlong[it] <= leg.alightAlong + 1 }
            .map { Repo.stops[d.stopIdx[it]] }
    }

    private fun warnOnce(t: TripState, key: String, title: String, body: String): TripState {
        if (key in t.warned) return t
        warn(title, body)
        return t.copy(warned = t.warned + key)
    }

    /** Avança as etapas sozinho conforme a posição da pessoa e o GPS dos ônibus. */
    private fun updateTrip() {
        var t = trip ?: return
        val me = myLocation
        val num = t.leg.dir.line.numero
        if (t.phase == TripPhase.WALK && me != null && Geo.dist(me, t.leg.boardStop.pos) <= AT_STOP_M) t = advance(t)
        if (t.phase == TripPhase.WALK || t.phase == TripPhase.WAIT) {
            val leg = t.leg
            val p = me?.let { leg.dir.project(it) }
            // já está dentro do ônibus, andando pelo trajeto depois do embarque (mesmo sem o GPS ter "visto" a parada)
            if (p != null && p.distM < 60 && p.alongM > leg.boardAlong + 150 && p.alongM < leg.alightAlong) {
                t = advance(t.copy(phase = TripPhase.WAIT)).copy(along = p.alongM)
            } else {
                val live = Planner.liveEta(leg.dir, leg.boardAlong, buses)
                if (live != null && live.minutes <= 2) {
                    t = warnOnce(t, "chega${t.legIdx}", "Linha $num chegando", "Está a ${minutesText(live.minutes)} da ${leg.boardStop.label}")
                }
            }
        }
        if (t.phase == TripPhase.RIDE) {
            val (along, byGps) = rideAlong(t)
            t = t.copy(along = along)
            val ahead = stopsAhead(t.leg, along)
            if (byGps && ahead.size == 1) {
                t = warnOnce(t, "desce${t.legIdx}", "Desça na próxima parada", "${t.leg.alightStop.label} · linha ${t.leg.dir.line.numero}")
            }
            if (byGps && along >= t.leg.alightAlong - AT_STOP_M) t = advance(t)
        }
        val d = t.dest
        if (t.phase == TripPhase.WALK_END && me != null && d != null && Geo.dist(me, d.pos) <= AT_STOP_M) t = advance(t)
        trip = t
        syncBackground()
    }

    /** Texto curto da etapa atual (painel e notificação fixa). */
    fun tripHeadline(t: TripState): Pair<String, String> {
        val leg = t.leg
        return when (t.phase) {
            TripPhase.WALK -> "Vá até a ${leg.boardStop.label}" to "Pegue a linha ${leg.dir.line.numero}"
            TripPhase.WAIT -> "Pegue a linha ${leg.dir.line.numero}" to "${leg.dir.label} · ${leg.boardStop.label}"
            TripPhase.RIDE -> {
                val (along, byGps) = rideAlong(t)
                val n = stopsAhead(leg, along).size
                (if (n <= 1) "Desça na próxima!" else "${if (byGps) "" else "Cerca de "}$n paradas até descer") to "Desça na ${leg.alightStop.label}"
            }
            TripPhase.WALK_END -> "Caminhe até o destino" to (t.dest?.name ?: "")
            TripPhase.DONE -> "Você chegou! 🎉" to (t.dest?.name ?: "")
        }
    }

    /** Liga/desliga o serviço que mantém o app vivo com a tela apagada e atualiza a notificação fixa. */
    private fun syncBackground() {
        val ctx = getApplication<Application>()
        val t = trip
        when {
            t != null -> tripHeadline(t).let { (a, b) -> TripService.start(ctx, a, b) }
            alerts.isNotEmpty() -> {
                val a = alerts.first()
                val live = Planner.liveEta(a.dir, a.along, buses)
                TripService.start(ctx, "Aviso: linha ${a.dir.line.numero} na ${a.stop.label}",
                    live?.let { "Ônibus a ${it.minutes} min · aviso a $ALERT_MIN min" } ?: "Esperando um ônibus com GPS")
            }
            else -> {
                if (TripService.running) {
                    TripService.stop(ctx)
                    onBackgroundWorkEnded?.invoke()
                }
            }
        }
    }

    // ---------- "Perto de você" ----------

    private var nearbyKey: Any? = null
    private var nearbyCache: List<NearbyRow> = emptyList()

    /** Uma linha por sentido, na parada próxima onde ela passa primeiro, contando a caminhada até lá. */
    fun nearbyRows(): List<NearbyRow> {
        val me = myLocation ?: return emptyList()
        if (!loaded) return emptyList()
        val now = ZonedDateTime.now(Time.ZONE)
        val nowMin = now.hour * 60 + now.minute
        val stops = Repo.stopsNear(me, NEARBY_RADIUS_M).take(NEARBY_MAX_STOPS)
        val key = listOf(stops.map { it.first.idx }, busesUpdatedAt, nowMin, favs.lines)
        if (key == nearbyKey) return nearbyCache
        val best = HashMap<Dir, NearbyRow>()
        for ((stop, d) in stops) {
            val distM = Planner.walkM(d)
            val walk = Planner.walkMinutes(distM)
            for ((dir, passes, rawLive) in Planner.stopDepartures(stop, now, NEARBY_WINDOW_MIN, buses)) {
                // só vale se dá tempo de chegar à parada a pé
                val live = rawLive?.takeIf { it.minutes >= walk }
                val at = live?.let { nowMin + it.minutes } ?: passes.firstOrNull { it >= nowMin + walk } ?: continue
                val cur = best[dir]
                if (cur == null || at < cur.atMin) best[dir] = NearbyRow(dir, stop, distM, at, live)
            }
        }
        nearbyCache = best.values
            .sortedWith(compareBy<NearbyRow> { if (favs.hasLine(it.dir.line.numero)) 0 else 1 }.thenBy { it.atMin })
            .take(NEARBY_ROWS)
        nearbyKey = key
        return nearbyCache
    }

    // ---------- consultas para a UI ----------

    fun liveBusesOf(line: Line): List<Bus> = buses.filter { it.linha == line.numero }

    /** Painel da parada é sempre "a partir de agora", independente do horário escolhido no planejador. */
    fun stopDepartures(stop: Stop) =
        Planner.stopDepartures(stop, ZonedDateTime.now(Time.ZONE), WINDOW_MIN, buses)

    fun nearestStop(): Stop? = myLocation?.let { Repo.stopsNear(it, 800.0).firstOrNull()?.first }
}
