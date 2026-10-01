/*
 * FPV Craft - MIT
 * Pure beep scheduling: continuous alarms (RX_LOST / BAT_LOW / BAT_CRIT) are edge-armed and
 * throttled by a minimum repeat interval, and only sound while armed. Disarmed state is fully
 * silent except ARM/DISARM transition beeps. No MC types here so headless can pin the throttling.
 */
package dev.fpv.flight

object BeepScheduler {

    /**
     * Decide which continuous alarm (if any) may beep this tick.
     * @return the alarm to play, or NONE. Throttled: at most one per its interval; disarmed -> NONE.
     */
    fun continuousAlarm(
        nowMs: Long, armed: Boolean, rxFail: Boolean, cellV: Float,
        lastRepeatMs: Long,
    ): MotorTone.Beep {
        if (!armed) return MotorTone.Beep.NONE           // disarmed = silent (except transitions)
        val ev = when {
            rxFail -> MotorTone.Beep.RX_LOST
            cellV < 3.3f -> MotorTone.Beep.BAT_CRIT
            cellV < 3.6f -> MotorTone.Beep.BAT_LOW
            else -> MotorTone.Beep.NONE
        }
        val interval = when (ev) {
            MotorTone.Beep.RX_LOST -> Defaults.RX_LOST_BEEP_INTERVAL_MS
            MotorTone.Beep.BAT_CRIT -> Defaults.BAT_CRIT_BEEP_INTERVAL_MS
            MotorTone.Beep.BAT_LOW -> Defaults.BAT_LOW_BEEP_INTERVAL_MS
            else -> 0L
        }
        if (ev == MotorTone.Beep.NONE) return MotorTone.Beep.NONE
        return if (nowMs - lastRepeatMs >= interval) ev else MotorTone.Beep.NONE
    }
}
