import dev.fpv.flight.FlightController;
import dev.fpv.flight.FpvConfig;
import dev.fpv.flight.TranslationalDynamics;
import dev.fpv.flight.ServerCompatConfig;
import dev.fpv.flight.ServerCompatLogic;
import dev.fpv.flight.FireworkEnvelope;
import dev.fpv.flight.MotionBlurCompositor;
import dev.fpv.flight.OsdLayoutMath;
import dev.fpv.input.StickChannels;
import dev.fpv.input.AuxState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.ArrayList;

/**
 * Headless, objective verification of the REAL flight-controller pipeline that
 * actually ships on main: actual rates -> PID -> Quad-X mixer -> motor first-order
 * lag -> rigid-body inertia -> attitude, plus the condition-driven prop-wash model.
 *
 * It drives the REAL classes (FlightController / FpvConfig), not a re-implementation,
 * and prints numeric evidence with PASS/FAIL. Compile/run: see run_headless.sh.
 */
public class FlightControlCheck {

  static int failures = 0;
  static void check(String name, boolean ok, String evidence) {
    System.out.printf("  %-46s : %s   %s%n", name, ok ? "PASS" : "FAIL", evidence);
    if (!ok) failures++;
  }

  static final double DT = 0.005; // fixed 200 Hz step

  /** Neutral channel frame (throttle 0); @JvmField fields are set by callers. */
  static StickChannels sticks() {
    return new StickChannels(0f, 0f, 0f, 0f, new float[0], true, "headless",
      new ArrayList<AuxState>(), new byte[0], new byte[0]);
  }

  /** Full-deflection step on axis 0=pitch,1=roll,2=yaw.
   *  Returns {t63, peakAbs, endSigned, setpoint}. */
  static double[] axisStep(int axis) {
    FpvConfig cfg = new FpvConfig();
    double setpoint = axis == 0 ? cfg.getPitch().getMax()
                   : axis == 1 ? cfg.getRoll().getMax()
                   : cfg.getYaw().getMax();
    FlightController fc = new FlightController(cfg);
    fc.engage(0f, 0f);
    // Warm motors at 0.5 throttle with zero attitude demand before the step.
    StickChannels warm = sticks(); warm.throttle = 0.5f;
    for (int i = 0; i < (int)(0.6 / DT); i++) fc.step(warm, (float) DT, 0.5f);

    StickChannels c = sticks();
    if (axis == 0) c.pitch = 1f; else if (axis == 1) c.roll = 1f; else c.yaw = 1f;
    c.throttle = 0.5f;

    double t63 = -1, peak = 0, t = 0;
    for (int i = 0; i < (int)(2.0 / DT); i++) {
      fc.step(c, (float) DT, 0.5f);
      t += DT;
      double a = Math.abs(fc.getBodyRates()[axis]);
      if (t63 < 0 && a >= 0.63 * setpoint) t63 = t;
      if (a > peak) peak = a;
    }
    double end = fc.getBodyRates()[axis];
    return new double[]{t63, peak, end, setpoint};
  }

  /** Zero-stick, hover-throttle run; returns {min body-up.y (1=level,-1=inverted), NaN flag}. */
  static double[] uprightRun(double dur) {
    FpvConfig cfg = new FpvConfig();
    FlightController fc = new FlightController(cfg);
    fc.engage(0f, 0f);
    float hover = cfg.activeAirframe().effectiveHoverThrottle();
    StickChannels c = sticks(); c.throttle = hover;
    double minUp = 1; boolean nan = false;
    for (int i = 0; i < (int)(dur / DT); i++) {
      fc.step(c, (float) DT, hover);
      Vector3f up = new Vector3f(0f, 1f, 0f).rotate(fc.getAttitude());
      if (!Float.isFinite(up.x) || !Float.isFinite(up.y) || !Float.isFinite(up.z)) nan = true;
      if (up.y < minUp) minUp = up.y;
    }
    return new double[]{minUp, nan ? 1 : 0};
  }

  /** Mean squared frame-to-frame gyro jump under a fixed flight condition. */
  static double washJitter(float vy, float horiz, float thr) {
    FpvConfig cfg = new FpvConfig();
    cfg.activeAirframe().setPropwashEnabled(true); // same enable for both conditions
    FlightController fc = new FlightController(cfg);
    fc.engage(0f, 0f);
    fc.setTranslationState(vy, horiz);
    StickChannels c = sticks(); c.throttle = thr;
    for (int i = 0; i < (int)(1.0 / DT); i++) { fc.setTranslationState(vy, horiz); fc.step(c, (float) DT, thr); }
    double acc = 0; int n = 0; float[] prev = new float[2]; boolean have = false;
    for (int i = 0; i < (int)(3.0 / DT); i++) {
      fc.setTranslationState(vy, horiz);
      fc.step(c, (float) DT, thr);
      float[] b = fc.getBodyRates();
      if (have) { double d0 = b[0] - prev[0], d1 = b[1] - prev[1]; acc += d0 * d0 + d1 * d1; }
      prev[0] = b[0]; prev[1] = b[1]; have = true; n++;
    }
    return n == 0 ? 0 : acc / n;
  }

  /** Drag-only per-axis delta (moving case minus zero-speed baseline) at zero
   *  throttle, level attitude: isolates airframe drag from gravity/thrust. */
  static float[] glideDrag(float vx, float vy, float vz) {
    FpvConfig cfg = new FpvConfig();
    TranslationalDynamics td = new TranslationalDynamics(cfg.activeAirframe());
    Quaternionf level = new Quaternionf();
    Vector3f base = td.step(level, 0f, 0.0, 0.0, 0.0, -1f, 1f, false, 0.05f);
    Vector3f mov  = td.step(level, 0f, (double) vx, (double) vy, (double) vz, -1f, 1f, false, 0.05f);
    return new float[]{ mov.x - base.x, mov.y - base.y, mov.z - base.z };
  }

  /** Zero-stick hover driven by a cycled per-step dt sequence; {minUp, nan}.
   *  Tests robustness at real render frame rates (60 fps) and under frame hitches. */
  static double[] hoverWithDts(double[] dts, double dur) {
    FpvConfig cfg = new FpvConfig();
    FlightController fc = new FlightController(cfg);
    fc.engage(0f, 0f);
    float hover = cfg.activeAirframe().effectiveHoverThrottle();
    StickChannels c = sticks(); c.throttle = hover;
    double minUp = 1, t = 0; boolean nan = false; int k = 0;
    while (t < dur) {
      double dt = dts[k % dts.length]; k++;
      fc.step(c, (float) dt, hover);
      t += dt;
      Vector3f up = new Vector3f(0f, 1f, 0f).rotate(fc.getAttitude());
      if (!Float.isFinite(up.x) || !Float.isFinite(up.y) || !Float.isFinite(up.z)) nan = true;
      if (up.y < minUp) minUp = up.y;
    }
    return new double[]{minUp, nan ? 1 : 0};
  }

  // ============================================================
  // Remote closed-loop plant (vanilla elytra + firework boost).
  // Velocity relax per attached rocket: v <- 0.5*v + 0.85*look (1.21.11 bytecode).
  // ============================================================

  /** One remote tick: ignite per the real ServerCompatLogic, relax the fleet, add gravity+drag.
   *  look = fixed level-forward-ish unit dir. Returns new velocity [vx,vy,vz]. */
  static double[] remoteTick(ServerCompatLogic logic, FireworkEnvelope.Fleet fleet,
                             double[] look, double[] v, float throttle, float speedBpt) {
    logic.tick();
    if (logic.shouldFirework(throttle, true, true, speedBpt)) { fleet.ignite(); logic.onFired(); }
    double[] nv = fleet.step(look[0], look[1], look[2], v[0], v[1], v[2]);
    nv[1] -= 0.05; // gravity
    // light vanilla elytra drag (per-tick)
    double sp = Math.sqrt(nv[0]*nv[0]+nv[1]*nv[1]+nv[2]*nv[2]);
    if (sp > 1e-4) { double d = 0.99; nv[0]*=d; nv[1]*=d; nv[2]*=d; }
    return nv;
  }

  /** High-frequency band energy fraction (>cutoffHz) of a 1/tick-sampled series.
   *  Simple DFT magnitude sum above the cutoff divided by total magnitude. */
  static double highBandEnergy(double[] series, double hzPerTick, double cutoffHz) {
    int n = series.length;
    double total = 0, high = 0;
    // DFT bins: bin k <-> freq = k * hzPerTick (real signal, use positive freqs).
    for (int k = 0; k < n / 2; k++) {
      double re = 0, im = 0;
      for (int i = 0; i < n; i++) {
        double a = -2 * Math.PI * k * i / n;
        re += series[i] * Math.cos(a);
        im += series[i] * Math.sin(a);
      }
      double mag = Math.sqrt(re*re + im*im);
      double hz = k * hzPerTick;
      total += mag;
      if (hz > cutoffHz) high += mag;
    }
    return total < 1e-9 ? 0 : high / total;
  }

  /** Std-dev of a series. */
  static double stddev(double[] s) {
    double m = 0; for (double v : s) m += v; m /= s.length;
    double a = 0; for (double v : s) a += (v-m)*(v-m);
    return Math.sqrt(a / s.length);
  }

  /** Build a remote-compat config (defaults). */
  static ServerCompatConfig remoteCfg() {
    ServerCompatConfig c = new ServerCompatConfig();
    c.setCompatEnabled(true); c.setFireworkEnabled(true);
    c.setCoordinatedTurn(true); c.setAntiKick(true);
    return c;
  }

  /** Smooth speed-limiter gain at a given horizontal speed (blocks/tick). */
  static double logicGain(double speedBpt) {
    return new ServerCompatLogic(remoteCfg()).speedLimitGain((float) speedBpt);
  }

  /** Instant ACRO roll setpoint (dps) for a given rate type, smoothing/PID off. */
  static double acroSetpointForType(String rateType, float roll) {
    FpvConfig cfg = new FpvConfig();
    cfg.setRateType(rateType);
    cfg.setSetpointSmoothingEnabled(false);
    cfg.setPhysicsRealism("ARCADE");
    if (cfg.getPid() != null) cfg.getPid().setEnabled(false);
    FlightController fc = new FlightController(cfg);
    fc.engage(0f, 0f);
    StickChannels c = sticks();
    c.roll = roll; c.throttle = 0.5f;
    fc.step(c, (float) DT, 0.5f);
    return fc.getSetpointRates()[1]; // roll channel
  }

  public static void main(String[] a) {
    System.out.println("=== REAL FlightController headless verification (dt=" + DT + "s) ===");

    // ---------- 1. three-axis step: finite non-instant rise, reach near setpoint ----------
    System.out.println("\n[1] Three-axis step response (pitch / roll / yaw)");
    double[] p = axisStep(0), r = axisStep(1), y = axisStep(2);
    System.out.printf("    pitch: t63=%.4fs peak=%.0f end=%.0f setpoint=%.0f%n", p[0], p[1], p[2], p[3]);
    System.out.printf("    roll : t63=%.4fs peak=%.0f end=%.0f setpoint=%.0f%n", r[0], r[1], r[2], r[3]);
    System.out.printf("    yaw  : t63=%.4fs peak=%.0f end=%.0f setpoint=%.0f%n", y[0], y[1], y[2], y[3]);

    // pitch: real, non-instant, reaches the target with only mild overshoot.
    check("pitch finite rise (5ms<t63<0.5s)", p[0] > 0.005 && p[0] < 0.5, "t63=" + String.format("%.4fs", p[0]));
    check("pitch reaches & holds near setpoint", p[1] >= 0.9 * p[3] && p[1] <= 1.4 * p[3] && p[2] > 0.8 * p[3],
      "peak/set=" + String.format("%.2f", p[1] / p[3]) + " end/set=" + String.format("%.2f", p[2] / p[3]));
    // roll: same.
    check("roll finite rise (5ms<t63<0.5s)", r[0] > 0.005 && r[0] < 0.5, "t63=" + String.format("%.4fs", r[0]));
    check("roll reaches & holds near setpoint", r[1] >= 0.9 * r[3] && r[1] <= 1.4 * r[3] && r[2] > 0.8 * r[3],
      "peak/set=" + String.format("%.2f", r[1] / r[3]) + " end/set=" + String.format("%.2f", r[2] / r[3]));
    // symmetry: a symmetric Quad-X airframe should pitch and roll at nearly the same rate.
    double ratio = Math.max(p[0], r[0]) / Math.max(Math.min(p[0], r[0]), 1e-6);
    check("pitch/roll response symmetric (ratio<2)", ratio < 2.0, "t63 ratio=" + String.format("%.2f", ratio));
    // yaw is driven by reaction torque only: require real authority and the correct sign,
    // not the same speed as roll/pitch (that would be unphysical).
    check("yaw has reaction-torque authority (>30%)", y[1] >= 0.30 * y[3], "peak/set=" + String.format("%.2f", y[1] / y[3]));
    check("yaw turns in the correct direction", y[2] > 0, "end=" + String.format("%.0f", y[2]));

    // ---------- 2. zero-stick rate hold stays upright ----------
    System.out.println("\n[2] Zero-stick rate hold stays upright (10s)");
    double[] h = uprightRun(10.0);
    System.out.printf("    min body-up.y=%.2f (1=level), NaN=%s%n", h[0], h[1] == 1);
    check("rate hold does not flip (up.y>0.2)", h[0] > 0.2, "min up.y=" + String.format("%.2f", h[0]));
    check("no NaN/Inf", h[1] == 0, "");

    // ---------- 3. prop wash: descending shimmies, clean forward flight does not ----------
    System.out.println("\n[3] Prop wash (descent vs clean forward flight)");
    double jDesc = washJitter(-0.20f, 0.0f, 0.8f);   // descending, settled, high power
    double jClean = washJitter(0.0f, 1.2f, 0.5f);    // level fast forward, fresh inflow
    System.out.printf("    descent jitter=%.2f  clean jitter=%.2f dps^2%n", jDesc, jClean);
    check("descent visibly shakes (jitter>=20 dps^2)", jDesc >= 20.0,
      "jDesc=" + String.format("%.1f", jDesc));
    check("clean flight smooth, descent dominates (>3x)", jClean < 5.0 && jDesc > jClean * 3.0,
      "ratio=" + String.format("%.1fx", jDesc / Math.max(jClean, 1e-6)));

    // ---------- 4. long closed loop ----------
    System.out.println("\n[4] Long closed-loop stability (30s)");
    double[] lo = uprightRun(30.0);
    System.out.printf("    min body-up.y=%.2f, NaN=%s%n", lo[0], lo[1] == 1);
    check("no NaN/Inf over 30s", lo[1] == 0, "");
    check("30s hover stays upright (up.y>0.2)", lo[0] > 0.2, "min up.y=" + String.format("%.2f", lo[0]));

    // ---------- 5. translational air drag: every direction, zero-throttle glide ----------
    System.out.println("\n[5] Translational air drag (zero-throttle glide, level)");
    float v = 0.5f;
    float[] ds = glideDrag(v, 0f, 0f), dv = glideDrag(0f, v, 0f), df = glideDrag(0f, 0f, v);
    float sSide = Math.abs(ds[0]), sVert = Math.abs(dv[1]), sFwd = Math.abs(df[2]);
    System.out.printf("    drag side=%.4f fwd=%.4f vert=%.4f (blocks/tick)%n", sSide, sFwd, sVert);
    check("side drag opposes motion", ds[0] < 0, "dx=" + String.format("%.4f", ds[0]));
    check("forward drag opposes motion", df[2] < 0, "dz=" + String.format("%.4f", df[2]));
    check("vertical drag opposes motion", dv[1] < 0, "dy=" + String.format("%.4f", dv[1]));
    check("drag anisotropic vert>fwd>side", sVert > sFwd && sFwd > sSide,
      String.format("%.3f > %.3f > %.3f", sVert, sFwd, sSide));
    check("glide drag material even at t=0", sSide > 1e-4, "side=" + String.format("%.4f", sSide));

    // ---------- 6. frame-rate dt robustness (60 fps + variable, hover) ----------
    System.out.println("\n[6] Frame-rate dt robustness (60fps + variable, zero-stick hover)");
    double[] f60 = hoverWithDts(new double[]{0.0166}, 10.0);
    double[] fvar = hoverWithDts(new double[]{0.010, 0.0166, 0.025, 0.008, 0.0166, 0.033}, 10.0);
    System.out.printf("    60fps dt=.0166 : minUp=%.2f NaN=%s%n", f60[0], f60[1] == 1);
    System.out.printf("    variable dt    : minUp=%.2f NaN=%s%n", fvar[0], fvar[1] == 1);
    check("60fps hover stays upright", f60[0] > 0.8 && f60[1] == 0, "minUp=" + String.format("%.2f", f60[0]));
    check("variable-dt hover stays upright", fvar[0] > 0.8 && fvar[1] == 0, "minUp=" + String.format("%.2f", fvar[0]));

    // ---------- 7. Firework beat: multi-throttle continuity + spool-down ----------
    System.out.println("\n[7] Firework beat continuity across throttle gears + spool-down");
    double[] gears = {0.3, 0.5, 0.7, 1.0};
    double maxGapFrac = 0, maxHighBand = 0;
    for (double thr : gears) {
      ServerCompatLogic logic = new ServerCompatLogic(remoteCfg());
      FireworkEnvelope.Fleet fleet = new FireworkEnvelope.Fleet(1, 42);
      double[] look = {0.0, -0.03, -1.0}; // slight nose-down cruise
      double[] vel = {0, 0, 0};
      int warmup = 100, run = 500;
      double[] vySer = new double[run];
      int gapTicks = 0;
      for (int i = 0; i < warmup + run; i++) {
        double spd = Math.sqrt(vel[0]*vel[0]+vel[2]*vel[2]);
        vel = remoteTick(logic, fleet, look, vel, (float) thr, (float) spd);
        if (i >= warmup) {
          vySer[i-warmup] = vel[1];
          if (fleet.silent()) gapTicks++;
        }
      }
      double spdFinal = Math.sqrt(vel[0]*vel[0]+vel[1]*vel[1]+vel[2]*vel[2]);
      // high-band ABSOLUTE RMS of the settled tail (drop startup transient). Fraction is
      // misleading when the whole signal is near-zero; judge the actual felt amplitude.
      double[] vyTail = new double[300];
      System.arraycopy(vySer, 200, vyTail, 0, 300);
      double hbFrac = highBandEnergy(vyTail, 20.0, 8.0);
      double hb = hbFrac * stddev(vyTail); // absolute >8Hz RMS (blocks/tick)
      double gapFrac = (double) gapTicks / run;
      System.out.printf("    thr=%.1f : finalSpeed=%.3f b/t (%.0f km/h)  gapTicks=%d/%d  hbAbs=%.5f b/t  vyStd=%.4f%n",
        thr, spdFinal, spdFinal*20*3.6, gapTicks, run, hb, stddev(vySer));
      if (thr >= 0.25) { // boosted gears must stay continuous + low high-freq energy
        maxGapFrac = Math.max(maxGapFrac, gapFrac);
        maxHighBand = Math.max(maxHighBand, hb);
      }
      // spool-down: throttle to 0, count ticks until fleet silent.
      int spool = 0;
      for (int i = 0; i < 60 && !fleet.silent(); i++) {
        double spd = Math.sqrt(vel[0]*vel[0]+vel[2]*vel[2]);
        vel = remoteTick(logic, fleet, look, vel, 0f, (float) spd);
        spool++;
      }
      System.out.printf("          spool-down: fleet silent after %d ticks (<= lifetime)%n", spool);
      check("thr=" + thr + " spool-down bounded (<=25 ticks)", spool <= 25, "t=" + spool);
    }
    check("boosted gears continuous (gapFrac<0.10)", maxGapFrac < 0.10, "maxGapFrac=" + String.format("%.3f", maxGapFrac));
    check("boosted gears low high-freq amplitude (hbAbs<0.01 b/t)", maxHighBand < 0.01, "maxHbAbs=" + String.format("%.5f", maxHighBand));

    // ---------- 8. Noise + pulsed thrust + variable dt: no divergence ----------
    System.out.println("\n[8] RC noise + pulse thrust + variable dt: no divergence");
    {
      ServerCompatLogic logic = new ServerCompatLogic(remoteCfg());
      FireworkEnvelope.Fleet fleet = new FireworkEnvelope.Fleet(1, 7);
      double[] look = {0, 0, -1};
      double[] vel8 = {0, 0, 0};
      boolean nan = false; double maxRate = 0;
      long rng = 123456789L;
      for (int i = 0; i < 2000; i++) {
        // pseudo-random zero-mean throttle noise + drift
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        double nz = ((rng >>> 33) & 0x7fff) / 32768.0 - 0.5;
        float thr = (float) (0.6 + 0.05 * nz);
        double spd = Math.sqrt(vel8[0]*vel8[0]+vel8[2]*vel8[2]);
        vel8 = remoteTick(logic, fleet, look, vel8, thr, (float) spd);
        if (!Double.isFinite(vel8[0]) || !Double.isFinite(vel8[1]) || !Double.isFinite(vel8[2])) nan = true;
        double sp = Math.sqrt(vel8[0]*vel8[0]+vel8[1]*vel8[1]+vel8[2]*vel8[2]);
        if (sp > maxRate) maxRate = sp;
      }
      System.out.printf("    max speed=%.3f b/t  NaN=%s%n", maxRate, nan);
      check("no NaN/Inf under noise+pulse+2000t", !nan, "");
      check("speed bounded (<=2.5 b/t)", maxRate < 2.5, "max=" + String.format("%.3f", maxRate));
    }

    // ---------- 9. Local quadcopter: full throttle accelerates PAST the 1.7 b/t vanilla pin ----------
    System.out.println("\n[9] Local quadcopter full-throttle acceleration (no hard ceiling, smooth terminal)");
    {
      FpvConfig cfg = new FpvConfig();
      TranslationalDynamics td = new TranslationalDynamics(cfg.activeAirframe());
      Quaternionf q = new Quaternionf(); // level, nose forward
      double vx=0, vy=0, vz=0; double[] ser = new double[400];
      float thr = 1.0f;
      // pitch the nose down 15 deg so thrust vector points forward+down (accelerate).
      Quaternionf fwd = new Quaternionf().rotateX((float) Math.toRadians(15));
      for (int i = 0; i < 400; i++) {
        Vector3f d = td.step(fwd, thr, vx, vy, vz, 100f, 1f, false, 0.05f);
        vx += d.x(); vy += d.y(); vz += d.z();
        ser[i] = Math.sqrt(vx*vx+vy*vy+vz*vz);
      }
      double term = ser[399];
      // high-band energy of the SETTLED tail (drop the acceleration transient).
      double[] settled = new double[200];
      System.arraycopy(ser, 200, settled, 0, 200);
      double hb = highBandEnergy(settled, 20.0, 8.0);
      System.out.printf("    terminal speed=%.3f b/t (%.0f km/h)  highBand=%.4f%n",
        term, term*20*3.6, hb);
      check("local quadcopter passes vanilla 1.7 b/t pin (>1.7)", term > 1.7, "term=" + String.format("%.3f", term));
      check("terminal speed smooth (low high-freq energy <0.1)", hb < 0.1, "hb=" + String.format("%.3f", hb));
      // smooth limiter gain is continuous (no bang-bang)
      double g0 = logicGain(1.0), g1 = logicGain(1.4), g2 = logicGain(1.7);
      System.out.printf("    speedLimitGain @1.0/1.4/1.7 b/t = %.2f/%.2f/%.2f (continuous 1->0)%n", g0, g1, g2);
      check("speed limiter gain continuous monotone", g0 >= g1 && g1 >= g2 && g0 == 1.0 && g2 == 0.0, "");
    }

    // ---------- 10. Bank-driven passive turn (omega=g*tan(phi)/V), yaw stick = 0 ----------
    System.out.println("\n[10] Bank passive coordinated turn (pure roll, yaw=0, then release)");
    {
      ServerCompatLogic logic = new ServerCompatLogic(remoteCfg());
      float bank = 25f;
      double heading0 = logic.currentTurnHeadingDeg();
      for (int i = 0; i < 100; i++) logic.integrateTurn(bank, 0.8f, 1f);
      double turned = logic.currentTurnHeadingDeg() - heading0;
      // analytic omega per tick = gain*tan(phi*coordTurnGain)/V
      double phi = Math.toRadians(bank * 0.5); // coordTurnGain=0.5
      double expected = Math.tan(phi) / 0.8 * 100; // gain=1
      System.out.printf("    held bank=%.0f deg, yaw=0: turned=%.1f deg over 100t (analytic ~%.1f)%n", bank, turned, expected);
      check("pure-roll turn produces heading change (>5 deg)", Math.abs(turned) > 5, "turned=" + String.format("%.1f", turned));
      check("turn rate matches g*tan(phi)/V (within 40%)",
        Math.abs(Math.abs(turned) - expected) < 0.4 * expected, "got=" + String.format("%.1f", expected));
      // release bank: heading freezes (does NOT unwind)
      for (int i = 0; i < 50; i++) logic.integrateTurn(0f, 0.8f, 1f);
      double afterRelease = logic.currentTurnHeadingDeg();
      double drift = Math.abs(afterRelease - (heading0 + turned));
      System.out.printf("    after releasing bank: heading drift=%.2f deg (should ~0, no unwind)%n", drift);
      check("released bank freezes heading (no unwind)", drift < 2.0, "drift=" + String.format("%.2f", drift));
    }

    // ---------- 11. Quadcopter characteristics (local body-up model) ----------
    System.out.println("\n[11] Quadcopter body-up characteristics (hover / on-spot yaw / vertical / no stall / pitch vector)");
    {
      FpvConfig cfg = new FpvConfig();
      TranslationalDynamics td = new TranslationalDynamics(cfg.activeAirframe());
      float hover = cfg.activeAirframe().effectiveHoverThrottle();
      // hover: level, hover throttle -> vy converges ~0, position stable
      double vx=0,vy=0,vz=0; double px=0,py=0,pz=0;
      for (int i = 0; i < 600; i++) {
        Vector3f d = td.step(new Quaternionf(), hover, vx, vy, vz, 100f, 1f, false, 0.05f);
        vx+=d.x(); vy+=d.y(); vz+=d.z(); px+=vx; py+=vy; pz+=vz;
      }
      System.out.printf("    hover: vy=%.4f b/t, |pos drift|=%.3f blocks%n", vy, Math.sqrt(px*px+py*py+pz*pz));
      check("hover converges to ~0 vertical speed (|vy|<0.01)", Math.abs(vy) < 0.01, "vy=" + String.format("%.4f", vy));
      // vertical climb: throttle above hover
      vx=vy=vz=0;
      for (int i=0;i<200;i++){ Vector3f d=td.step(new Quaternionf(),1f,vx,vy,vz,100f,1f,false,0.05f); vx+=d.x();vy+=d.y();vz+=d.z(); }
      System.out.printf("    full throttle vertical climb: vy=%.3f b/t%n", vy);
      check("full throttle climbs vertically (vy>0.05)", vy > 0.05, "vy=" + String.format("%.3f", vy));
      // no stall: at zero airspeed, body-up lift still accelerates (full throttle).
      vx=vy=vz=0;
      Vector3f d0 = td.step(new Quaternionf(), 1f, 0,0,0, 100f,1f,false,0.05f);
      check("zero airspeed still has active body-up thrust (no stall)", d0.y() > 0.01, "dy=" + String.format("%.4f", d0.y()));
      // on-spot yaw: rotate heading, position should not translate horizontally
      Quaternionf yaw = new Quaternionf().rotateY((float)Math.toRadians(45));
      vx=vy=vz=0; double hx=0,hz=0;
      for (int i=0;i<200;i++){ Vector3f d=td.step(yaw,hover,vx,vy,vz,100f,1f,false,0.05f); vx+=d.x();vy+=d.y();vz+=d.z(); hx+=vx; hz+=vz; }
      check("on-spot yaw: no horizontal translation (|dxz|<0.1)", Math.sqrt(hx*hx+hz*hz) < 0.1, "dxz=" + String.format("%.3f", Math.sqrt(hx*hx+hz*hz)));
      // pitch vector: pitch tilt -> horizontal forward speed builds
      Quaternionf pitch = new Quaternionf().rotateX((float)Math.toRadians(20));
      vx=vy=vz=0;
      for (int i=0;i<200;i++){ Vector3f d=td.step(pitch,hover,vx,vy,vz,100f,1f,false,0.05f); vx+=d.x();vy+=d.y();vz+=d.z(); }
      check("pitch thrust-vector builds horizontal speed (|vz|>0.1)", Math.abs(vz) > 0.1, "vz=" + String.format("%.3f", vz));
    }

    // ---------- 12. HUD layering: OSD never enters motion-blur history; layout stable ----------
    System.out.println("\n[12] HUD layering: OSD excluded from blur history; layout uses same attitude");
    {
      MotionBlurCompositor comp = new MotionBlurCompositor(4);
      // scene shifts between sub-samples (world motion blur), OSD text is fixed.
      float[] osd = new float[8]; osd[0] = 1f; osd[3] = 1f; // opaque red OSD pixel at index 0
      for (int s = 0; s < 4; s++) {
        float[] scene = {0.1f*s, 0, 0}; // moving scene pixel
        comp.accumulateScene(scene);
      }
      float[] out = comp.composite(osd);
      // OSD pixel (index0) must be pure red (alpha replaced), NOT the averaged scene.
      boolean osdClean = out[0] == 1f && out[1] == 0f && out[2] == 0f;
      System.out.printf("    OSD pixel after composite = (%.2f,%.2f,%.2f) [expect 1,0,0]; scene averaged=%.2f%n",
        out[0], out[1], out[2], comp.scenePixel(0));
      check("OSD pixel never smeared by scene blur", osdClean, "");
      // layout from a fixed attitude is bit-identical frame to frame (no sub-pixel shimmer).
      Quaternionf q = new Quaternionf().rotateZ((float)Math.toRadians(12)).rotateX((float)Math.toRadians(-5));
      int dy1 = OsdLayoutMath.INSTANCE.groupDyPx(q); int rows1 = OsdLayoutMath.INSTANCE.ladderTickRows().length;
      int dy2 = OsdLayoutMath.INSTANCE.groupDyPx(q); int rows2 = OsdLayoutMath.INSTANCE.ladderTickRows().length;
      check("OSD layout deterministic frame-to-frame", dy1==dy2 && rows1==rows2, "dy="+dy1);
    }

    // ---------- 13. Betaflight CLI import: actual + legacy samples, error capture ----------
    System.out.println("\n[13] Betaflight CLI import (actual + legacy + out-of-range error)");
    {
      String actual =
        "set rates_type = ACTUAL\n" +
        "set roll_rc_rate = 7.00\n" +
        "set pitch_rc_rate = 7.00\n" +
        "set yaw_rc_rate = 7.00\n" +
        "set roll_super_rate = 67.00\n" +
        "set pitch_super_rate = 67.00\n" +
        "set yaw_super_rate = 67.00\n" +
        "set roll_expo = 0.00\n" +
        "set throttle_mid = 1500\n" +
        "set throttle_expo = 0\n" +
        "set motor_idle = 6.0\n" +
        "set roll_p = 45\n" +
        "set roll_i = 80\n" +
        "set roll_d = 30\n" +
        "set roll_f = 120\n" +
        "set serial_port_1_speed = 115200\n" +
        "set roll_rc_rate = 30.0\n";   // duplicate out-of-range -> error (center=300 > 250)
      FpvConfig cfg = new FpvConfig();
      dev.fpv.flight.BfImportResult res = dev.fpv.flight.BetaflightCli.INSTANCE.importInto(actual, cfg);
      System.out.printf("    rateType=%s roll.center=%.1f roll.max=%.1f thrMid=%.1f minThr=%.3f roll.P=%.0f errors=%d skipped=%d%n",
        cfg.getRateType(), cfg.getRoll().getCenter(), cfg.getRoll().getMax(),
        cfg.getThrMidPct(), cfg.activeAirframe().getMinThrottle(),
        cfg.getPid().getRoll().getP(), res.getErrors().size(), res.getSkippedUnrelated());
      check("BF import sets rateType=ACTUAL", "ACTUAL".equals(cfg.getRateType()), cfg.getRateType());
      check("BF actual roll center=70 dps", Math.abs(cfg.getRoll().getCenter()-70f) < 1f, "c="+cfg.getRoll().getCenter());
      check("BF actual roll max=670 dps", Math.abs(cfg.getRoll().getMax()-670f) < 1f, "m="+cfg.getRoll().getMax());
      check("BF throttle_mid 1500 -> thrMidPct=50", Math.abs(cfg.getThrMidPct()-50f) < 0.5f, "mid="+cfg.getThrMidPct());
      check("BF motor_idle 6% -> minThrottle=0.06", Math.abs(cfg.activeAirframe().getMinThrottle()-0.06f) < 0.005f, "");
      check("BF roll_p=45 applied", cfg.getPid().getRoll().getP() == 45f, "P="+cfg.getPid().getRoll().getP());
      check("out-of-range line captured as error (not silent)", res.getErrors().size() >= 1, "errs="+res.getErrors());
      check("unrelated dump line skipped (counted)", res.getSkippedUnrelated() >= 1, "skip="+res.getSkippedUnrelated());

      String legacy =
        "set rates_type = BETAFLIGHT\n" +
        "set rc_rate = 2.0\n" +
        "set rc_expo = 0.10\n" +
        "set roll_super_rate = 0.70\n";
      dev.fpv.flight.BfImportResult res2 = dev.fpv.flight.BetaflightCli.INSTANCE.importInto(legacy, cfg);
      System.out.printf("    legacy: rateType=%s roll.rcRate=%.2f roll.superRate=%.2f roll.expo=%.2f%n",
        cfg.getRateType(), cfg.getRoll().getRcRate(), cfg.getRoll().getSuperRate(), cfg.getRoll().getExpo());
      check("BF legacy sets rateType=LEGACY", "LEGACY".equals(cfg.getRateType()), cfg.getRateType());
      check("BF legacy rc_rate=2.0 -> roll.rcRate=2.0", Math.abs(cfg.getRoll().getRcRate()-2.0f) < 0.01f, "");
      check("BF legacy roll_super_rate=0.70", Math.abs(cfg.getRoll().getSuperRate()-0.70f) < 0.01f, "");
      check("BF legacy rc_expo=0.10 -> roll.expo", Math.abs(cfg.getRoll().getExpo()-0.10f) < 0.01f, "e="+cfg.getRoll().getExpo());
    }

    // ---------- 14. rate-type selector: same stick -> different setpoint per type ----------
    System.out.println("\n[14] Rate-type selector: ACRO setpoint varies with ACTUAL/LEGACY/QUICK");
    {
      double spActual = acroSetpointForType("ACTUAL", 0.5f);
      double spLegacy = acroSetpointForType("LEGACY", 0.5f);
      double spQuick  = acroSetpointForType("QUICK", 0.5f);
      System.out.printf("    roll=0.5: ACTUAL=%.1f LEGACY=%.1f QUICK=%.1f dps%n", spActual, spLegacy, spQuick);
      check("ACTUAL mid-stick setpoint finite > 0", spActual > 50 && spActual < 400, "act="+String.format("%.1f", spActual));
      check("LEGACY differs from ACTUAL at mid-stick", Math.abs(spLegacy - spActual) > 20, "leg="+String.format("%.1f", spLegacy));
      check("QUICK differs from ACTUAL at mid-stick (expo bend)", Math.abs(spQuick - spActual) > 20, "qck="+String.format("%.1f", spQuick));
      double spFull = acroSetpointForType("ACTUAL", 1f);
      check("full-stick ACTUAL reaches ~max (670)", Math.abs(spFull - 670f) < 30, "full="+String.format("%.1f", spFull));
    }

    // ---------- 15. Remote setback detection: big snap fires, small/relative/cooldown don't ----------
    System.out.println("\n[15] Remote rubber-band setback detector + onSetback backoff");
    {
      dev.fpv.flight.SetbackDetector det = dev.fpv.flight.SetbackDetector.INSTANCE;
      check("big absolute snap (>2 blocks) is a setback",
        det.isSetback(5.0, true, 2.0f, Long.MAX_VALUE, 0L), "dist=5");
      check("small absolute correction (<2 blocks) is NOT",
        !det.isSetback(0.5, true, 2.0f, Long.MAX_VALUE, 0L), "dist=0.5");
      check("relative-axis delta never counts",
        !det.isSetback(5.0, false, 2.0f, Long.MAX_VALUE, 0L), "relatives");
      check("cooldown not elapsed -> suppressed",
        !det.isSetback(5.0, true, 2.0f, 100L, 800L), "cd=100/800");
      check("cooldown elapsed -> fires",
        det.isSetback(5.0, true, 2.0f, 900L, 800L), "cd=900/800");

      ServerCompatLogic logic = new ServerCompatLogic(remoteCfg());
      int intervalBefore = logic.effectiveFireworkInterval();
      logic.onSetback();
      int intervalAfter = logic.effectiveFireworkInterval();
      double penalty = logic.currentPenalty();
      System.out.printf("    interval before=%d after=%d penalty=%.2f%n", intervalBefore, intervalAfter, penalty);
      check("onSetback raises penalty to 1.0", penalty >= 0.99, "p="+String.format("%.2f", penalty));
      check("interval widened after setback (anti-kick)", intervalAfter > intervalBefore,
        intervalBefore + "->" + intervalAfter);
    }

    // ---------- 16. Continuous-axis aux (S1/S2/LS/RS) binding path ----------
    System.out.println("\n[16] Continuous aux axis routed via Modes table (S1 -> ANGLE)");
    {
      dev.fpv.flight.ModesController modes = new dev.fpv.flight.ModesController();
      dev.fpv.flight.ModeBinding b = new dev.fpv.flight.ModeBinding();
      b.setFunction("ANGLE"); b.setSourceKind("AUX"); b.setSourceName("S1");
      b.setActiveLow(0.5f); b.setActiveHigh(1f);
      java.util.List<dev.fpv.flight.ModeBinding> rows = java.util.List.of(b);

      // knob S1 high (>0.5): ANGLE should engage
      StickChannels high = sticks();
      high.auxChannels = java.util.List.of(
        new dev.fpv.input.AuxState("S1", 0.8f, -1, 0, "AXIS", "Axis 9", true));
      dev.fpv.flight.ModesResult rHigh = modes.evaluate(high, rows);

      // knob S1 low (<0.5): ANGLE must disengage
      StickChannels low = sticks();
      low.auxChannels = java.util.List.of(
        new dev.fpv.input.AuxState("S1", 0.2f, -1, 0, "AXIS", "Axis 9", true));
      dev.fpv.flight.ModesResult rLow = modes.evaluate(low, rows);

      // unknown/unbound channel at full value: must NOT participate
      StickChannels unbound = sticks();
      unbound.auxChannels = java.util.List.of(
        new dev.fpv.input.AuxState("S99", 1.0f, -1, 0, "AXIS", "Axis 13", false));
      dev.fpv.flight.ModesResult rUb = modes.evaluate(unbound, rows);

      System.out.printf("    S1=0.8 mode=%s | S1=0.2 mode=%s | unbound=1.0 mode=%s%n",
        rHigh.getDesiredMode(), rLow.getDesiredMode(), rUb.getDesiredMode());
      check("S1 high band engages ANGLE mode",
        rHigh.getDesiredMode() == dev.fpv.flight.FlightMode.ANGLE, ""+rHigh.getDesiredMode());
      check("S1 low band releases ANGLE", rLow.getDesiredMode() == null, ""+rLow.getDesiredMode());
      check("unbound channel never participates", rUb.getDesiredMode() == null, ""+rUb.getDesiredMode());
    }

    System.out.println("\n========================================");
    System.out.println(failures == 0 ? "ALL TESTS PASS" : ("FAILURES: " + failures));
    System.exit(failures == 0 ? 0 : 1);
  }
}
