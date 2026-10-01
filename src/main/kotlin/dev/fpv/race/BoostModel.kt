/*
 * FPV Craft - MIT
 * Pure client-side boost thrust envelope. A BOOST gate opens a short window during which the
 * local translational thrust is scaled. No packets/entities; on a remote server this is a no-op
 * unless applied only to the client's own predicted motion.
 */
package dev.fpv.race

object BoostModel {
    /** Thrust multiplier while a boost window is open. */
    fun thrustScale(boostActive: Boolean): Double =
        if (boostActive) BOOST_MULTIPLIER + 0.0 else 1.0
}
