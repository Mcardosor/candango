package io.github.mcardosor.candango.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/** Testes com os dados reais de assets/dados.json. */
class PlannerTest {

    companion object {
        @BeforeClass @JvmStatic
        fun load() {
            if (!Repo.isLoaded) Repo.loadFromText(File("src/main/assets/dados.json").readText())
        }

        val RODOVIARIA = LatLon(-15.7942, -47.8825)
        val TAGUATINGA_CENTRO = LatLon(-15.8331, -48.0567)
        val ESPLANADA = LatLon(-15.7998, -47.8645)
        val LAGO_NORTE = LatLon(-15.7330, -47.8350)

        val CIDADES = linkedMapOf(
            "Rodoviária" to RODOVIARIA,
            "Taguatinga" to TAGUATINGA_CENTRO,
            "Ceilândia" to LatLon(-15.8190, -48.1080),
            "Gama" to LatLon(-16.0150, -48.0650),
            "Planaltina" to LatLon(-15.6203, -47.6510),
            "Sobradinho" to LatLon(-15.6510, -47.7910),
            "Samambaia" to LatLon(-15.8770, -48.0830),
            "Paranoá" to LatLon(-15.7740, -47.7790),
            "Lago Norte" to LAGO_NORTE,
            "Guará" to LatLon(-15.8230, -47.9770),
        )
    }

    /** Cobertura entre centros de 10 cidades às 9h: com uma troca, quase todos os pares têm opção. */
    @Test fun coberturaEntreCidades() {
        val start = ZonedDateTime.now(Time.ZONE).withHour(9).withMinute(0)
        var comOpcao = 0
        var soComTroca = 0
        var total = 0
        val t0 = System.currentTimeMillis()
        for ((a, pa) in CIDADES) for ((b, pb) in CIDADES) {
            if (a == b) continue
            total++
            val res = Planner.plan(pa, pb, start, 180, emptyList())
            if (res.isNotEmpty()) comOpcao++
            if (res.none { it.isDirect } && res.any { !it.isDirect }) soComTroca++
            res.filter { !it.isDirect }.forEach { checkTransfer(it) }
        }
        println("$total pares: $comOpcao com opção ($soComTroca só com troca) em ${System.currentTimeMillis() - t0} ms")
        assertTrue(comOpcao >= total * 0.8)
        assertTrue(soComTroca >= 20)
    }

    @Test fun polylineRoundTrip() {
        // "_p~iF~ps|U_ulLnnqC_mqNvxq`@" é o exemplo da documentação do Google
        val pts = decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6)
        assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6)
    }

    @Test fun dataLoaded() {
        assertTrue(Repo.stops.size > 5000)
        assertTrue(Repo.lines.size > 900)
        val l = Repo.lineByNumero.getValue("0.111")
        val dir = l.dirs.first()
        // projeção de um ponto do próprio trajeto deve cair em cima dele
        val mid = dir.shape[dir.shape.size / 2]
        val p = dir.project(mid)
        assertTrue("dist=${p.distM}", p.distM < 1.0)
        assertTrue(p.alongM in 1 until dir.lengthM)
        // paradas atendidas estão perto do trajeto
        for (k in dir.stopIdx.indices) {
            val d = dir.project(Repo.stops[dir.stopIdx[k]].pos).distM
            assertTrue("parada ${Repo.stops[dir.stopIdx[k]].cod} a ${d}m", d < 45)
        }
    }

    @Test fun rodoviariaParaTaguatinga() {
        val start = ZonedDateTime.now(Time.ZONE).withHour(8).withMinute(0)
        val res = Planner.plan(RODOVIARIA, TAGUATINGA_CENTRO, start, 180, emptyList())
        println("Rodoviária -> Taguatinga às 08:00: ${res.size} linhas")
        res.take(8).forEach { println("  " + describe(it)) }
        assertTrue("esperava várias linhas diretas", res.size >= 3)
        for (o in res) {
            assertTrue(o.legs.all { it.alightAlong > it.boardAlong })
            assertTrue(o.boardTimes.all { it in (8 * 60)..(11 * 60) })
        }
    }

    @Test fun gradeDeParadasIgualBuscaLinear() {
        for (p in listOf(RODOVIARIA, TAGUATINGA_CENTRO, LatLon(-15.6203, -47.6510), LatLon(-16.0170, -48.0620))) {
            val linear = Repo.stops.filter { Geo.dist(it.pos, p) <= 700.0 }.map { it.idx }.toSet()
            val grid = Repo.stopsNear(p, 700.0).map { it.first.idx }.toSet()
            println("(${p.lat}, ${p.lon}): ${linear.size} paradas (linear) / ${grid.size} (grade)")
            assertEquals(linear, grid)
            val lines = Repo.stopsNear(p, 700.0).flatMap { Repo.dirsAtStop[it.first.idx] }.map { it.first.line.numero }.distinct()
            println("   linhas: ${lines.size} ${lines.take(12)}")
        }
    }

    @Test fun baldeacaoQuandoNaoHaDireta() {
        val start = ZonedDateTime.now(Time.ZONE).withHour(9).withMinute(0)
        val t0 = System.currentTimeMillis()
        val res = Planner.plan(TAGUATINGA_CENTRO, LAGO_NORTE, start, 180, emptyList())
        println("Taguatinga -> Lago Norte às 09:00 (${System.currentTimeMillis() - t0} ms): ${res.size} opções, ${res.count { it.isDirect }} diretas")
        assertEquals("não deveria haver linha direta", 0, res.count { it.isDirect })
        res.forEach { println("  " + describe(it)) }
        val transfers = res.filter { !it.isDirect }
        assertTrue("esperava opções com baldeação", transfers.isNotEmpty())
        for (o in transfers) checkTransfer(o)
    }

    @Test fun baldeacaoSoAparecesSeForMaisRapida() {
        val start = ZonedDateTime.now(Time.ZONE).withHour(8).withMinute(0)
        val res = Planner.plan(RODOVIARIA, TAGUATINGA_CENTRO, start, 180, emptyList())
        val bestDirect = res.filter { it.isDirect }.minOf { Planner.sortKey(it) }
        for (o in res.filter { !it.isDirect }) {
            checkTransfer(o)
            assertTrue("baldeação mais lenta que a melhor direta: ${describe(o)}", Planner.sortKey(o) < bestDirect)
        }
    }

    /** horários encadeados: o 2º ônibus sai depois de o 1º chegar e de dar tempo de caminhar */
    private fun checkTransfer(o: PlanOption) {
        assertEquals(2, o.legs.size)
        assertTrue(o.legs[0].dir.line != o.legs[1].dir.line)
        assertTrue(o.transferWalkM <= (Repo.TRANSFER_RADIUS_M * 1.25).toInt() + 1)
        assertTrue(o.trips.isNotEmpty())
        for (t in o.trips) {
            val ready = t.boards[0] + o.legs[0].rideMin + o.transferWalkMin
            assertTrue("troca impossível em ${describe(o)}", t.boards[1] >= ready)
            assertTrue(t.boards[1] - ready <= 62)
            assertEquals(t.boards[1] + o.legs[1].rideMin + o.walkFromMin, t.arrival)
        }
    }

    @Test fun janelaDeTresHorasMudaComHorario() {
        val now = ZonedDateTime.now(Time.ZONE).withHour(7).withMinute(0)
        val a = Planner.plan(RODOVIARIA, ESPLANADA, now, 180, emptyList())
        val b = Planner.plan(RODOVIARIA, ESPLANADA, now.plusHours(3), 180, emptyList())
        println("Rodoviária -> Esplanada: ${a.size} linhas às 07:00, ${b.size} às 10:00")
        a.take(5).forEach { println("  07h " + describe(it)) }
        b.take(5).forEach { println("  10h " + describe(it)) }
        assertTrue(a.isNotEmpty())
    }

    @Test fun gpsAoVivo() = runBlocking {
        // depende da API da SEMOB estar no ar; se não estiver, o teste é pulado em vez de falhar
        val buses = runCatching { LiveApi.fetchBuses() }.getOrNull()
        Assume.assumeTrue("API de GPS da SEMOB indisponível", buses != null)
        buses!!
        println("GPS: ${buses.size} ônibus, ${buses.count { it.linha != null }} com linha")
        assertTrue(buses.size > 100)
        // pelo menos parte dos ônibus com linha deve cair sobre o trajeto da própria linha
        val withLine = buses.filter { it.linha != null && Repo.lineByNumero[it.linha] != null }.take(300)
        val onRoute = withLine.count { b -> Repo.lineByNumero.getValue(b.linha!!).dirs.any { it.project(b.pos).distM < 60 } }
        println("Ônibus sobre o próprio trajeto: $onRoute de ${withLine.size}")
        assertTrue(onRoute > withLine.size / 2)

        val res = Planner.plan(RODOVIARIA, TAGUATINGA_CENTRO, ZonedDateTime.now(Time.ZONE), 180, buses)
        println("Agora, Rodoviária -> Taguatinga: ${res.size} linhas, ${res.count { it.live != null }} com ônibus ao vivo chegando")
        res.take(5).forEach { println("  " + describe(it)) }

        // em paradas no meio do trajeto, linhas com ônibus rodando devem ter previsão ao vivo
        val running = withLine.mapNotNull { Repo.lineByNumero[it.linha!!] }.distinct().take(60)
        var checked = 0
        var withEta = 0
        for (line in running) for (dir in line.dirs) {
            if (dir.stopIdx.size < 10) continue
            checked++
            val eta = (dir.stopIdx.size / 2 until dir.stopIdx.size).firstNotNullOfOrNull { k -> Planner.liveEta(dir, dir.stopAlong[k], buses) }
            if (eta != null) withEta++
        }
        println("Sentidos com ônibus ao vivo vindo para a 2ª metade das paradas: $withEta de $checked")
        assertTrue(withEta > checked / 4)
    }

    private fun describe(o: PlanOption) =
        o.legs.joinToString(" → ") { "${it.dir.line.numero} (${it.dir.label}, ${it.rideMin}min)" } +
            " | a pé ${o.walkToM}m" + (if (o.isDirect) "" else " + troca ${o.transferWalkM}m") + " + ${o.walkFromM}m" +
            " | " + o.trips.take(3).joinToString("  ") { t -> t.boards.joinToString("/") { Time.fmt(it) } + "→" + Time.fmt(t.arrival) } +
            (o.live?.let { " | AO VIVO ${it.minutes}min (${it.bus.id})" } ?: "")
}
