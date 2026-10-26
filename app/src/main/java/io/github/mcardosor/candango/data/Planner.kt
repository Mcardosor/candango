package io.github.mcardosor.candango.data

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Ônibus ao vivo se aproximando de um ponto. */
data class LiveEta(val bus: Bus, val minutes: Int, val distanceM: Int)

/** Um trecho de ônibus: embarca numa parada e desce noutra, no mesmo sentido de uma linha. */
data class RideLeg(
    val dir: Dir,
    val boardStop: Stop,
    val alightStop: Stop,
    val boardAlong: Int,
    val alightAlong: Int,
) {
    val rideMin: Int get() = dir.minutesBetween(boardAlong, alightAlong).roundToInt()
    val distanceM: Int get() = alightAlong - boardAlong
}

/** Uma viagem concreta: horário de embarque em cada trecho e chegada ao destino (minutos desde 00:00 de hoje). */
data class Trip(val boards: List<Int>, val arrival: Int)

/** Uma opção de viagem: um trecho (direta) ou dois (com baldeação). */
data class PlanOption(
    val legs: List<RideLeg>,
    val walkToM: Int,
    /** caminhada entre a descida do 1º ônibus e o embarque no 2º (0 se for na mesma parada) */
    val transferWalkM: Int,
    val walkFromM: Int,
    /** viagens com embarque dentro da janela, da mais cedo para a mais tarde */
    val trips: List<Trip>,
    /** ônibus ao vivo da 1ª linha vindo para o ponto de embarque */
    val live: LiveEta?,
) {
    val first: RideLeg get() = legs.first()
    val last: RideLeg get() = legs.last()
    val isDirect: Boolean get() = legs.size == 1
    val walkToMin: Int get() = Planner.walkMinutes(walkToM)
    val walkFromMin: Int get() = Planner.walkMinutes(walkFromM)
    val transferWalkMin: Int get() = Planner.walkMinutes(transferWalkM)
    /** horários de embarque no primeiro ônibus */
    val boardTimes: List<Int> get() = trips.map { it.boards.first() }

    /** identifica a opção entre replanejamentos (para manter a tela de rota aberta) */
    val key: String get() = legs.joinToString("|") { "${it.dir.line.numero}/${it.dir.sentido}/${it.boardStop.idx}" }
}

object Planner {
    private const val SEARCH_RADIUS_M = 700.0
    private const val WALK_M_PER_MIN = 75.0
    private const val WALK_DETOUR = 1.25 // caminho real ≈ 25% maior que a linha reta
    private const val MIN_RIDE_M = 300
    private const val TRANSFER_BUFFER_MIN = 2   // folga para descer e chegar ao outro ponto
    private const val MAX_TRANSFER_WAIT_MIN = 60
    private const val TRANSFER_PENALTY_MIN = 8  // incômodo de trocar de ônibus, usado só na ordenação
    private const val MAX_TRANSFER_CANDIDATES = 200 // muitas linhas só rodam no pico; avaliar poucas deixa a janela vazia
    private const val MAX_TRANSFER_RESULTS = 6

    fun walkMinutes(m: Int) = (m / WALK_M_PER_MIN).roundToInt()
    private fun walkM(straightM: Double) = (straightM * WALK_DETOUR).roundToInt()

    /**
     * Opções de [origin] a [dest] com embarque entre [start] e [start] + [windowMin] minutos:
     * linhas diretas e, quando ajudam, combinações com uma baldeação.
     */
    fun plan(origin: LatLon, dest: LatLon, start: ZonedDateTime, windowMin: Int, buses: List<Bus>): List<PlanOption> {
        val nearO = Repo.stopsNear(origin, SEARCH_RADIUS_M)
        val nearD = Repo.stopsNear(dest, SEARCH_RADIUS_M)
        if (nearO.isEmpty() || nearD.isEmpty()) return emptyList()

        val ctx = Ctx(start, windowMin, buses)
        val direct = directOptions(nearO, nearD, ctx)
        val bestDirect = direct.minOfOrNull { sortKey(it) }
        val transfers = transferOptions(nearO, nearD, ctx, direct.map { it.first.dir.line }.toSet())
            // com boas diretas, só mostra baldeação que chega antes
            .filter { bestDirect == null || sortKey(it) < bestDirect }
            .take(if (direct.isEmpty()) MAX_TRANSFER_RESULTS + 2 else MAX_TRANSFER_RESULTS)

        return (direct + transfers).sortedBy { sortKey(it) }
    }

    private class Ctx(val start: ZonedDateTime, val windowMin: Int, val buses: List<Bus>) {
        val today: LocalDate = start.toLocalDate()
        val startMin = start.hour * 60 + start.minute
        val nowMin = ZonedDateTime.now(Time.ZONE).let { it.hour * 60 + it.minute }

        private val passCache = HashMap<Pair<Dir, Int>, List<Int>>()

        /** passagens num ponto do trajeto, de ontem a amanhã, em minutos relativos a hoje */
        fun passes(dir: Dir, along: Int): List<Int> = passCache.getOrPut(dir to along) {
            (dir.passingTimes(today.minusDays(1), along).map { it - 1440 } +
                dir.passingTimes(today, along) +
                dir.passingTimes(today.plusDays(1), along).map { it + 1440 }).distinct().sorted()
        }

        fun inWindow(passes: List<Int>, walkToMin: Int) =
            passes.filter { it in (startMin + walkToMin)..(startMin + windowMin) }
    }

    // ---------- diretas ----------

    private fun directOptions(
        nearO: List<Pair<Stop, Double>>, nearD: List<Pair<Stop, Double>>, ctx: Ctx,
    ): List<PlanOption> {
        val destDist = nearD.associate { it.first.idx to it.second }
        // melhor par embarque/desembarque por sentido de linha
        val best = HashMap<Dir, Triple<RideLeg, Int, Int>>() // leg, walkTo, walkFrom
        for ((oStop, oDist) in nearO) {
            for ((dir, oAlong) in Repo.dirsAtStop[oStop.idx]) {
                for (k in dir.stopIdx.indices) {
                    val dAlong = dir.stopAlong[k]
                    if (dAlong - oAlong < MIN_RIDE_M) continue
                    val dDist = destDist[dir.stopIdx[k]] ?: continue
                    val leg = RideLeg(dir, oStop, Repo.stops[dir.stopIdx[k]], oAlong, dAlong)
                    val walkTo = walkM(oDist)
                    val walkFrom = walkM(dDist)
                    val cost = walkMinutes(walkTo) + walkMinutes(walkFrom) + leg.rideMin
                    val cur = best[dir]
                    if (cur == null || cost < walkMinutes(cur.second) + walkMinutes(cur.third) + cur.first.rideMin) {
                        best[dir] = Triple(leg, walkTo, walkFrom)
                    }
                }
            }
        }

        return best.values.map { (leg, walkTo, walkFrom) ->
            val boards = ctx.inWindow(ctx.passes(leg.dir, leg.boardAlong), walkMinutes(walkTo))
            PlanOption(
                legs = listOf(leg), walkToM = walkTo, transferWalkM = 0, walkFromM = walkFrom,
                trips = boards.map { Trip(listOf(it), it + leg.rideMin + walkMinutes(walkFrom)) },
                live = liveEta(leg.dir, leg.boardAlong, ctx.buses),
            )
        }
            .filter { it.trips.isNotEmpty() || it.live != null }
            // uma opção por linha: o sentido que chega primeiro
            .groupBy { it.first.dir.line }
            .map { (_, opts) -> opts.minBy { sortKey(it) } }
    }

    // ---------- uma baldeação ----------

    /** Segundo trecho possível: embarca em [board] (no sentido [dir]) e desce perto do destino. */
    private class SecondLeg(val leg: RideLeg, val walkFromM: Int) {
        val cost get() = leg.rideMin + walkMinutes(walkFromM)
    }

    private fun transferOptions(
        nearO: List<Pair<Stop, Double>>, nearD: List<Pair<Stop, Double>>, ctx: Ctx, directLines: Set<Line>,
    ): List<PlanOption> {
        // 1) lado do destino: para cada parada "a montante", o melhor 2º trecho de cada sentido de linha
        val secondByStop = HashMap<Int, HashMap<Dir, SecondLeg>>()
        for ((dStop, dDist) in nearD) {
            for ((dirB, dAlong) in Repo.dirsAtStop[dStop.idx]) {
                if (dirB.line in directLines) continue // já resolve sozinha
                val walkFrom = walkM(dDist)
                for (k in dirB.stopIdx.indices) {
                    val uAlong = dirB.stopAlong[k]
                    if (dAlong - uAlong < MIN_RIDE_M) continue
                    val cand = SecondLeg(RideLeg(dirB, Repo.stops[dirB.stopIdx[k]], dStop, uAlong, dAlong), walkFrom)
                    val m = secondByStop.getOrPut(dirB.stopIdx[k]) { HashMap() }
                    val cur = m[dirB]
                    if (cur == null || cand.cost < cur.cost) m[dirB] = cand
                }
            }
        }
        if (secondByStop.isEmpty()) return emptyList()

        // 2) lado da origem: desce do 1º ônibus em algum ponto e caminha até um ponto do 2º
        data class Cand(val legA: RideLeg, val walkTo: Int, val transferWalk: Int, val second: SecondLeg) {
            val cost get() = walkMinutes(walkTo) + legA.rideMin + walkMinutes(transferWalk) + second.cost
        }
        val best = HashMap<Pair<Dir, Dir>, Cand>()
        for ((oStop, oDist) in nearO) {
            val walkTo = walkM(oDist)
            for ((dirA, oAlong) in Repo.dirsAtStop[oStop.idx]) {
                if (dirA.line in directLines) continue
                for (k in dirA.stopIdx.indices) {
                    val tAlong = dirA.stopAlong[k]
                    if (tAlong - oAlong < MIN_RIDE_M) continue
                    val alight = Repo.stops[dirA.stopIdx[k]]
                    for ((tStop, tDist) in Repo.transferNeighbors(alight)) {
                        val seconds = secondByStop[tStop.idx] ?: continue
                        for ((dirB, second) in seconds) {
                            if (dirB.line == dirA.line) continue
                            val c = Cand(RideLeg(dirA, oStop, alight, oAlong, tAlong), walkTo, walkM(tDist), second)
                            val key = dirA to dirB
                            val cur = best[key]
                            if (cur == null || c.cost < cur.cost) best[key] = c
                        }
                    }
                }
            }
        }

        // 3) só os candidatos mais promissores ganham o encadeamento de horários (a parte cara)
        return best.values.sortedBy { it.cost }.take(MAX_TRANSFER_CANDIDATES).mapNotNull { c ->
            val legB = c.second.leg
            val walkToMin = walkMinutes(c.walkTo)
            val transferMin = walkMinutes(c.transferWalk)
            val walkFromMin = walkMinutes(c.second.walkFromM)
            val passesB = ctx.passes(legB.dir, legB.boardAlong)
            // para cada 2º ônibus, fica com o 1º ônibus mais tarde que ainda o alcança (menos espera)
            val byB = LinkedHashMap<Int, Trip>()
            for (a in ctx.inWindow(ctx.passes(c.legA.dir, c.legA.boardAlong), walkToMin)) {
                val ready = a + c.legA.rideMin + transferMin + TRANSFER_BUFFER_MIN
                val b = passesB.firstOrNull { it >= ready } ?: continue
                if (b - ready > MAX_TRANSFER_WAIT_MIN) continue
                byB[b] = Trip(listOf(a, b), b + legB.rideMin + walkFromMin)
            }
            if (byB.isEmpty()) return@mapNotNull null
            PlanOption(
                legs = listOf(c.legA, legB), walkToM = c.walkTo, transferWalkM = c.transferWalk,
                walkFromM = c.second.walkFromM, trips = byB.values.sortedBy { it.boards.first() },
                live = liveEta(c.legA.dir, c.legA.boardAlong, ctx.buses),
            )
        }
            .sortedBy { sortKey(it) }
            // evita repetir a mesma dupla de linhas em sentidos/pontos diferentes
            .distinctBy { o -> o.legs.map { it.dir.line } }
    }

    // ---------- ordenação ----------

    /** Chegada estimada ao destino (minutos desde 00:00) + penalidade por baldeação. */
    fun sortKey(o: PlanOption): Int {
        val nowMin = ZonedDateTime.now(Time.ZONE).let { it.hour * 60 + it.minute }
        val arrival = if (o.isDirect) {
            val board = o.live?.takeIf { it.minutes >= o.walkToMin }?.let { nowMin + it.minutes }
                ?: o.trips.firstOrNull()?.boards?.first()
            board?.let { it + o.first.rideMin + o.walkFromMin }
        } else {
            o.trips.firstOrNull()?.arrival
        } ?: return Int.MAX_VALUE / 2
        return arrival + (o.legs.size - 1) * TRANSFER_PENALTY_MIN
    }

    /** Ônibus da linha, no mesmo sentido, que ainda não passou pelo ponto [alongM]. */
    fun liveEta(dir: Dir, alongM: Int, buses: List<Bus>): LiveEta? {
        val now = System.currentTimeMillis()
        return buses.asSequence()
            .filter { it.linha == dir.line.numero && now - it.timeMs < 10 * 60_000 }
            .mapNotNull { bus ->
                val p = dir.project(bus.pos)
                if (p.distM > 60) return@mapNotNull null
                if (bus.heading != null && (bus.speedKmh ?: 0.0) > 3 && Geo.angleDiff(bus.heading, p.bearing) > 75) return@mapNotNull null
                val gap = alongM - p.alongM
                if (gap < -50) return@mapNotNull null
                LiveEta(bus, dir.minutesBetween(0, gap.coerceAtLeast(0)).roundToInt(), gap.coerceAtLeast(0))
            }
            .minByOrNull { it.distanceM }
    }

    /** Próximas passagens de cada sentido de linha numa parada. */
    fun stopDepartures(stop: Stop, start: ZonedDateTime, windowMin: Int, buses: List<Bus>): List<Triple<Dir, List<Int>, LiveEta?>> {
        val today = start.toLocalDate()
        val startMin = start.hour * 60 + start.minute
        return Repo.dirsAtStop[stop.idx].map { (dir, along) ->
            val passes = (dir.passingTimes(today, along) + dir.passingTimes(today.plusDays(1), along).map { it + 1440 })
                .filter { it in startMin..(startMin + windowMin) }.distinct().sorted()
            Triple(dir, passes, liveEta(dir, along, buses))
        }
            .filter { it.second.isNotEmpty() || it.third != null }
            .sortedBy { it.third?.minutes?.plus(startMin) ?: it.second.firstOrNull() ?: Int.MAX_VALUE }
    }
}

object Time {
    val ZONE: java.time.ZoneId = java.time.ZoneId.of("America/Sao_Paulo")

    /** minutos desde 00:00 de hoje -> "HH:mm" (marca "+1" se for amanhã) */
    fun fmt(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        val s = "%02d:%02d".format(m / 60, m % 60)
        return if (min >= 1440) "$s (+1)" else s
    }

    /** espera em minutos -> "7 min" ou "3h16" */
    fun wait(min: Int): String = if (min < 60) "$min min" else "%dh%02d".format(min / 60, min % 60)
}
