package com.tmapp.transit

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** Loads the pipeline's latest bundle once; tests skip when it has not been built in this checkout. */
object Fixture {
    val dir: File? by lazy {
        val out = File(System.getProperty("tm.bundleDir") ?: return@lazy null)
        val latest = File(out, "latest.json").takeIf { it.exists() } ?: return@lazy null
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(latest.readText())!!.groupValues[1]
        File(out, version)
    }
    val timetable: Timetable by lazy {
        val started = System.nanoTime()
        BundleDecoder.decode(File(dir, "timetable.bin").readBytes()).also {
            println("decode ms=${(System.nanoTime() - started) / 1_000_000}")
        }
    }
    private val days = HashMap<LocalDate, ServiceDay>()
    fun day(date: LocalDate): ServiceDay = synchronized(days) { days.getOrPut(date) { ServiceDay(timetable, date) } }
    val router by lazy { Router(timetable) { day(it) } }
    fun at(text: String) = LocalDateTime.parse(text).atZone(BOGOTA).toInstant()
    fun station(name: String) = timetable.stops.first { it.isStation && it.name.startsWith(name) }
}

class TransitTest {
    private fun requireBundle() = assumeTrue("Run pnpm bundle:build first", Fixture.dir?.exists() == true)

    @Test fun decodesBundle() {
        requireBundle()
        val tt = Fixture.timetable
        assertThat(tt.stopCount).isGreaterThan(8000)
        assertThat(tt.routes.size).isGreaterThan(1000)
        assertThat(tt.patternCount).isGreaterThan(1000)
        assertThat(tt.feedEnd).isAtLeast(tt.feedStart)
        // Platforms point at their stations.
        val platform = tt.stops.first { it.parent >= 0 }
        assertThat(tt.stops[platform.parent].isStation).isTrue()
    }

    @Test fun splitsPairedRouteCodes() {
        val r = RouteCodes.describe(0, "x", "HA617", "A617 Centro || H617 Perdomo Ciclovía", SystemKind.URBANO, "3")
        assertThat(r.code).isEqualTo("A617 · H617")
        assertThat(r.aliases).containsExactly("A617", "H617")
        assertThat(r.longName).isEqualTo("Centro – Perdomo")
        val t = RouteCodes.describe(0, "y", "B10", "Portal Norte", SystemKind.TRONCAL, "1")
        assertThat(t.code).isEqualTo("B10")
        assertThat(RouteCodes.sayable("K635")).isEqualTo("K 635")
    }

    @Test fun searchRanksStationsAndRouteHalves() {
        requireBundle()
        val tt = Fixture.timetable
        val search = TransitSearch(tt)
        val first = search.search("portal norte").first()
        assertThat(first).isInstanceOf(SearchHit.StopHit::class.java)
        assertThat(tt.stops[(first as SearchHit.StopHit).stop].name).startsWith("Portal Norte")
        val typo = search.search("portla norte").filterIsInstance<SearchHit.StopHit>().map { tt.stops[it.stop].name }
        assertThat(typo.any { it.startsWith("Portal Norte") }).isTrue()
        val half = search.search("A617").first()
        assertThat(half).isInstanceOf(SearchHit.RouteHit::class.java)
        assertThat(tt.routes[(half as SearchHit.RouteHit).route].aliases).contains("A617")
        val b10 = search.search("b10").first() as SearchHit.RouteHit
        assertThat(tt.routes[b10.route].code).isEqualTo("B10")
        assertThat(search.search("ciudad jardin").filterIsInstance<SearchHit.StopHit>()).isNotEmpty()
    }

    @Test fun listsDeparturesAtAStation() {
        requireBundle()
        val tt = Fixture.timetable
        val station = Fixture.station("Portal Norte")
        val now = Fixture.at("2026-11-11T08:00")
        val list = Schedule(tt).departures(Fixture.day(now.atZone(BOGOTA).toLocalDate()), station.index, now)
        assertThat(list).isNotEmpty()
        assertThat(list.zipWithNext().all { (a, b) -> a.time <= b.time }).isTrue()
        assertThat(list.first().time).isAtLeast(now)
        val directions = Schedule(tt).directions(Fixture.day(LocalDate.parse("2026-11-11")), tt.routes.first { it.code == "B10" }.index)
        assertThat(directions).isNotEmpty()
        assertThat(directions.first().first).isNotNull()
    }

    @Test fun plansBetweenPortals() {
        requireBundle()
        val tt = Fixture.timetable
        val from = Fixture.station("Portal Norte")
        val to = Fixture.station("Portal Sur")
        val time = Fixture.at("2026-11-11T08:00")
        val request = TripRequest(Place(from.name, from.lat, from.lon, from.index), Place(to.name, to.lat, to.lon, to.index), time)
        val started = System.nanoTime()
        val options = Fixture.router.plan(request)
        println("portal plan ms=${(System.nanoTime() - started) / 1_000_000} options=${options.size}")
        assertThat(options).isNotEmpty()
        for (it in options) {
            assertThat(it.departure).isAtLeast(time)
            assertConsistent(it)
        }
        val arriveBy = Fixture.router.plan(request.copy(time = Fixture.at("2026-11-11T09:30"), arriveBy = true))
        assertThat(arriveBy).isNotEmpty()
        for (it in arriveBy) {
            assertThat(it.arrival).isAtMost(Fixture.at("2026-11-11T09:30"))
            assertConsistent(it)
        }
    }

    @Test fun routesRandomPairsQuickly() {
        requireBundle()
        val tt = Fixture.timetable
        val random = Random(7)
        val roots = tt.stops.filter { it.parent < 0 }
        val timings = ArrayList<Long>()
        var found = 0
        val time = Fixture.at("2026-11-11T07:30")
        Fixture.router.plan(TripRequest(Place("a", 4.65, -74.06), Place("b", 4.60, -74.08), time)) // warm up
        repeat(60) {
            val a = roots[random.nextInt(roots.size)]
            val b = roots[random.nextInt(roots.size)]
            val started = System.nanoTime()
            val result = Fixture.router.plan(TripRequest(Place(a.name, a.lat, a.lon, a.index), Place(b.name, b.lat, b.lon, b.index), time))
            timings += (System.nanoTime() - started) / 1_000_000
            if (result.any { it.rides.isNotEmpty() } || result.isNotEmpty()) found++
            result.forEach(::assertConsistent)
        }
        timings.sort()
        println("random pairs found=$found/60 median ms=${timings[30]} p90 ms=${timings[54]}")
        assertThat(found).isAtLeast(54)
    }

    @Test fun plansFromArbitraryPoint() {
        requireBundle()
        // Parque de la 93 to Universidad Nacional area.
        val result = Fixture.router.plan(
            TripRequest(Place("Mi ubicación", 4.6766, -74.0484), Place("Destino", 4.6381, -74.0840), Fixture.at("2026-11-11T12:00")),
        )
        assertThat(result.filter { it.rides.isNotEmpty() }).isNotEmpty()
        result.forEach(::assertConsistent)
        assertThat(result.first { it.rides.isNotEmpty() }.legs.first()).isInstanceOf(WalkLeg::class.java)
    }

    @Test fun decodesPolylines() {
        val line = Shapes.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertThat(line.size).isEqualTo(3)
        assertThat(line.lat(0)).isWithin(1e-6).of(38.5)
        assertThat(line.lon(2)).isWithin(1e-6).of(-126.453)
        requireBundle()
        val shapes = Shapes.parse(File(Fixture.dir, "shapes.json").readText())
        val tt = Fixture.timetable
        val p = (0 until tt.patternCount).first { shapes.pattern(it) != null && tt.patternSize(it) > 5 }
        val leg = shapes.leg(tt, p, 1, 4)
        assertThat(leg.size).isAtLeast(2)
    }

    private fun assertConsistent(it: Itinerary) {
        assertThat(it.arrival).isAtLeast(it.departure)
        for ((a, b) in it.legs.zipWithNext()) assertThat(b.departure).isAtLeast(a.arrival)
        for (leg in it.rides) {
            assertThat(leg.alightPosition).isGreaterThan(leg.boardPosition)
            assertThat(leg.stops.zipWithNext().all { (x, y) -> y.arrival >= x.departure }).isTrue()
        }
    }
}
