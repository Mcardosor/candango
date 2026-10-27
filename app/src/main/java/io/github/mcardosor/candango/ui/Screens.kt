package io.github.mcardosor.candango.ui

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mcardosor.candango.Notifier
import io.github.mcardosor.candango.data.Geo
import io.github.mcardosor.candango.data.LatLon
import io.github.mcardosor.candango.data.Line
import io.github.mcardosor.candango.data.LiveEta
import io.github.mcardosor.candango.data.PlanOption
import io.github.mcardosor.candango.data.Planner
import io.github.mcardosor.candango.data.Repo
import io.github.mcardosor.candango.data.Time
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

private val Green = Color(0xFF0B8A5F)
private val LiveGreen = Color(0xFF00A86B)
private val Muted = Color(0xFF6B7280)
private val MeBlue = Color(0xFF1E88E5) // mesmo azul do marcador "você" no mapa
private val Star = Color(0xFFF59E0B)

fun hex(c: String): Color = Color(android.graphics.Color.parseColor(c))

// =====================================================================================
// Raiz
// =====================================================================================

@Composable
fun AppRoot(vm: AppViewModel) {
    var controller by remember { mutableStateOf<MapController?>(null) }
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp
    var panelHeightPx by remember { mutableStateOf(0) }
    var panelSnap by remember { mutableStateOf(PanelSnap.MID) }
    // tela nova abre o painel na altura do meio, mesmo que a anterior estivesse recolhida ou aberta
    LaunchedEffect(vm.screen::class) { panelSnap = PanelSnap.MID }
    // enquadra o mapa na parte que o painel deixa livre, na altura em que ele estiver
    val fitBottomPx = with(density) { if (panelHeightPx > 0) panelHeightPx + 8.dp.roundToPx() else (screenH * 0.48f).dp.roundToPx() }
    val fitTopPx = with(density) { 200.dp.roundToPx() }

    BackHandler(enabled = vm.canGoBack || vm.searchFor != null || vm.searchLinesOnly || vm.pickOnMapFor != null) { vm.back() }

    // permissão de notificação (Android 13+), pedida na hora em que a pessoa liga um aviso ou inicia uma viagem
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(ctx)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // tela acesa durante a viagem
    val view = LocalView.current
    val tripActive = vm.trip != null
    DisposableEffect(tripActive) {
        view.keepScreenOn = tripActive
        onDispose { view.keepScreenOn = false }
    }

    CompositionLocalProvider(LocalAskNotifications provides askNotifications) {
    Box(Modifier.fillMaxSize()) {
        MapHost(vm) { controller = it }

        // Atualiza o conteúdo do mapa sempre que algo relevante muda
        val c = controller
        DisposableEffect(c) { onDispose { c?.dispose() } }
        LaunchedEffect(c, vm.screen, vm.buses, vm.origin, vm.dest, vm.myLocation, vm.loaded, vm.trip?.option) {
            if (c != null && vm.loaded) renderMap(c, vm)
        }
        // Enquadra o mapa só quando o "assunto" da tela muda
        LaunchedEffect(c, fitKey(vm), vm.loaded) {
            if (c != null && vm.loaded) {
                fitPoints(vm).takeIf { it.isNotEmpty() }?.let { c.fit(it, fitBottomPx, fitTopPx) }
            }
        }
        LaunchedEffect(c, vm.loaded) { if (c != null && vm.loaded) c.setAllStops(Repo.stops) }

        Column(Modifier.statusBarsPadding().padding(12.dp)) {
            when (vm.screen) {
                Screen.Home, Screen.Plan -> TripCard(vm)
                else -> TitleBar(vm)
            }
            vm.pickOnMapFor?.let { f ->
                Spacer(Modifier.height(8.dp))
                Banner(
                    when (f) {
                        Field.ORIGIN -> "Toque no mapa para escolher o ponto de partida"
                        Field.DEST -> "Toque no mapa para escolher o destino"
                        Field.HOME -> "Toque no mapa para marcar sua casa"
                        Field.WORK -> "Toque no mapa para marcar seu trabalho"
                    },
                ) { vm.back() }
            }
            ToastBanner(vm)
            vm.alerts.forEach { a ->
                Spacer(Modifier.height(8.dp))
                val live = Planner.liveEta(a.dir, a.along, vm.buses)
                Banner(
                    "🔔 Aviso: ${a.dir.line.numero} na ${a.stop.label} · " + (live?.let { "ônibus a ${it.minutes} min" } ?: "esperando um ônibus com GPS"),
                    color = Color(0xFF5B4300),
                ) { vm.cancelAlert(a) }
            }
            vm.liveError?.let {
                Spacer(Modifier.height(8.dp))
                Banner(it, onClose = null)
            }
        }

        BottomPanel(vm, panelSnap, { panelSnap = it }, Modifier.align(Alignment.BottomCenter).onSizeChanged { panelHeightPx = it.height })

        // botão "voltar para mim", logo acima do painel (some com o painel aberto na tela toda)
        val me = vm.myLocation
        if (c != null && me != null && vm.pickOnMapFor == null && panelSnap != PanelSnap.FULL) {
            Surface(
                onClick = { c.center(me, 16.0, fitBottomPx, fitTopPx) },
                shape = CircleShape, color = Color.White, shadowElevation = 6.dp,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = with(density) { panelHeightPx.toDp() } + 12.dp)
                    .size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MyLocation, "Centralizar na minha localização", tint = MeBlue)
                }
            }
        }

        if (vm.searchFor != null || vm.searchLinesOnly) SearchOverlay(vm)

        if (!vm.loaded) {
            Box(Modifier.fillMaxSize().background(Color(0x88FFFFFF)), contentAlignment = Alignment.Center) {
                vm.loadError?.let { Text(it, color = Color.Red) } ?: CircularProgressIndicator(color = Green)
            }
        }
    }
    }
}

/** Pede a permissão de notificação; chamado antes de ligar um aviso ou iniciar uma viagem. */
private val LocalAskNotifications = staticCompositionLocalOf<() -> Unit> { {} }

/** Último aviso disparado ("ônibus chegando", "desça na próxima"), por até 2 min. */
@Composable
private fun ToastBanner(vm: AppViewModel) {
    val t = vm.toast ?: return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(t) { while (true) { delay(5_000); now = System.currentTimeMillis() } }
    if (now - t.at > AppViewModel.TOAST_MS) return
    Spacer(Modifier.height(8.dp))
    Banner("${t.title}\n${t.body}", color = Green) { vm.toast = null }
}

// =====================================================================================
// Mapa
// =====================================================================================

@Composable
private fun MapHost(vm: AppViewModel, onReady: (MapController) -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(ctx).apply { onCreate(null) } }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            mapView.getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder().target(MapController.BRASILIA).zoom(11.0).build()
                map.setStyle(Style.Builder().fromUri(MapController.STYLE_URL)) { style ->
                    val c = MapController(map, style)
                    map.addOnMapClickListener { ll ->
                        if (vm.pickOnMapFor != null) {
                            vm.onMapPicked(LatLon(ll.latitude, ll.longitude))
                            return@addOnMapClickListener true
                        }
                        val (stopIdx, linha) = c.hitTest(ll)
                        when {
                            linha != null -> Repo.lineByNumero[linha]?.let { vm.openLine(it) }
                            stopIdx != null -> vm.openStop(Repo.stops[stopIdx])
                        }
                        linha != null || stopIdx != null
                    }
                    map.addOnMapLongClickListener { ll ->
                        vm.setEndpoint(Field.DEST, Endpoint("Ponto no mapa", LatLon(ll.latitude, ll.longitude)))
                        true
                    }
                    onReady(c)
                }
            }
            mapView
        },
    )
}

private fun fitKey(vm: AppViewModel): Any = when (val s = vm.screen) {
    is Screen.Route -> "rota" to s.option.key
    Screen.Trip -> vm.trip.let { Triple("viagem", it?.legIdx, it?.phase) }
    is Screen.LineView -> s.line to s.dirIndex
    is Screen.StopView -> s.stop
    else -> Triple("plano", vm.effectiveOrigin?.pos, vm.dest?.pos)
}

private fun fitPoints(vm: AppViewModel): List<LatLon> = when (val s = vm.screen) {
    is Screen.Route -> listOfNotNull(vm.effectiveOrigin?.pos, vm.dest?.pos) +
        s.option.legs.flatMap { it.dir.shapeBetween(it.boardAlong, it.alightAlong) }
    // na viagem: a pessoa e o próximo ponto que importa
    Screen.Trip -> vm.trip?.let { t ->
        val target = when (t.phase) {
            TripPhase.RIDE -> t.leg.alightStop.pos
            TripPhase.WALK_END, TripPhase.DONE -> t.dest?.pos
            else -> t.leg.boardStop.pos
        }
        listOfNotNull(vm.myLocation, target)
    } ?: emptyList()
    is Screen.LineView -> s.line.dirs.getOrNull(s.dirIndex)?.shape ?: emptyList()
    is Screen.StopView -> listOfNotNull(s.stop.pos, walkableMe(vm, s.stop.pos))
    else -> listOfNotNull(vm.effectiveOrigin?.pos, vm.dest?.pos)
}

/** Minha localização, se estiver perto o bastante de [p] para ir a pé. */
private fun walkableMe(vm: AppViewModel, p: LatLon): LatLon? =
    vm.myLocation?.takeIf { Geo.dist(it, p) <= WALKABLE_M }

private const val WALKABLE_M = 3000.0

private fun renderMap(c: MapController, vm: AppViewModel) {
    c.setMe(vm.myLocation)
    val markers = buildList {
        vm.effectiveOrigin?.takeIf { !it.isMyLocation }?.let { add(MapMarker(it.pos, "origem", "Partida")) }
        vm.dest?.let { add(MapMarker(it.pos, "destino", "Destino")) }
    }
    fun allBuses() = vm.buses.map { MapBus(it, AppViewModel.colorFor(it.linha), false) }

    val s = vm.screen
    val routeOption = (s as? Screen.Route)?.option ?: vm.trip?.takeIf { s == Screen.Trip }?.option
    when {
        routeOption != null -> {
            val o = routeOption
            val walk = "#455A64"
            val lines = ArrayList<MapLine>()
            vm.effectiveOrigin?.let { lines += MapLine(listOf(it.pos, o.first.boardStop.pos), walk, dashed = true) }
            o.legs.forEachIndexed { i, leg ->
                if (i > 0) lines += MapLine(listOf(o.legs[i - 1].alightStop.pos, leg.boardStop.pos), walk, dashed = true)
                lines += MapLine(leg.dir.shapeBetween(leg.boardAlong, leg.alightAlong), AppViewModel.colorFor(leg.dir.line.numero))
            }
            vm.dest?.let { lines += MapLine(listOf(o.last.alightStop.pos, it.pos), walk, dashed = true) }
            c.setLines(lines)
            c.setColoredStops(o.legs.flatMap { leg ->
                val color = AppViewModel.colorFor(leg.dir.line.numero)
                listOf(leg.boardStop to color, leg.alightStop to color)
            })
            c.setBuses(o.legs.flatMap { leg ->
                val color = AppViewModel.colorFor(leg.dir.line.numero)
                vm.liveBusesOf(leg.dir.line).map { MapBus(it, color, true) }
            })
            c.setMarkers(markers)
        }
        s is Screen.LineView -> {
            val dir = s.line.dirs.getOrNull(s.dirIndex)
            val color = AppViewModel.colorFor(s.line.numero)
            c.setLines(listOfNotNull(dir?.let { MapLine(it.shape, color) }))
            c.setStops(dir?.stopIdx?.map { Repo.stops[it] } ?: emptyList(), color)
            c.setBuses(vm.liveBusesOf(s.line).map { MapBus(it, color, true) })
            c.setMarkers(markers)
        }
        s is Screen.StopView -> {
            c.setLines(listOfNotNull(walkableMe(vm, s.stop.pos)?.let { MapLine(listOf(it, s.stop.pos), "#1E88E5", dashed = true) }))
            c.setStops(listOf(s.stop), "#0B8A5F")
            c.setBuses(allBuses())
            c.setMarkers(markers)
        }
        else -> {
            c.setLines(emptyList())
            c.setStops(emptyList(), "#0B8A5F")
            c.setBuses(allBuses())
            c.setMarkers(markers)
        }
    }
}

// =====================================================================================
// Topo
// =====================================================================================

@Composable
private fun TripCard(vm: AppViewModel) {
    val ctx = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, color = Color.White) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    EndpointRow(
                        icon = Icons.Default.TripOrigin, tint = Color(0xFF2E7D32),
                        text = vm.effectiveOrigin?.name ?: "Escolha a partida",
                        placeholder = vm.effectiveOrigin == null,
                    ) { vm.openSearch(Field.ORIGIN) }
                    HorizontalDivider(Modifier.padding(start = 36.dp), color = Color(0xFFE5E7EB))
                    EndpointRow(
                        icon = Icons.Default.Place, tint = Color(0xFFC62828),
                        text = vm.dest?.name ?: "Para onde?",
                        placeholder = vm.dest == null,
                    ) { vm.openSearch(Field.DEST) }
                }
                IconButton(onClick = { vm.swap() }) { Icon(Icons.Default.SwapVert, "Inverter") }
            }
            if (vm.dest != null) {
                val now = ZonedDateTime.now(Time.ZONE)
                val sel = vm.startTime
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(selected = sel == null, onClick = { vm.setStart(null) }, label = { Text("Agora") })
                    for (h in 1..3) {
                        val isSel = sel != null && sel.minute == now.minute && java.time.Duration.between(now, sel).toMinutes().let { it in (h * 60 - 2)..(h * 60 + 2) }
                        FilterChip(selected = isSel, onClick = { vm.setStart(now.plusHours(h.toLong())) }, label = { Text("+${h}h") })
                    }
                    FilterChip(
                        selected = false,
                        onClick = {
                            val base = sel ?: now
                            TimePickerDialog(ctx, { _, hh, mm ->
                                var t = now.withHour(hh).withMinute(mm)
                                if (t.isBefore(now.minusMinutes(30))) t = t.plusDays(1)
                                vm.setStart(t)
                            }, base.hour, base.minute, true).show()
                        },
                        label = { Text(sel?.let { "Saída %02d:%02d".format(it.hour, it.minute) } ?: "Horário…") },
                    )
                    FilterChip(selected = false, onClick = { vm.clearTrip() }, label = { Text("Limpar") })
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(icon: ImageVector, tint: Color, text: String, placeholder: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (placeholder) Muted else Color(0xFF111827),
            fontWeight = if (placeholder) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

@Composable
private fun TitleBar(vm: AppViewModel) {
    val isTrip = vm.screen == Screen.Trip
    val title = when (val s = vm.screen) {
        is Screen.Route -> s.option.legs.joinToString(" + ") { it.dir.line.numero }.let { if (s.option.isDirect) "Linha $it" else "Linhas $it" }
        Screen.Trip -> "Viagem · " + (vm.trip?.option?.legs?.joinToString(" + ") { it.dir.line.numero } ?: "")
        is Screen.LineView -> "Linha ${s.line.numero}"
        is Screen.StopView -> s.stop.label
        else -> ""
    }
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, color = Color.White) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) {
                if (isTrip) Icon(Icons.Default.Close, "Encerrar viagem") else Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Banner(text: String, color: Color = Color(0xFF263238), onClose: (() -> Unit)?) {
    Surface(shape = RoundedCornerShape(12.dp), color = color) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color.White, modifier = Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 14.sp)
            if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cancelar", tint = Color.White) }
        }
    }
}

// =====================================================================================
// Painel inferior
// =====================================================================================

/** Alturas do painel: só o começo (para ver mais mapa), a do meio (padrão) e quase a tela toda. */
enum class PanelSnap { PEEK, MID, FULL }

@Composable
private fun BottomPanel(vm: AppViewModel, snap: PanelSnap, onSnap: (PanelSnap) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    fun heightOf(s: PanelSnap) = when (s) {
        PanelSnap.PEEK -> 120.dp
        PanelSnap.MID -> screenH * 0.46f
        PanelSnap.FULL -> screenH * 0.88f
    }
    var shownPx by remember { mutableStateOf(0) }
    var dragPx by remember { mutableStateOf<Float?>(null) } // altura enquanto o dedo arrasta
    val animated by animateDpAsState(heightOf(snap), label = "painel")
    val maxH = dragPx?.let { with(density) { it.toDp() } } ?: animated
    val dragState = rememberDraggableState { delta ->
        val minPx = with(density) { 80.dp.toPx() }
        val maxPx = with(density) { heightOf(PanelSnap.FULL).toPx() }
        dragPx = ((dragPx ?: shownPx.toFloat()) - delta).coerceIn(minPx, maxPx)
    }
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(max = maxH).onSizeChanged { shownPx = it.height },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp, color = Color.White,
    ) {
        Column(Modifier.navigationBarsPadding()) {
            // faixa de arrastar: bem mais alta que o tracinho, para o dedo acertar
            Box(
                Modifier.fillMaxWidth()
                    .draggable(
                        dragState, Orientation.Vertical,
                        onDragStarted = { dragPx = shownPx.toFloat() },
                        onDragStopped = { velocity ->
                            // numa jogada rápida o painel segue um pouco além de onde o dedo soltou
                            val target = (dragPx ?: shownPx.toFloat()) - velocity * 0.25f
                            dragPx = null
                            onSnap(PanelSnap.entries.minBy { abs(with(density) { heightOf(it).toPx() } - target) })
                        },
                    )
                    .clickable(onClickLabel = "Aumentar ou diminuir o painel") {
                        onSnap(if (snap == PanelSnap.MID) PanelSnap.FULL else PanelSnap.MID)
                    }
                    .padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color(0xFFD1D5DB)))
            }
            when (val s = vm.screen) {
                Screen.Home -> HomeContent(vm)
                Screen.Plan -> PlanContent(vm)
                is Screen.Route -> RouteContent(vm, s.option)
                is Screen.LineView -> LineContent(vm, s.line, s.dirIndex)
                is Screen.StopView -> StopContent(vm, s.stop)
                Screen.Trip -> vm.trip?.let { TripContent(vm, it) }
            }
        }
    }
}

@Composable
private fun LiveStatus(vm: AppViewModel) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val ago = if (vm.busesUpdatedAt == 0L) null else ((now - vm.busesUpdatedAt) / 1000).toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Sensors, null, tint = LiveGreen, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (ago == null) "Conectando ao GPS dos ônibus…"
            else "${"%,d".format(Locale("pt", "BR"), vm.buses.size)} ônibus ao vivo · atualizado há ${ago}s",
            fontSize = 13.sp, color = Muted,
        )
    }
}

@Composable
private fun HomeContent(vm: AppViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        LiveStatus(vm)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FavPlaceCard(vm, Field.HOME, Icons.Default.Home, Modifier.weight(1f))
            FavPlaceCard(vm, Field.WORK, Icons.Default.Work, Modifier.weight(1f))
        }
        val favLines = vm.favs.lines.filter { Repo.lineByNumero.containsKey(it) }
        if (favLines.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                favLines.forEach { n -> Box(Modifier.clickable { Repo.lineByNumero[n]?.let { vm.openLine(it) } }) { LineBadge(n) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.openSearch(null) }) {
                Icon(Icons.Default.DirectionsBus, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Linhas")
            }
            OutlinedButton(onClick = { vm.nearestStop()?.let { vm.openStop(it) } }, enabled = vm.myLocation != null) {
                Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Parada mais próxima")
            }
        }
        NearbyList(vm)
        if (vm.myLocation == null) {
            Text("Dica: toque e segure no mapa para marcar o destino.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** Atalho para Casa ou Trabalho: um toque planeja a rota; vazio, abre a busca para definir. */
@Composable
private fun FavPlaceCard(vm: AppViewModel, field: Field, icon: ImageVector, modifier: Modifier) {
    val p = vm.favs.place(field.favKey!!)
    Row(
        modifier.clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(12.dp))
            .clickable { vm.goToFavorite(field) }
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(field.label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(p?.name ?: "Toque para definir", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (p != null) {
            IconButton(onClick = { vm.openSearch(field) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, "Mudar ${field.label}", tint = Muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** "Perto de você": próximas linhas nas paradas ao redor, já contando a caminhada. */
@Composable
private fun NearbyList(vm: AppViewModel) {
    if (vm.myLocation == null || !vm.loaded) return
    val rows = vm.nearbyRows()
    val nowMin = ZonedDateTime.now(Time.ZONE).let { it.hour * 60 + it.minute }
    Text("Perto de você", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    if (rows.isEmpty()) {
        Text("Nenhuma linha passando a até 500 m nos próximos 90 minutos.", fontSize = 13.sp, color = Muted)
        return
    }
    rows.forEach { r ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.openStop(r.stop) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineBadge(r.dir.line.numero)
            if (vm.favs.hasLine(r.dir.line.numero)) Icon(Icons.Default.Star, null, tint = Star, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.dir.line.descricao, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.stop.label} · ${r.distM} m · ${r.dir.label}", fontSize = 12.sp, color = Muted, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                val live = r.live
                if (live != null) LiveMinutes(live)
                else {
                    Text(Time.wait(r.atMin - nowMin), fontWeight = FontWeight.Bold)
                    Text(Time.fmt(r.atMin), fontSize = 12.sp, color = Muted)
                }
            }
        }
        HorizontalDivider(color = Color(0xFFF1F5F9))
    }
}

@Composable
private fun PlanContent(vm: AppViewModel) {
    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                vm.startTime?.let { "Saindo às %02d:%02d · próximas 3h".format(it.hour, it.minute) } ?: "Saindo agora · próximas 3h",
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            if (vm.planning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Green)
        }
        when {
            vm.effectiveOrigin == null -> Hint("Escolha o ponto de partida ou permita o acesso à localização.")
            !vm.planning && vm.planResults.isEmpty() -> Hint(
                "Nenhuma opção encontrada entre esses pontos nesse horário, nem com uma troca de ônibus. " +
                    "Tente outro horário ou um ponto mais perto de uma via com ônibus.",
            )
            else -> LazyColumn {
                items(vm.planResults, key = { it.key }) { o ->
                    OptionCard(o) { vm.openRoute(o) }
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Muted, modifier = Modifier.padding(16.dp), fontSize = 14.sp)
}

@Composable
private fun LineBadge(numero: String, big: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(hex(AppViewModel.colorFor(numero)))
            .padding(horizontal = 8.dp, vertical = if (big) 5.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.DirectionsBus, null, tint = Color.White, modifier = Modifier.size(if (big) 18.dp else 14.dp))
        Spacer(Modifier.width(4.dp))
        Text(numero, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 16.sp else 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionCard(o: PlanOption, onClick: () -> Unit) {
    val nowMin = ZonedDateTime.now(Time.ZONE).let { it.hour * 60 + it.minute }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(Modifier.weight(1f)) {
            // a sequência quebra de linha quando há baldeação com caminhada e não cabe
            FlowRow(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WalkMinutes(o.walkToMin)
                o.legs.forEachIndexed { i, leg ->
                    if (i > 0 && o.transferWalkMin > 0) { Chevron(); WalkMinutes(o.transferWalkMin) }
                    Chevron()
                    LineBadge(leg.dir.line.numero)
                }
                Chevron()
                WalkMinutes(o.walkFromMin)
            }
            val desc = if (o.isDirect) o.first.dir.line.descricao else "1 troca · na ${o.legs[1].boardStop.label}"
            Text(desc, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(
                "Embarque na ${o.first.boardStop.label} · ${o.legs.sumOf { it.rideMin }} min de ônibus",
                fontSize = 12.sp, color = Muted,
            )
            val upcoming = o.boardTimes.take(6)
            if (upcoming.isNotEmpty()) {
                Text(upcoming.joinToString("  ") { Time.fmt(it) }, fontSize = 12.sp, color = Color(0xFF374151), modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 64.dp)) {
            val live = o.live
            if (live != null) {
                LiveMinutes(live)
            } else o.trips.firstOrNull()?.let {
                Text(Time.fmt(it.boards.first()), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("em ${Time.wait(it.boards.first() - nowMin)}", fontSize = 12.sp, color = Muted)
            }
            o.trips.firstOrNull()?.let { Text("chega ${Time.fmt(it.arrival)}", fontSize = 12.sp, color = Muted) }
        }
    }
}

@Composable
private fun WalkMinutes(min: Int) {
    Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = Muted, modifier = Modifier.size(18.dp))
        Text("$min", fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun Chevron() {
    Box(Modifier.height(26.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text("›", color = Muted)
    }
}

@Composable
private fun LiveMinutes(live: LiveEta) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Sensors, null, tint = LiveGreen, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(2.dp))
        Text(if (live.minutes <= 1) "Agora" else "${live.minutes} min", color = LiveGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
    Text("ao vivo", fontSize = 12.sp, color = LiveGreen)
}

@Composable
private fun RouteContent(vm: AppViewModel, o: PlanOption) {
    val brl = Locale("pt", "BR")
    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                o.legs.forEachIndexed { i, leg ->
                    if (i > 0) Text("  ›  ", color = Muted)
                    LineBadge(leg.dir.line.numero, big = true)
                }
            }
            Row(Modifier.padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    o.trips.firstOrNull()?.let {
                        Text("Chega às ${Time.fmt(it.arrival)}", fontWeight = FontWeight.SemiBold)
                    }
                    val fare = o.legs.mapNotNull { it.dir.line.tarifa }.takeIf { it.size == o.legs.size }?.sum()
                    Text(
                        listOfNotNull(
                            if (o.isDirect) o.first.dir.label else "1 troca",
                            fare?.let { "R$ %.2f".format(brl, it) + if (o.isDirect) "" else " (sem integração)" },
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = Muted,
                    )
                }
                o.live?.let { Column(horizontalAlignment = Alignment.End) { LiveMinutes(it) } }
            }
            val ask = LocalAskNotifications.current
            val alertOn = vm.hasAlert(o.first.dir, o.first.boardStop)
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ask(); vm.startTrip(o) }, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                    Icon(Icons.Default.Navigation, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Iniciar viagem")
                }
                OutlinedButton(onClick = {
                    if (!alertOn) ask()
                    vm.toggleAlert(o.first.dir, o.first.boardAlong, o.first.boardStop)
                }) {
                    Icon(
                        if (alertOn) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, null,
                        tint = if (alertOn) Star else Color(0xFF374151), modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (alertOn) "Aviso ligado" else "Avisar a ${AppViewModel.ALERT_MIN} min")
                }
            }
        }
        item { Step(Icons.AutoMirrored.Filled.DirectionsWalk, "Caminhe até a ${o.first.boardStop.label}", "${o.walkToM} m · ${o.walkToMin} min") }
        o.legs.forEachIndexed { i, leg ->
            if (i > 0) item {
                Step(
                    Icons.AutoMirrored.Filled.DirectionsWalk,
                    if (o.transferWalkM < 30) "Troque de ônibus na mesma parada" else "Caminhe até a ${leg.boardStop.label}",
                    if (o.transferWalkM < 30) "" else "${o.transferWalkM} m · ${o.transferWalkMin} min",
                )
            }
            item {
                Step(
                    Icons.Default.DirectionsBus, "Pegue a linha ${leg.dir.line.numero} · ${leg.dir.label}",
                    leg.dir.line.descricao + "\n${leg.rideMin} min · ${leg.distanceM / 100 / 10.0} km" + if (leg.dir.durEstimada) " (estimado)" else "",
                    tint = hex(AppViewModel.colorFor(leg.dir.line.numero)),
                )
            }
            item { Step(Icons.Default.Place, "Desça na ${leg.alightStop.label}", "") }
        }
        item { Step(Icons.AutoMirrored.Filled.DirectionsWalk, "Caminhe até o destino", "${o.walkFromM} m · ${o.walkFromMin} min") }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val live = o.live
            Text(
                if (live != null) "Ônibus ${live.bus.id} da ${o.first.dir.line.numero} está a ${"%.1f".format(brl, live.distanceM / 1000.0)} km do seu ponto"
                else "Nenhum ônibus da ${o.first.dir.line.numero} com GPS ativo vindo para o seu ponto agora",
                fontSize = 13.sp, color = if (live != null) LiveGreen else Muted,
            )
            if (o.isDirect) {
                Text("Passagens previstas no ponto de embarque", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                TimesGrid(o.boardTimes)
            } else {
                Text("Próximas viagens", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                TripsTable(o)
            }
            Text(
                "Horários calculados a partir da tabela da SEMOB e da distância do terminal até o ponto.",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

/** Viagem em andamento: a etapa atual em destaque e o botão manual da etapa. */
@Composable
private fun TripContent(vm: AppViewModel, t: TripState) {
    val leg = t.leg
    val num = leg.dir.line.numero
    val me = vm.myLocation
    val nowMin = ZonedDateTime.now(Time.ZONE).let { it.hour * 60 + it.minute }
    val (big, sub) = vm.tripHeadline(t)
    val urgent = t.phase == TripPhase.RIDE && big.startsWith("Desça")

    @Composable
    fun walkInfo(p: LatLon?) {
        if (p == null) return
        val text = if (me == null) "Sem sua localização" else Planner.walkM(Geo.dist(me, p)).let { m ->
            "Faltam ${if (m < 1000) "$m m" else "%.1f km".format(Locale("pt", "BR"), m / 1000.0)} · ${Planner.walkMinutes(m).coerceAtLeast(1)} min a pé"
        }
        Text(text, fontSize = 13.sp, color = MeBlue, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    }

    // quando o ônibus chega ao ponto: ao vivo ou pela tabela
    @Composable
    fun busInfo() {
        val live = Planner.liveEta(leg.dir, leg.boardAlong, vm.buses)
        if (live != null) {
            Text(
                "Ônibus ao vivo a ${if (live.minutes <= 1) "menos de 1 min" else "${live.minutes} min"} do ponto",
                color = LiveGreen, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            val next = vm.stopDepartures(leg.boardStop).firstOrNull { it.first === leg.dir }?.second?.firstOrNull()
            Text(
                "Nenhum ônibus da $num com GPS vindo agora" +
                    (next?.let { " · previsto às ${Time.fmt(it)} (${if (it - nowMin <= 0) "agora" else Time.wait(it - nowMin)})" } ?: ""),
                color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    // o painel pisca em vermelho na hora de descer
    val pulse = rememberInfiniteTransition(label = "desca")
    val urgentBg by pulse.animateColor(Color(0xFFFDE8E8), Color(0xFFFBD0D0), infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "fundo")

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            t.option.legs.forEachIndexed { i, l ->
                if (i > 0) Text("  ›  ", color = Muted)
                Box(Modifier.alpha(if (i < t.legIdx) 0.35f else 1f)) { LineBadge(l.dir.line.numero) }
            }
        }
        Column(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (urgent) urgentBg else Color(0xFFEEF6F2)).padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(big, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = if (urgent) Color(0xFFB91C1C) else Color(0xFF111827))
            Text(sub, color = Muted, fontSize = 14.sp)
        }
        when (t.phase) {
            TripPhase.WALK -> { walkInfo(leg.boardStop.pos); busInfo() }
            TripPhase.WAIT -> busInfo()
            TripPhase.RIDE -> {
                val (along, byGps) = vm.rideAlong(t)
                val ahead = vm.stopsAhead(leg, along)
                if (ahead.size > 1) Text("Próxima parada: ${ahead.first().label}", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                if (!byGps) Text(
                    "Sem o seu GPS no trajeto: contagem estimada pelo tempo. Confira as paradas pela janela.",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
            TripPhase.WALK_END -> walkInfo(t.dest?.pos)
            TripPhase.DONE -> {}
        }
        Spacer(Modifier.height(12.dp))
        if (t.phase == TripPhase.DONE) {
            Button(onClick = { vm.back() }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Encerrar viagem") }
        } else {
            Button(onClick = { vm.tripNext() }, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Text(
                    when (t.phase) {
                        TripPhase.WALK -> "Cheguei na parada"
                        TripPhase.WAIT -> "Entrei no ônibus"
                        TripPhase.RIDE -> "Desci do ônibus"
                        else -> "Cheguei"
                    },
                )
            }
            Text(
                "O app avança sozinho pelo GPS e avisa quando o ônibus estiver chegando e quando for a hora de descer, " +
                    "mesmo com a tela apagada.",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Viagens com baldeação: horário de cada ônibus e chegada. */
@Composable
private fun TripsTable(o: PlanOption) {
    if (o.trips.isEmpty()) {
        Text("Sem viagens nesse período.", fontSize = 13.sp, color = Muted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        o.trips.take(12).forEach { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFF6F8F7)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                t.boards.forEachIndexed { i, b ->
                    if (i > 0) Text("  ›  ", color = Muted)
                    Text(o.legs[i].dir.line.numero, fontSize = 12.sp, color = hex(AppViewModel.colorFor(o.legs[i].dir.line.numero)), fontWeight = FontWeight.Bold)
                    Text(" " + Time.fmt(b), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                if (t.boards.size > 1) {
                    val wait = t.boards[1] - (t.boards[0] + o.first.rideMin + o.transferWalkMin)
                    Text("espera ${wait} min  ", fontSize = 11.sp, color = Muted)
                }
                Text("chega ${Time.fmt(t.arrival)}", fontSize = 13.sp, color = Color(0xFF065F46), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, sub: String, tint: Color = Muted) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable
private fun TimesGrid(times: List<Int>, highlightFrom: Int? = null) {
    if (times.isEmpty()) {
        Text("Sem horários nesse período.", fontSize = 13.sp, color = Muted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        times.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    val past = highlightFrom != null && t < highlightFrom
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (past) Color(0xFFF3F4F6) else Color(0xFFE8F5EF))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(Time.fmt(t), fontSize = 13.sp, color = if (past) Muted else Color(0xFF065F46), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineContent(vm: AppViewModel, line: Line, dirIndex: Int) {
    val dir = line.dirs.getOrNull(dirIndex) ?: return
    val live = vm.liveBusesOf(line)
    val today = ZonedDateTime.now(Time.ZONE)
    val nowMin = today.hour * 60 + today.minute
    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LineBadge(line.numero, big = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(line.descricao, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    line.tarifa?.let { Text("Tarifa R$ %.2f".format(Locale("pt", "BR"), it), fontSize = 12.sp, color = Muted) }
                }
                val fav = vm.favs.hasLine(line.numero)
                IconButton(onClick = { vm.favs.toggleLine(line.numero) }) {
                    Icon(
                        if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                        if (fav) "Tirar dos favoritos" else "Favoritar linha", tint = Star,
                    )
                }
            }
            if (line.dirs.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.dirs.forEachIndexed { i, d ->
                        FilterChip(selected = i == dirIndex, onClick = { vm.setLineDir(i) }, label = { Text(d.label) })
                    }
                }
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sensors, null, tint = if (live.isNotEmpty()) LiveGreen else Muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (live.isEmpty()) "Nenhum ônibus desta linha com GPS ativo agora"
                    else "${live.size} ônibus desta linha no mapa agora",
                    fontSize = 13.sp, color = if (live.isNotEmpty()) LiveGreen else Muted,
                )
            }
            Text(
                "${dir.stopIdx.size} paradas · ${dir.lengthM / 100 / 10.0} km · ~${dir.durMin} min de viagem",
                fontSize = 12.sp, color = Muted,
            )
            Text("Partidas do terminal hoje", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            TimesGrid(dir.departures(today.toLocalDate()), highlightFrom = nowMin)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StopContent(vm: AppViewModel, stop: io.github.mcardosor.candango.data.Stop) {
    val rows = vm.stopDepartures(stop)
    val nowMin = ZonedDateTime.now(Time.ZONE).let { it.hour * 60 + it.minute }
    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stop.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${Repo.dirsAtStop[stop.idx].map { it.first.line }.distinct().size} linhas passam aqui · próximas 3h", fontSize = 12.sp, color = Muted)
                walkableMe(vm, stop.pos)?.let { me ->
                    val m = Planner.walkM(Geo.dist(me, stop.pos))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = MeBlue, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${if (m < 1000) "$m m" else "%.1f km".format(Locale("pt", "BR"), m / 1000.0)} · " +
                                "${Planner.walkMinutes(m).coerceAtLeast(1)} min a pé de você",
                            fontSize = 12.sp, color = MeBlue, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            OutlinedButton(onClick = { vm.setEndpoint(Field.DEST, Endpoint(stop.label, stop.pos)) }) { Text("Ir para cá") }
        }
        if (rows.isEmpty()) Hint("Nenhuma passagem prevista nas próximas 3 horas.")
        val ask = LocalAskNotifications.current
        LazyColumn {
            items(rows, key = { it.first.line.numero + it.first.sentido }) { (dir, passes, live) ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openLine(dir.line) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineBadge(dir.line.numero)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dir.line.descricao, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            dir.label + " · " + passes.take(4).joinToString("  ") { Time.fmt(it) },
                            fontSize = 12.sp, color = Muted, maxLines = 1,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (live != null) LiveMinutes(live)
                        else passes.firstOrNull()?.let { Text(Time.wait(it - nowMin), fontWeight = FontWeight.Bold) }
                    }
                    val on = vm.hasAlert(dir, stop)
                    IconButton(onClick = {
                        if (!on) ask()
                        val along = Repo.dirsAtStop[stop.idx].first { it.first === dir }.second
                        vm.toggleAlert(dir, along, stop)
                    }) {
                        Icon(
                            if (on) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                            if (on) "Cancelar aviso" else "Avisar quando estiver a ${AppViewModel.ALERT_MIN} min",
                            tint = if (on) Star else Muted,
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFFF1F5F9))
            }
        }
    }
}

// =====================================================================================
// Busca
// =====================================================================================

@Composable
private fun SearchOverlay(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(Modifier.fillMaxSize(), color = Color.White) {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.closeSearch() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                TextField(
                    value = vm.searchQuery,
                    onValueChange = vm::onSearchQuery,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = {
                        Text(
                            when (vm.searchFor) {
                                Field.ORIGIN -> "De onde você sai?"
                                Field.DEST -> "Endereço, lugar ou parada"
                                Field.HOME -> "Endereço de casa"
                                Field.WORK -> "Endereço do trabalho"
                                null -> "Número ou nome da linha"
                            },
                        )
                    },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFF3F4F6), unfocusedContainerColor = Color(0xFFF3F4F6),
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
            }
            if (vm.searching) CircularProgressIndicator(Modifier.padding(start = 24.dp).size(16.dp), strokeWidth = 2.dp, color = Green)
            LazyColumn {
                val field = vm.searchFor
                if (field != null) {
                    if (vm.myLocation != null) item {
                        SearchRow(Icons.Default.MyLocation, "Minha localização", "") {
                            vm.choose(field, Endpoint("Minha localização", vm.myLocation!!, isMyLocation = true))
                        }
                    }
                    item { SearchRow(Icons.Default.Map, "Escolher no mapa", "") { vm.startPick(field) } }
                    val key = field.favKey
                    if (key != null && vm.favs.place(key) != null) item {
                        SearchRow(Icons.Default.Close, "Remover ${field.label}", "") { vm.removeFavorite(field) }
                    }
                    if (vm.searchQuery.isBlank()) {
                        if (key == null) {
                            for ((f, ic) in listOf(Field.HOME to Icons.Default.Home, Field.WORK to Icons.Default.Work)) {
                                val p = vm.favs.place(f.favKey!!) ?: continue
                                item { SearchRow(ic, f.label, p.name) { vm.choose(field, Endpoint(f.label, p.pos, isFavorite = true)) } }
                            }
                        }
                        items(vm.favs.recents) { r ->
                            SearchRow(Icons.Default.History, r.name, "Recente") { vm.choose(field, Endpoint(r.name, r.pos)) }
                        }
                    }
                }
                items(vm.searchHits) { hit ->
                    when (hit) {
                        is SearchHit.PlaceHit -> SearchRow(Icons.Default.Place, hit.place.name, hit.place.detail) { vm.onSearchHit(hit) }
                        is SearchHit.StopHit -> SearchRow(Icons.Default.DirectionsBus, hit.stop.label, "Parada de ônibus") { vm.onSearchHit(hit) }
                        is SearchHit.LineHit -> Row(
                            Modifier.fillMaxWidth().clickable { vm.onSearchHit(hit) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LineBadge(hit.line.numero)
                            Spacer(Modifier.width(12.dp))
                            Text(hit.line.descricao, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFF3F4F6)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color(0xFF374151), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
