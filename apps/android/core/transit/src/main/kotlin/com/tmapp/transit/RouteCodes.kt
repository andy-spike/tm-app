package com.tmapp.transit

/**
 * The feed files paired SITP routes under one joined code: `HA617` is the A617 and the H617,
 * and its long name reads "A617 Centro || H617 Perdomo". Riders only know the halves, so search
 * and badges use those.
 */
object RouteCodes {
    private val variants = listOf("Ciclovía", "Festivo", "Nocturna", "Especial")
    private val side = Regex("^([A-Z]{1,2}\\d{1,4}[A-Z]?)\\s+(.+)$")
    private val spaces = Regex("\\s+")

    fun describe(
        index: Int,
        id: String,
        shortName: String,
        longName: String?,
        system: SystemKind,
        agencyId: String,
    ): Route {
        var base = longName?.trim().orEmpty()
        for (v in variants) if (base.endsWith(" $v")) base = base.dropLast(v.length + 1).trim()
        val aliases = mutableListOf<String>()
        val places = base.split("||").map { it.replace(spaces, " ").trim() }.filter { it.isNotEmpty() }.map { part ->
            val match = side.matchEntire(part) ?: return@map part
            val code = match.groupValues[1]
            if (code !in aliases) aliases += code
            match.groupValues[2]
        }.distinct()
        val code = if (aliases.isEmpty()) shortName.trim() else aliases.joinToString(" · ")
        return Route(
            index = index,
            id = id,
            feedCode = shortName.trim(),
            code = code.ifEmpty { "?" },
            aliases = aliases.ifEmpty { listOf(shortName.trim()) },
            longName = places.joinToString(" – "),
            system = system,
            agencyId = agencyId,
        )
    }

    /** "K635" -> "K 635" so screen readers say the letter and the number. */
    fun sayable(code: String): String = code.replace(Regex("([A-Z]+)(\\d+)"), "$1 $2")
}
