/*
 * FPV Craft - MIT
 *
 * Emergent aerobatics harness. It drives the REAL flight controller (stick setpoint ->
 * PID -> motor lag -> torque -> rigid-body integration) AND the translational dynamics
 * (thrust along the tilted disc + gravity + anisotropic drag) in a single closed loop,
 * exactly like the LivingEntityMixin does at runtime.
 *
 * There is NO scripted trajectory: the caller supplies only a time-series of STICK
 * inputs and an initial velocity; every attitude, translation and energy change emerges
 * from the coupled physics. The trace below records the sampled state so headless can
 * measure *how much* the environment (gravity / momentum / drag / initial speed)
 * contributed versus the pilot's sticks.
 */
package dev.fpv.flight

import dev.fpv.input.StickChannels
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.hypot

class Aerobatics(private val cfg: FpvConfig) {

    /** One pilot input sample: stick deflections + collective throttle. */
    data class Frame(
        val t: Double, val roll: Float, val pitch: Float, val yaw: Float, val throttle: Float,
    )

    /** One recorded state sample. Angles are unwrapped integrated body rates (deg). */
    data class Sample(
        val t: Double,
        val rollAccumDeg: Float, val pitchAccumDeg: Float, val yawAccumDeg: Float,
        val bodyUpY: Float,          // 1 = level, -1 = fully inverted
        val vy: Double, val horizSpeed: Double,
        val altY: Double,
        val bodyRateMagDps: Float,
        val stickRoll: Float,
    )

    private val td = TranslationalDynamics(cfg.activeAirframe())
    private val tickSec = 0.05f // MC physics tick; td.step() returns per-tick deltas.

    data class Trace(
        val samples: List<Sample>,
        val scriptEndT: Double,
        val startVy: Double,
        val startHoriz: Double,
        val startSpeed: Double,
        val endVy: Double,
        val endHoriz: Double,
        val endSpeed: Double,
        val nan: Boolean,
    )

    /**
     * Run [script] (stick inputs only) closed-loop. After the last script frame the
     * sticks return to neutral for [extraSec] so the pilot can measure what gravity /
     * momentum / drag do on their own.
     */
    fun run(
        script: List<Frame>, dt: Float,
        initVx: Double, initVy: Double, initVz: Double,
        extraSec: Double,
    ): Trace {
        require(script.size >= 2) { "aerobatic script needs >=2 frames" }
        val fc = FlightController(cfg)
        fc.engage(0f, 0f)
        val hover = cfg.activeAirframe().effectiveHoverThrottle()
        // Brief warm-up spool so the motors are live before the maneuver.
        val warm = StickChannels(0f, 0f, 0f, hover, FloatArray(0), true, "aerobatics")
        val warmSteps = (0.3 / dt).toInt()
        for (i in 0 until warmSteps) fc.step(warm, dt, hover)

        var vx = initVx; var vy = initVy; var vz = initVz
        var px = 0.0; var py = 0.0; var pz = 0.0
        var rollAcc = 0f; var pitchAcc = 0f; var yawAcc = 0f
        var nan = false
        val samples = ArrayList<Sample>(2048)
        val ch = StickChannels(0f, 0f, 0f, hover, FloatArray(0), true, "aerobatics")
        val rev = cfg.reversible3D
        val db = cfg.threeDThrottleDeadband

        val scriptEnd = script.last().t
        val total = scriptEnd + extraSec
        var t = 0.0
        val scale = dt / tickSec // td.step is per MC tick; fold into our step rate.
        while (t < total) {
            val fr = sampleAt(script, t)
            val free = t >= scriptEnd
            ch.roll = if (free) 0f else fr.roll
            ch.pitch = if (free) 0f else fr.pitch
            ch.yaw = if (free) 0f else fr.yaw
            // In the free phase keep the *last* collective so gravity still has a disc to push;
            // the pilot let go of attitude sticks, not necessarily the throttle.
            ch.throttle = fr.throttle
            fc.step(ch, dt, fr.throttle)

            // Closed translational coupling: thrust follows the tilted disc; gravity + drag act.
            val acc = td.step(fc.attitude, fr.throttle, vx, vy, vz, 100f, 1f, rev, db)
            vx += acc.x().toDouble() * scale
            vy += acc.y().toDouble() * scale
            vz += acc.z().toDouble() * scale
            px += vx * dt; py += vy * dt; pz += vz * dt
            fc.setTranslationState(vy.toFloat(), hypot(vx, vz).toFloat())

            val br = fc.bodyRates
            pitchAcc += br[0] * dt; rollAcc += br[1] * dt; yawAcc += br[2] * dt
            val up = Vector3f(0f, 1f, 0f).rotate(Quaternionf(fc.attitude).invert())
            if (!fc.attitude.isFinite || br.any { !it.isFinite() } ||
                !vx.isFinite() || !vy.isFinite() || !vz.isFinite() || !up.isFinite
            ) nan = true

            samples.add(
                Sample(
                    t, rollAcc, pitchAcc, yawAcc, up.y,
                    vy, hypot(vx, vz), py,
                    kotlin.math.sqrt(br[0] * br[0] + br[1] * br[1] + br[2] * br[2]),
                    ch.roll,
                ),
            )
            t += dt
        }
        val last = samples.last()
        val startSpeed = kotlin.math.sqrt(initVx * initVx + initVy * initVy + initVz * initVz)
        return Trace(
            samples, scriptEnd, initVy, hypot(initVx, initVz), startSpeed,
            last.vy, last.horizSpeed, hypot(last.vy, last.horizSpeed), nan,
        )
    }

    private fun sampleAt(script: List<Frame>, t: Double): Frame {
        if (t <= script.first().t) return script.first()
        for (i in 1 until script.size) {
            if (t <= script[i].t) {
                val a = script[i - 1]; val b = script[i]
                val f = ((t - a.t) / (b.t - a.t)).toFloat()
                return Frame(
                    t,
                    a.roll + (b.roll - a.roll) * f,
                    a.pitch + (b.pitch - a.pitch) * f,
                    a.yaw + (b.yaw - a.yaw) * f,
                    a.throttle + (b.throttle - a.throttle) * f,
                )
            }
        }
        return script.last()
    }
}
