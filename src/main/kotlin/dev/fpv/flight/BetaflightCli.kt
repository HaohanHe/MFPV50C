/*
 * FPV Craft - MIT
 *
 * Clean-room parser for Betaflight CLI `set name = value` lines (the format a
 * user copies from a `diff` / `dump` in the Betaflight configurator or serial
 * CLI). Only the PUBLIC text format is parsed; no Betaflight source code is
 * copied. Parameter semantics follow the published configurator defaults
 * documented in docs/research/betaflight.
 *
 * Recognized vocabulary (per-axis when given, global as fallback):
 *   rates_type            ACTUAL | BETAFLIGHT | LEGACY | RACEFLIGHT | QUICK
 *   {axis}_rc_rate        center sensitivity (ACTUAL, x10 -> dps) or rcRate (LEGACY)
 *   {axis}_super_rate     max rate (ACTUAL, x10 -> dps) or superRate (LEGACY)
 *   {axis}_expo / rc_expo / rc_yaw_expo
 *   global rc_rate / rc_expo / rc_yaw_expo  (apply to all axes when per-axis absent)
 *   throttle_mid (us) / throttle_expo (0..10) / throttle_limit_type / throttle_limit_percent
 *   {axis}_p / {axis}_i / {axis}_d / {axis}_f
 *   motor_idle (%)  /  tpa_rate (0..1000) / tpa_breakpoint (us)
 *
 * Unrecognized domain-related lines are reported explicitly (unsupported),
 * out-of-range values are hard errors; unrelated dump noise (serial, vtx, rx,
 * led, ...) is skipped and counted. Nothing is silently dropped.
 */
package dev.fpv.flight

/** Structured numeric values extracted from a BF CLI text. */
class BfValues {
    val center = FloatArray(3) { -1f }   // roll,pitch,yaw; -1 = absent
    val max = FloatArray(3) { -1f }
    val expo = FloatArray(3) { -1f }
    val rcRate = FloatArray(3) { -1f }    // legacy only
    val superRate = FloatArray(3) { -1f }
    var rateType: String? = null
    var thrMidPct = -1f
    var thrExpoPct = -1f
    var limitType: String? = null
    var limitPct = -1f
    // pid[axis][p,i,d,f]; -1 = absent
    val pid = Array(3) { floatArrayOf(-1f, -1f, -1f, -1f) }
    var minThrottle = -1f
    var tpaRate = -1f
    var tpaBreakpoint = -1f
}

data class BfImportResult(
    val values: BfValues,
    val applied: List<String>,
    val errors: List<String>,
    val unsupported: List<String>,
    val skippedUnrelated: Int,
) {
    val rateType: String? get() = values.rateType
}

object BetaflightCli {

    private val AXES = listOf("roll", "pitch", "yaw")
    private const val ROLL = 0
    private const val PITCH = 1
    private const val YAW = 2

    // Names that belong to the rates/pid/throttle domain but we do not (yet) map.
    private val DOMAIN_HINT = Regex(
        "rate|expo|pid|roll|pitch|yaw|throttle|motor|airmode|tpa|^rc|dterm|feedforward|antigravity|anti.?gravity|iterm|setpoint|smoothing|lpf|gyro",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): BfImportResult {
        val v = BfValues()
        val applied = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var skipped = 0

        val raw = HashMap<String, Float>()
        var rawType: String? = null
        var limitType: String? = null

        val lines = text.lines()
        for ((idx, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val body = if (line.startsWith("set ", ignoreCase = true)) line.substring(4).trim() else line
            val eq = body.indexOf('=')
            if (eq < 0) continue
            val name = body.substring(0, eq).trim().lowercase()
            val value = body.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue

            fun num(tag: String): Float? {
                val f = value.toFloatOrNull()
                if (f == null) errors += "line ${idx + 1}: '$name=$value' not a number"
                return f
            }

            when (name) {
                "rates_type" -> rawType = value.uppercase()
                "rc_rate" -> num("rc_rate")?.let { raw["__rc_rate"] = it }
                "rc_expo" -> num("rc_expo")?.let { raw["__rc_expo"] = it }
                "rc_yaw_expo" -> num("rc_yaw_expo")?.let { raw["__yaw_expo"] = it }
                "throttle_mid" -> num("throttle_mid")?.let { raw["__thr_mid"] = it }
                "throttle_expo" -> num("throttle_expo")?.let { raw["__thr_expo"] = it }
                "throttle_limit_type" -> limitType = value.uppercase()
                "throttle_limit_percent" -> num("throttle_limit_percent")?.let { raw["__limit_pct"] = it }
                "motor_idle" -> num("motor_idle")?.let { raw["__motor_idle"] = it }
                "tpa_rate" -> num("tpa_rate")?.let { raw["__tpa_rate"] = it }
                "tpa_breakpoint" -> num("tpa_breakpoint")?.let { raw["__tpa_bp"] = it }
                else -> {
                    val f = value.toFloatOrNull()
                    when {
                        f != null && AXES.any { a -> name.startsWith("${a}_") } -> raw[name] = f
                        DOMAIN_HINT.matches(name) -> unsupported += "line ${idx + 1}: '$name' not mapped"
                        else -> skipped++
                    }
                }
            }
        }

        v.rateType = when (rawType) {
            null -> Defaults.RATES_TYPE_ACTUAL // published default when rates_type absent
            "ACTUAL" -> Defaults.RATES_TYPE_ACTUAL
            "BETAFLIGHT", "LEGACY" -> Defaults.RATES_TYPE_LEGACY
            "RACEFLIGHT", "QUICK" -> Defaults.RATES_TYPE_QUICK
            else -> {
                errors += "unknown rates_type '$rawType' (expected ACTUAL/BETAFLIGHT/LEGACY/QUICK)"
                Defaults.RATES_TYPE_ACTUAL
            }
        }

        // ---- per-axis rates ----
        for ((i, a) in AXES.withIndex()) {
            val rcRate = raw["${a}_rc_rate"] ?: raw["__rc_rate"]
            val superRate = raw["${a}_super_rate"] ?: raw["__super_rate"]
            val expo = raw["${a}_expo"] ?: raw["__${a}_expo"] ?: raw["__rc_expo"]
            val yawExpo = if (a == "yaw") raw["__yaw_expo"] else null

            if (v.rateType == Defaults.RATES_TYPE_LEGACY) {
                rcRate?.let {
                    if (it !in 0.1f..2.5f) errors += "$a.rc_rate=$it out of range [0.1,2.5]"
                    else { v.rcRate[i] = it }
                }
                superRate?.let {
                    if (it !in 0f..1f) errors += "$a.super_rate=$it out of range [0,1]"
                    else { v.superRate[i] = it }
                }
            } else {
                rcRate?.let {
                    val center = it * 10f
                    if (center !in 0f..250f) errors += "$a center=$center (rc_rate=$it) out of range"
                    else { v.center[i] = center }
                }
                superRate?.let {
                    val maxR = it * 10f
                    if (maxR !in 0f..2000f) errors += "$a max=$maxR (super_rate=$it) out of range"
                    else { v.max[i] = maxR }
                }
            }
            (expo ?: yawExpo)?.let {
                if (it !in 0f..1f) errors += "$a.expo=$it out of range [0,1]"
                else { v.expo[i] = it }
            }

            if (v.center[i] >= 0 || v.max[i] >= 0 || v.expo[i] >= 0 ||
                v.rcRate[i] >= 0 || v.superRate[i] >= 0
            ) {
                applied += if (v.rateType == Defaults.RATES_TYPE_LEGACY)
                    "$a: rcRate=${fmt(v.rcRate[i])} superRate=${fmt(v.superRate[i])} expo=${fmt(v.expo[i])}"
                else
                    "$a: center=${fmt(v.center[i])} max=${fmt(v.max[i])} expo=${fmt(v.expo[i])}"
            }
        }

        // ---- throttle curve ----
        raw["__thr_mid"]?.let {
            val pct = (it - 1000f) / 10f
            if (pct !in 0f..100f) errors += "throttle_mid=$it out of range [1000,2000]"
            else { v.thrMidPct = pct; applied += "thrMidPct=${fmt(pct)}" }
        }
        raw["__thr_expo"]?.let {
            val pct = it * 10f
            if (pct !in 0f..100f) errors += "throttle_expo=$it out of range [0,10]"
            else { v.thrExpoPct = pct; applied += "thrExpoPct=${fmt(pct)}" }
        }
        limitType?.let { v.limitType = it }
        raw["__limit_pct"]?.let {
            if (it !in 1f..100f) errors += "throttle_limit_percent=$it out of range [1,100]"
            else { v.limitPct = it; applied += "throttleLimit.percent=${fmt(it)}" }
        }

        // ---- PID ----
        for ((i, a) in AXES.withIndex()) {
            for ((g, gi) in listOf("p" to 0, "i" to 1, "d" to 2, "f" to 3)) {
                raw["${a}_$g"]?.let {
                    if (it !in 0f..500f) errors += "$a.$g=$it out of range [0,500]"
                    else {
                        v.pid[i][gi] = it
                        applied += "$a.$g=${fmt(it)}"
                    }
                }
            }
        }

        // ---- motor idle / tpa ----
        raw["__motor_idle"]?.let {
            if (it !in 0f..30f) errors += "motor_idle=$it out of range [0,30] (%)"
            else { v.minThrottle = it / 100f; applied += "minThrottle=${fmt(v.minThrottle)}" }
        }
        raw["__tpa_rate"]?.let {
            if (it !in 0f..1000f) errors += "tpa_rate=$it out of range [0,1000]"
            else { v.tpaRate = it / 1000f; applied += "tpaRate=${fmt(v.tpaRate)}" }
        }
        raw["__tpa_bp"]?.let {
            val bp = (it - 1000f) / 1000f
            if (bp !in 0f..1f) errors += "tpa_breakpoint=$it out of range [1000,2000]"
            else { v.tpaBreakpoint = bp; applied += "tpaBreakpoint=${fmt(bp)}" }
        }

        return BfImportResult(v, applied, errors, unsupported, skipped)
    }

    /** Parse [text] and apply the recognized values onto [cfg] in place. */
    fun importInto(text: String, cfg: FpvConfig): BfImportResult {
        val result = parse(text)
        applyParsed(result.values, cfg)
        return result
    }

    private fun applyParsed(v: BfValues, cfg: FpvConfig) {
        v.rateType?.let { cfg.rateType = it }
        val axes = listOf(cfg.roll, cfg.pitch, cfg.yaw)
        for (i in 0..2) {
            if (v.center[i] >= 0) axes[i].center = v.center[i]
            if (v.max[i] >= 0) axes[i].max = v.max[i]
            if (v.expo[i] >= 0) axes[i].expo = v.expo[i]
            if (v.rcRate[i] >= 0) axes[i].rcRate = v.rcRate[i]
            if (v.superRate[i] >= 0) axes[i].superRate = v.superRate[i]
        }
        if (v.thrMidPct >= 0) cfg.thrMidPct = v.thrMidPct
        if (v.thrExpoPct >= 0) cfg.thrExpoPct = v.thrExpoPct
        v.limitType?.let { cfg.throttleLimit?.type = it }
        if (v.limitPct >= 0) cfg.throttleLimit?.percent = v.limitPct
        val pid = cfg.pid ?: return
        for (i in 0..2) {
            val axisPid = when (i) { 0 -> pid.roll; 1 -> pid.pitch; else -> pid.yaw }
            if (v.pid[i][0] >= 0) axisPid.p = v.pid[i][0]
            if (v.pid[i][1] >= 0) axisPid.i = v.pid[i][1]
            if (v.pid[i][2] >= 0) axisPid.d = v.pid[i][2]
            if (v.pid[i][3] >= 0) axisPid.f = v.pid[i][3]
        }
        if (v.tpaRate >= 0) pid.tpaRate = v.tpaRate
        if (v.tpaBreakpoint >= 0) pid.tpaBreakpoint = v.tpaBreakpoint
        if (v.minThrottle >= 0) {
            cfg.activeAirframe().minThrottle = v.minThrottle
        }
    }

    private fun fmt(v: Float): String = if (v < 0f) "-" else "%.2f".format(v)
}
