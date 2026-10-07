package com.brandonwallace.rocketlaunch.v2.game

import kotlin.math.PI
import kotlin.math.ln

class StageSpec(
    val dryMass: Double,
    val fuelMass: Double,
    val thrust: Double,   // N
    val isp: Double,      // s
    val length: Float,    // m (visual)
    val radius: Float,    // m (visual)
)

/** Everything about the rocket that derives from the researched tech. */
class RocketSpec(val tech: Set<String>) {
    private val lv = TechTree.levels(tech).let { m -> Stat.entries.associateWith { (m[it] ?: 0).coerceIn(0, it.max) } }
    fun level(t: Stat) = lv.getValue(t)
    fun has(id: String) = id in tech

    companion object {
        val THRUST_KN = doubleArrayOf(16.0, 26.0, 42.0, 68.0, 110.0, 180.0, 300.0)
        val ISP = doubleArrayOf(200.0, 222.0, 246.0, 272.0, 298.0, 325.0, 352.0)
        val FUEL_KG = doubleArrayOf(90.0, 190.0, 400.0, 820.0, 1650.0, 3200.0, 6200.0)
        val CD = doubleArrayOf(0.85, 0.66, 0.52, 0.42, 0.34, 0.27)
        val HULL = doubleArrayOf(100.0, 160.0, 240.0, 340.0, 470.0, 650.0)
        val HEAT_TOL = doubleArrayOf(1.0, 2.0, 3.6, 6.5)
        val MAX_TILT_DEG = floatArrayOf(14f, 18f, 23f, 28f, 34f)
        val STEER_RESP = floatArrayOf(2.2f, 3.0f, 4.0f, 5.2f, 6.5f)
        val UPPER_FUEL_FRAC = doubleArrayOf(0.70, 0.46, 0.30)
        val UPPER_THRUST_FRAC = doubleArrayOf(0.85, 0.6, 0.4)
        const val RADIUS = 0.85f
        const val PAYLOAD_KG = 220.0
        const val VAC2_BONUS = 20.0
        /** Fraction of the last stage's fuel needed on arrival to land instead of crash. */
        const val LANDING_FUEL_FRAC = 0.04
    }

    val thrust1 = THRUST_KN[level(Stat.ENGINE)] * 1000.0
    val isp1 = ISP[level(Stat.ENGINE)] + 8.0 * level(Stat.REGEN)
    /** Vacuum nozzle: +14 s per level; the niobium extension (level 2) adds another 20 s — the landing margin. */
    val upperIsp = isp1 + 42.0 + 14.0 * level(Stat.VACUUM) + (if (level(Stat.VACUUM) >= 2) VAC2_BONUS else 0.0)
    val fuel1 = FUEL_KG[level(Stat.FUEL)]

    val stages: List<StageSpec> = buildList {
        val len1 = 3.2f + 0.85f * level(Stat.FUEL)
        add(StageSpec(dryMass(fuel1, thrust1, 230.0), fuel1, thrust1, isp1, len1, RADIUS))
        for (k in 0 until level(Stat.STAGES)) {
            val f = fuel1 * UPPER_FUEL_FRAC[k]
            val t = thrust1 * UPPER_THRUST_FRAC[k]
            add(StageSpec(dryMass(f, t, 45.0, 0.06, 0.8), f, t, upperIsp, len1 * (0.75f - 0.12f * k), RADIUS * (0.96f - 0.06f * k)))
        }
    }

    val boosterCount: Int = when (level(Stat.BOOSTERS)) { 0 -> 0; 3 -> 4; else -> 2 }
    val booster: StageSpec? = if (boosterCount == 0) null else {
        val big = level(Stat.BOOSTERS) >= 2
        val f = fuel1 * (if (big) 0.42 else 0.26)
        val t = thrust1 * (if (big) 0.52 else 0.36)
        StageSpec(dryMass(f, t, 35.0), f, t, isp1 - 15.0, stages[0].length * (if (big) 0.85f else 0.68f), if (big) 0.46f else 0.38f)
    }

    val payloadMass = PAYLOAD_KG + 35.0 * level(Stat.HULL) + 30.0 * level(Stat.HEAT)
    val cd = CD[level(Stat.AERO)]
    val area = PI * RADIUS * RADIUS + boosterCount * 0.28
    val maxHull = HULL[level(Stat.HULL)]
    val heatTolerance = HEAT_TOL[level(Stat.HEAT)]
    val maxTiltRad = Math.toRadians(MAX_TILT_DEG[level(Stat.FINS)].toDouble() + 3.0 * level(Stat.AVIONICS).coerceAtMost(2))
    val steerResponse = STEER_RESP[level(Stat.FINS)] * (1f + 0.2f * level(Stat.AVIONICS))
    /** Digital flight computer throttles back through max-Q heating. */
    val autoThrottle = level(Stat.AVIONICS) >= 3
    /** The flight computer also cuts the engines once the coast reaches the Moon, keeping the landing reserve. */
    val autoCut = level(Stat.AVIONICS) >= 3
    val hazardRadar = level(Stat.AVIONICS) >= 1
    val separationTime = if (level(Stat.SEP) > 0) 0.15 else 0.45
    val engineCount: Int = when { has("P_FFSC") -> 9; has("P_STAGED") -> 3; else -> 1 }

    private fun dryMass(fuel: Double, thrustN: Double, base: Double, frac: Double = 0.085, perKn: Double = 1.6) = base + frac * fuel + perKn * thrustN / 1000.0

    fun liftoffMass(): Double =
        payloadMass + stages.sumOf { it.dryMass + it.fuelMass } + boosterCount * ((booster?.dryMass ?: 0.0) + (booster?.fuelMass ?: 0.0))

    fun liftoffTwr(): Double =
        (thrust1 + boosterCount * (booster?.thrust ?: 0.0)) / (liftoffMass() * Physics.G0)

    /** Ideal (no gravity/drag) delta-v, boosters approximated as burning with stage 1. */
    fun idealDeltaV(): Double {
        var m = liftoffMass()
        var dv = 0.0
        val b = booster
        if (b != null) {
            val flowB = boosterCount * b.thrust / (b.isp * Physics.G0)
            val flow1 = stages[0].thrust / (stages[0].isp * Physics.G0)
            val tb = b.fuelMass / (b.thrust / (b.isp * Physics.G0))
            val burned = flowB * tb + flow1 * tb
            val ispEff = (boosterCount * b.thrust + stages[0].thrust) / ((flowB + flow1) * Physics.G0)
            dv += ispEff * Physics.G0 * ln(m / (m - burned))
            m -= burned + boosterCount * b.dryMass
            val left = stages[0].fuelMass - flow1 * tb
            dv += stages[0].isp * Physics.G0 * ln(m / (m - left))
            m -= left + stages[0].dryMass
            for (s in stages.drop(1)) { dv += s.isp * Physics.G0 * ln(m / (m - s.fuelMass)); m -= s.fuelMass + s.dryMass }
        } else {
            for (s in stages) { dv += s.isp * Physics.G0 * ln(m / (m - s.fuelMass)); m -= s.fuelMass + s.dryMass }
        }
        return dv
    }

}
