/*
 * FPV Craft - MIT
 * Real-time derived performance for an AirframeProfile. Every number here is
 * a closed-form function of the user-edited hardware + physics fields, so
 * changing mass / Kv / prop / S / drag moves the displayed performance bars.
 *
 * Model basis (clean-room, open textbooks):
 *  - No-load motor rpm: n0 = Kv * Vnom  (Vnom = S * nominal cell 3.7V), derated
 *    by a load factor (beta) because a loaded prop spins slower than the no-load
 *    Kv rating. beta is an engineering estimate, NOT measured.
 *  - Static thrust per motor: momentum-theory form T = Ct * rho * n^2 * D^4.
 *    Ct is an empirical thrust coefficient folded from prop diameter/pitch;
 *    it is a tuning constant, not a leaf-element integration.
 *  - Top speed: level flight thrust equals parasitic drag
 *    0.5*rho*Cd*A*v^2 = T_eff  ->  v = sqrt(2*T_eff/(rho*Cd*A)).
 *  - Aerodynamics index: a transparent dimensionless efficiency ratio.
 *
 * None of the constants below are copied from any closed-source product; the
 * reference values (Ct, beta, Cd*A_ref) are engineering defaults flagged for
 * real-world measurement.
 */
package dev.fpv.flight

import kotlin.math.sqrt

object AirframeDerivation {

    /** Air density, kg/m^2 sea level ISA. */
    const val RHO = 1.225f

    /** LiPo nominal cell voltage, V (fully-charged peak is separate). */
    const val NOMINAL_CELL_V = 3.7f

    /**
     * Load rpm factor: loaded prop rpm = no-load rpm * beta. Engineering
     * starting value (~85%); real motors load to 75-90% depending on prop.
     */
    const val BETA_LOAD = 0.85f

    /**
     * Empirical static-thrust coefficient (momentum-theory fit for a 5-6"
     * prop). T = Ct * rho * n^2 * D^4, n in rev/s, D in m. Calibrated so a
     * 5.1" / 1900Kv / 4S prop delivers ~1.5 N per motor at loaded rpm; this
     * is a tunable starting fit, NOT a leaf-element integration. Pitch biases
     * it via [ctForPitch].
     */
    const val CT_BASE = 0.028f

    /**
     * Reference drag area Cd*A (m^2) that airDrag=1.0 maps onto. Engineering
     * fit value (typical 5" ~0.015-0.020 m^2), NOT measured; scale by bench
     * top-speed / decel tuning.
     */
    const val CD_A_REF = 0.018f

    /** Inch -> metre. */
    private const val INCH_M = 0.0254f

    /** Pack nominal voltage, V. */
    fun nominalPackV(af: AirframeProfile): Float =
        af.effectiveCellCount(4) * NOMINAL_CELL_V

    /** No-load motor rpm = Kv * Vnom. */
    fun noLoadRpm(af: AirframeProfile): Float = af.motorKv * nominalPackV(af)

    /** Loaded (on-prop) shaft speed, rev/s. */
    fun loadedRevPerSec(af: AirframeProfile): Float =
        noLoadRpm(af) / 60f * BETA_LOAD

    /**
     * Thrust coefficient biased by prop pitch (coarser pitch -> more thrust
     * for the same rpm). Linear around the 4.6" reference; engineering.
     */
    fun ctForPitch(af: AirframeProfile): Float =
        CT_BASE * (0.7f + 0.3f * (af.propPitch / 4.6f))

    /** Static thrust of ONE prop at loaded speed, Newtons. */
    fun staticThrustPerMotorN(af: AirframeProfile): Float {
        val d = af.propInch * INCH_M
        val n = loadedRevPerSec(af)
        return ctForPitch(af) * RHO * n * n * d * d * d * d
    }

    /** Total static thrust converted to kgf (kg-force), displayed "Thrust". */
    fun totalThrustKg(af: AirframeProfile): Float =
        af.motorCount * staticThrustPerMotorN(af) / 9.81f

    /** Drag area Cd*A (m^2) implied by the airDrag knob + relative airspeed. */
    fun dragArea(af: AirframeProfile): Float =
        CD_A_REF * af.airDrag * af.relativeAirspeed

    /**
     * Level-flight top speed, km/h. Equates full-thrust horizontal component to
     * parasitic drag; uses the *derived* static thrust (consistent with the
     * thrust bar) rather than maxThrustPerMotorN.
     */
    fun topSpeedKmh(af: AirframeProfile): Float {
        val tEff = af.motorCount * staticThrustPerMotorN(af) // N
        val cda = dragArea(af).coerceAtLeast(1e-4f)
        val vMps = sqrt((2f * tEff / (RHO * cda)).toDouble()).toFloat()
        return vMps * 3.6f
    }

    /**
     * Transparent dimensionless aerodynamics index: how hard the craft cuts
     * through the air relative to the reference rig, scaled by grip.
     *   aero = (Cd*A_ref / (Cd*A)) * airGrip
     * Higher = cleaner aerodynamic hold for a given drag-area setting.
     */
    fun aeroIndex(af: AirframeProfile): Float {
        val cda = dragArea(af).coerceAtLeast(1e-4f)
        return (CD_A_REF / cda) * af.airGrip
    }
}
