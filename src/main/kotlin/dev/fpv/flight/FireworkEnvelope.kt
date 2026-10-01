/*
 * FPV Craft - MIT
 *
 * Data-driven model of the vanilla firework-rocket boost envelope on a 1.21.11
 * elytra glide. Every numeric anchor below was read out of the obfuscated
 * client jar with `javap -p -c` (NOT guessed / not from memory):
 *
 *  (a) onset: once an attached FireworkRocketEntity exists, its FIRST tick()
 *      applies the velocity relax (life==0 branch runs before life++).
 *      Source: FireworkRocketEntity.tick() offsets 47-174 (relax) then 420-432
 *      (life++). -> entity-side onset = FW_ONSET_TICKS.
 *
 *  (b) per-tick thrust: while attached && isFallFlying, the entity does
 *        attachedTo.setDeltaMovement(
 *            old + look*0.1 + (look*1.5 - old)*0.5 )
 *      which simplifies to  vel <- 0.5*vel + 0.85*look.
 *      Steady-state target = 0.85/(1-0.5) = 1.7 blocks/tick along `look`.
 *      Source: FireworkRocketEntity.tick() offsets 65-174 (constants 1.5 / 0.1 / 0.5).
 *
 *  (c) duration: lifetime = 10*power + rand(6) + rand(7), where power = 1 plus
 *      the item's Fireworks.flightDuration() (absent component => power=1).
 *      For a plain rocket (power=1): 10 + [0,5] + [0,6] = 10..21 ticks, mean 15.5.
 *      The relax runs every tick of that lifetime (no life-dependence in the
 *      attached branch; magnitude is constant while burning).
 *      Source: FireworkRocketEntity 5-arg ctor offsets 89-141; explode gate
 *      at tick() offsets 510-543 (life > lifetime).
 *
 * This is a clean-room Kotlin re-implementation of that observed behaviour; it
 * carries no Minecraft types so the beat/envelope can be unit-tested on a plain
 * JVM and used both by ServerCompatLogic (to derive a continuous beat) and by the
 * headless closed-loop check (to reproduce / prove out the shake).
 */
package dev.fpv.flight

import kotlin.random.Random

class FireworkEnvelope(
    /** Nominal rocket power (1 = plain single-gunpowder rocket). */
    val power: Int = Defaults.FW_POWER_DEFAULT,
) {
    /** Mean expected lifetime in ticks for this power (deterministic for reproducibility). */
    fun expectedLifetimeTicks(): Int {
        // 10*power + mean(rand(6)) + mean(rand(7)) = 10*power + 2.5 + 3.0
        return (Defaults.FW_LIFETIME_BASE * power
            + Defaults.FW_LIFETIME_RAND_A / 2
            + (Defaults.FW_LIFETIME_RAND_B - 1) / 2)
    }

    /**
     * Recommended ignite interval (ticks) so the fleet stays continuously covered
     * but does NOT over-saturate the velocity pin: interval = lifetime / overlap.
     * overlap>=1 guarantees no thrust gap; overlap~1.5 keeps ~1.5 rockets active.
     */
    fun recommendedIntervalTicks(overlap: Double = Defaults.FW_BEAT_OVERLAP): Int {
        val it = (expectedLifetimeTicks() / overlap).toInt()
        return it.coerceAtLeast(1)
    }

    /**
     * A simulated fleet of currently-burning rockets. Each tick every alive rocket
     * applies the vanilla relax along the (possibly wobbling) live look direction.
     * This is the plant the headless closed-loop check drives; the real server runs
     * the identical relax in FireworkRocketEntity.tick().
     */
    class Fleet(private val power: Int = Defaults.FW_POWER_DEFAULT, seed: Long = 0L) {
        private val rng = Random(seed)
        private val remaining = ArrayList<Int>()

        /** Ignite a new rocket; its random lifetime is drawn like the vanilla ctor. */
        fun ignite() {
            val life = Defaults.FW_LIFETIME_BASE * power +
                rng.nextInt(Defaults.FW_LIFETIME_RAND_A) +
                rng.nextInt(Defaults.FW_LIFETIME_RAND_B)
            remaining.add(life)
        }

        fun activeCount(): Int = remaining.size

        /** True if no rocket is currently applying thrust. */
        fun silent(): Boolean = remaining.isEmpty()

        /**
         * Advance one tick: apply every alive rocket's relax to the velocity, then
         * age the rockets and drop expired ones. Returns the new velocity.
         * @return {vx, vy, vz} after this tick's relax applications.
         */
        fun step(lookX: Double, lookY: Double, lookZ: Double,
                 vx: Double, vy: Double, vz: Double): DoubleArray {
            var x = vx; var y = vy; var z = vz
            val relax = Defaults.FW_RELAX
            val accel = Defaults.FW_ACCEL
            var i = 0
            while (i < remaining.size) {
                val life = remaining[i]
                // Vanilla relax: vel <- relax*vel + accel*look, applied per rocket.
                x = relax * x + accel * lookX
                y = relax * y + accel * lookY
                z = relax * z + accel * lookZ
                if (life <= 1) remaining.removeAt(i) else { remaining[i] = life - 1; i++ }
            }
            return doubleArrayOf(x, y, z)
        }
    }
}
