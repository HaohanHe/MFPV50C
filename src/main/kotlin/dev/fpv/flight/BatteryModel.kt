/*
 * FPV Craft - MIT
 * Virtual on-board battery. There is no real voltage divider: the pack voltage
 * is a model driven by commanded throttle.
 *
 *  - Open-circuit voltage drifts down as charge is consumed (only when a real
 *    capacity is configured; otherwise the pack stays nominally full and the
 *    percentage is estimated linearly from per-cell voltage).
 *  - Under load the terminal voltage sags (more throttle -> more drop); on
 *    release the sag relaxes slowly ("松杆缓慢回升").
 *  - mAhDrawn integrates current = k * |throttle| over time.
 *
 * Warning / critical thresholds are the published per-cell values 3.50 / 3.30.
 */
package dev.fpv.flight

enum class BatteryStage { OK, WARNING, CRITICAL }

class BatteryModel(private val cfg: FpvConfig) {

    /** Terminal pack voltage, virtual volts. */
    var vbat: Float = 0f
        private set

    /** Consumed charge, mAh. */
    var mAhDrawn: Float = 0f
        private set

    var stage: BatteryStage = BatteryStage.OK
        private set

    private var sagV: Float = 0f

    init {
        vbat = cells() * Defaults.VBAT_FULL_CELL
    }

    /** Effective series cells: airframe S override if set, else the pack. */
    private fun cells(): Int = cfg.activeAirframe().effectiveCellCount(cfg.activeBattery().cellCount)

    /** Full pack voltage from the configured cell count. */
    private fun fullPackV(): Float = cells() * Defaults.VBAT_FULL_CELL

    private fun emptyPackV(): Float =
        cells() * cfg.activeBattery().criticalCellV

    /** State of charge 0..1, from capacity when configured, else from voltage. */
    fun percent(): Float {
        val b = cfg.activeBattery()
        if (b.packCapacityMah > 0) {
            return (1f - mAhDrawn / b.packCapacityMah) * 100f
        }
        // Voltage-only linear estimate between critical and full per-cell.
        val perCell = vbat / cells()
        val span = Defaults.VBAT_FULL_CELL - b.criticalCellV
        if (span <= 0f) return 0f
        return ((perCell - b.criticalCellV) / span * 100f).coerceIn(0f, 100f)
    }

    fun perCell(): Float = vbat / cells()

    /**
     * Advance the model one frame.
     *
     * @param throttle commanded throttle 0..1 (normal mode) or -1..1 (3D)
     * @param dt       frame seconds
     */
    fun update(throttle: Float, dt: Float) {
        val b = cfg.activeBattery()
        val demand = kotlin.math.abs(throttle)

        // mAh integration: I = k * demand, mAh = integral(I dt / 3600).
        val currentA = Defaults.CURRENT_AT_FULL_THROTTLE_A * demand
        mAhDrawn += currentA * dt / 3600f * 1000f

        // Open-circuit voltage follows consumed charge only when capacity known.
        val ocv = if (b.packCapacityMah > 0) {
            val usedFrac = (mAhDrawn / b.packCapacityMah).coerceIn(0f, 1f)
            emptyPackV() + (fullPackV() - emptyPackV()) * (1f - usedFrac)
        } else {
            fullPackV()
        }

        // Sag: snap toward demand*k on load, relax slowly on release.
        val targetSag = Defaults.BATTERY_SAG_V * demand
        sagV = if (targetSag >= sagV) {
            targetSag // load: fast sag
        } else {
            // release: first-order recovery with a slow time constant
            val a = (dt / (Defaults.BATTERY_RECOVERY_TAU + dt)).coerceIn(0f, 1f)
            sagV + a * (targetSag - sagV)
        }

        vbat = (ocv - sagV).coerceAtLeast(0f)

        // Stage from per-cell voltage.
        val pc = perCell()
        stage = when {
            pc <= b.criticalCellV -> BatteryStage.CRITICAL
            pc <= b.warningCellV -> BatteryStage.WARNING
            else -> BatteryStage.OK
        }
    }

    fun reset() {
        mAhDrawn = 0f
        sagV = 0f
        vbat = fullPackV()
        stage = BatteryStage.OK
    }
}
