package com.brandonwallace.rocketlaunch.v2.game

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/** First-time zone crossings pay a one-off bonus. These are the only bonuses; there are no collectibles. */
enum class Milestone(val title: String, val altitude: Double, val bonus: Long) {
    KM1("Kilometer Club", 1_000.0, 40),
    CLOUDS("Cloud Deck", 5_000.0, 120),
    STRATOSPHERE("Stratosphere", 12_000.0, 250),
    MESOSPHERE("Mesosphere", 50_000.0, 500),
    KARMAN("Kármán Line — Space!", 100_000.0, 1_000),
    LEO("Orbital Altitude", 400_000.0, 1_500),
    MEO("Medium Earth Orbit", 2_000_000.0, 2_200),
    GEO("Geostationary Belt", 35_786_000.0, 3_500),
    CISLUNAR("Cislunar Space", 150_000_000.0, 5_000),
    MOON("Lunar Arrival", Physics.MOON_ARRIVAL_ALT, 12_000),
}

/** Badges only (no money). */
enum class Achievement(val title: String, val desc: String) {
    FIRST_FLIGHT("Liftoff!", "Complete your first flight"),
    SOUND_BARRIER("Sound Barrier", "Fly faster than 343 m/s"),
    HYPERSONIC("Hypersonic", "Fly faster than 5 km/s"),
    ESCAPE("Escape Velocity", "Reach 11 km/s"),
    TOUGH("Tough as Nails", "Survive 3 hazard hits in one flight"),
    STAGER("Staging Master", "Separate 3 stages in one flight"),
    MOON_LANDING("The Eagle Has Landed", "Land on the Moon with fuel to spare"),
    BRANCH("Specialist", "Complete an entire tech branch"),
    TREE("Chief Engineer", "Research the whole tech tree"),
}

/**
 * Settlement of one launch. [performancePay] is what the results screen shows as one line; it silently
 * contains the bank interest on the balance carried into the launch (by design, the player never sees
 * interest as its own line).
 */
class Settlement(
    val altitudePay: Long,
    val milestones: List<Milestone>,
    internal val interest: Long,
) {
    val milestonePay: Long get() = milestones.sumOf { it.bonus }
    val performancePay: Long get() = altitudePay + interest
    val total: Long get() = performancePay + milestonePay
}

object Economy {
    /** Hidden compound interest on unspent money carried into a launch. */
    const val INTEREST_RATE = 0.025

    /** Pay for the highest altitude reached. Grows fast in the atmosphere, logarithmically in space. */
    fun altitudePay(maxAltM: Double): Long {
        val km = maxAltM / 1000.0
        if (km <= 0.0) return 0
        val low = 95.0 * km.coerceAtMost(100.0).pow(0.6)
        val high = if (km > 100.0) 420.0 * ln(km / 100.0) else 0.0
        return (low + high).roundToLong()
    }

    fun interest(balanceCarriedIntoLaunch: Long): Long =
        if (balanceCarriedIntoLaunch <= 0) 0 else (balanceCarriedIntoLaunch * INTEREST_RATE).roundToLong()

    fun newMilestones(maxAlt: Double, already: Set<Milestone>): List<Milestone> =
        Milestone.entries.filter { maxAlt >= it.altitude && it !in already }

    fun settle(balanceAtLaunch: Long, maxAlt: Double, already: Set<Milestone>): Settlement =
        Settlement(altitudePay(maxAlt), newMilestones(maxAlt, already), interest(balanceAtLaunch))
}

/** Immutable save game, serialized as "k=v;" (no Android deps, unit-testable). Separate from v1's save. */
data class SaveData(
    val money: Long = 0,
    val tech: Set<String> = emptySet(),
    val milestones: Set<Milestone> = emptySet(),
    val achievements: Set<Achievement> = emptySet(),
    val bestAltitude: Double = 0.0,
    val bestSpeed: Double = 0.0,
    val flights: Int = 0,
    val totalEarned: Long = 0,
    val moonLandings: Int = 0,
    val soundOn: Boolean = true,
    val tiltControl: Boolean = false,
    /** 0 = Low, 1 = Medium, 2 = High. */
    val quality: Int = 1,
) {
    fun has(id: String) = id in tech
    fun canResearch(node: TechNode) = TechTree.isAvailable(tech, node) && money >= node.cost

    /** New save after researching a node, or null if locked / unaffordable / owned. */
    fun research(id: String): SaveData? {
        val node = TechTree.byId[id] ?: return null
        if (!canResearch(node)) return null
        return copy(money = money - node.cost, tech = tech + id)
    }

    fun serialize(): String = buildString {
        append("v=2;money=").append(money)
        append(";bestAlt=").append(bestAltitude)
        append(";bestSpd=").append(bestSpeed)
        append(";flights=").append(flights)
        append(";earned=").append(totalEarned)
        append(";moon=").append(moonLandings)
        append(";sound=").append(soundOn)
        append(";tilt=").append(tiltControl)
        append(";q=").append(quality)
        append(";tech=").append(tech.sorted().joinToString(","))
        append(";ms=").append(milestones.joinToString(",") { it.name })
        append(";ach=").append(achievements.joinToString(",") { it.name })
    }

    companion object {
        fun parse(s: String?): SaveData {
            if (s.isNullOrBlank()) return SaveData()
            val m = HashMap<String, String>()
            for (part in s.split(';')) {
                val i = part.indexOf('=')
                if (i > 0) m[part.substring(0, i)] = part.substring(i + 1)
            }
            fun list(k: String) = m[k].orEmpty().split(',').filter { it.isNotBlank() }
            return SaveData(
                money = m["money"]?.toLongOrNull() ?: 0,
                tech = list("tech").filter { it in TechTree.byId }.toSet(),
                milestones = list("ms").mapNotNull { n -> Milestone.entries.firstOrNull { it.name == n } }.toSet(),
                achievements = list("ach").mapNotNull { n -> Achievement.entries.firstOrNull { it.name == n } }.toSet(),
                bestAltitude = m["bestAlt"]?.toDoubleOrNull() ?: 0.0,
                bestSpeed = m["bestSpd"]?.toDoubleOrNull() ?: 0.0,
                flights = m["flights"]?.toIntOrNull() ?: 0,
                totalEarned = m["earned"]?.toLongOrNull() ?: 0,
                moonLandings = m["moon"]?.toIntOrNull() ?: 0,
                soundOn = m["sound"]?.toBooleanStrictOrNull() ?: true,
                tiltControl = m["tilt"]?.toBooleanStrictOrNull() ?: false,
                quality = (m["q"]?.toIntOrNull() ?: 1).coerceIn(0, 2),
            )
        }
    }
}

fun formatMoney(v: Long): String = "$" + "%,d".format(v)
