package com.tmapp.transit

import java.text.Normalizer

sealed interface SearchHit {
    val score: Int

    data class StopHit(val stop: Int, override val score: Int) : SearchHit
    data class RouteHit(val route: Int, override val score: Int) : SearchHit
}

/**
 * In-memory search over stations, stops and routes. Accent-insensitive word prefixes, one typo for
 * words of four letters or more, and route codes as riders read them (K635 finds HK635).
 */
class TransitSearch(private val tt: Timetable) {
    private class Entry(val tokens: Array<String>, val joined: String)

    private val stopEntries: Array<Entry>
    /** Platforms collapse into their station unless the query names a code. */
    private val visibleStop: BooleanArray
    private val routeCodes: Array<Array<String>>
    private val routeNames: Array<Entry>
    private val routeOrder: IntArray

    init {
        stopEntries = Array(tt.stopCount) { entry(tt.stops[it].name) }
        visibleStop = BooleanArray(tt.stopCount) { tt.stops[it].parent < 0 }
        routeCodes = Array(tt.routes.size) { r ->
            val route = tt.routes[r]
            (route.aliases + route.feedCode).map { normalize(it).replace(" ", "").replace("-", "") }.distinct().toTypedArray()
        }
        routeNames = Array(tt.routes.size) { entry(tt.routes[it].longName) }
        // Prefer the everyday service over its holiday/Ciclovía twin when codes collide.
        val trips = IntArray(tt.routes.size)
        for (p in 0 until tt.patternCount) trips[tt.patternRoute[p]] += tt.tripCount(p)
        routeOrder = trips
    }

    fun search(query: String, limit: Int = 30): List<SearchHit> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        val compact = q.replace(" ", "").replace("-", "")
        val hits = ArrayList<SearchHit>()
        // Routes by code.
        val seenCodes = HashMap<String, SearchHit.RouteHit>()
        for (r in tt.routes.indices) {
            var score = 0
            for (code in routeCodes[r]) {
                score = maxOf(score, when {
                    code == compact -> 1000
                    code.startsWith(compact) && compact.length >= 1 -> 700 - (code.length - compact.length) * 10
                    else -> 0
                })
            }
            if (score == 0 && qTokens.size >= 1 && compact.length >= 3) {
                val s = matchTokens(qTokens, routeNames[r])
                if (s > 0) score = s / 2
            }
            if (score <= 0) continue
            val key = tt.routes[r].code
            val hit = SearchHit.RouteHit(r, score + minOf(routeOrder[r], 999) / 100)
            val existing = seenCodes[key]
            if (existing == null || hit.score > existing.score) seenCodes[key] = hit
        }
        hits += seenCodes.values
        // Stops by name or code.
        val seenStations = HashSet<Int>()
        for (s in 0 until tt.stopCount) {
            val stop = tt.stops[s]
            val codeMatch = stop.code?.let { normalize(it).replace(" ", "") == compact } == true
            if (!visibleStop[s] && !codeMatch) continue
            var score = matchTokens(qTokens, stopEntries[s])
            if (codeMatch) score = maxOf(score, 900)
            if (score <= 0) continue
            if (stop.isStation) score += 150
            if (stop.isStation || stop.parent < 0) {
                if (!seenStations.add(s)) continue
            }
            hits += SearchHit.StopHit(s, score)
        }
        hits.sortWith(compareByDescending<SearchHit> { it.score }.thenBy { label(it).length })
        return if (hits.size > limit) hits.subList(0, limit) else hits
    }

    private fun label(hit: SearchHit) = when (hit) {
        is SearchHit.StopHit -> tt.stops[hit.stop].name
        is SearchHit.RouteHit -> tt.routes[hit.route].code
    }

    /** Every query token must match some name token (prefix, or one edit for longer words). */
    private fun matchTokens(query: List<String>, entry: Entry): Int {
        if (entry.tokens.isEmpty()) return 0
        var total = 0
        for ((qi, q) in query.withIndex()) {
            var best = 0
            for ((ti, t) in entry.tokens.withIndex()) {
                val s = when {
                    t == q -> 120
                    t.startsWith(q) -> 90 - minOf(t.length - q.length, 30)
                    q.length >= 4 && withinOneEdit(q, t.take(q.length + 1)) -> 45
                    q.length >= 4 && withinOneEdit(q, t) -> 40
                    else -> 0
                }
                // Earlier name words and in-order matches rank higher.
                val bonus = if (s > 0) (if (ti == qi) 15 else 0) + (if (ti == 0) 20 else 0) else 0
                best = maxOf(best, s + bonus)
            }
            if (best == 0) return 0
            total += best
        }
        if (entry.joined.startsWith(query.joinToString(" "))) total += 80
        return total
    }

    private fun entry(name: String): Entry {
        val n = normalize(name)
        return Entry(n.split(' ').filter { it.isNotEmpty() }.toTypedArray(), n)
    }

    companion object {
        private val marks = Regex("\\p{Mn}+")
        private val nonWord = Regex("[^a-z0-9-]+")

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(marks, "").replace(nonWord, " ").trim()

        fun withinOneEdit(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            if (a.length == b.length) {
                // Adjacent swaps ("portla") are the most common phone typo; count them as one edit.
                val k = a.indices.firstOrNull { a[it] != b[it] } ?: return true
                if (k + 1 < a.length && a[k] == b[k + 1] && a[k + 1] == b[k] && a.regionMatches(k + 2, b, k + 2, a.length - k - 2)) return true
            }
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) { i++; j++; continue }
                if (++edits > 1) return false
                when {
                    a.length > b.length -> i++
                    a.length < b.length -> j++
                    else -> { i++; j++ }
                }
            }
            return edits + (a.length - i) + (b.length - j) <= 1
        }
    }
}
