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

  // I-11: clean-flight jitter (dps^2) below this makes the wash ratio numerically
  // meaningless; report N/A rather than divide by a near-zero denominator.
  static final double WASH_RATIO_MIN_CLEAN_DPS2 = 1.0;

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
    Vector3f base = td.step(level, 0f, 0.0, 0.0, 0.0, -1f, 1f, false, 0.05f, 1.0f);
    Vector3f mov  = td.step(level, 0f, (double) vx, (double) vy, (double) vz, -1f, 1f, false, 0.05f, 1.0f);
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

  private static double trackSampleX(dev.fpv.replay.CinematicTrack track, double t) {
    var p = new dev.fpv.replay.TrackPose();
    track.sample(t, p);
    return p.getX();
  }

  public static void main(String[] a) throws java.io.IOException {
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
    // I-11: when clean-flight jitter is ~zero the ratio is dominated by numerical
    // noise, so report N/A instead of a huge/inf blow-up; never divide by <=0.
    String ratioStr;
    if (jClean < WASH_RATIO_MIN_CLEAN_DPS2 || !Double.isFinite(jClean) || jClean <= 0.0) {
      ratioStr = "N/A(clean~" + String.format("%.2f", jClean) + ")";
    } else {
      double washRatio = jDesc / jClean;
      ratioStr = Double.isFinite(washRatio) ? String.format("%.1fx", washRatio) : "non-finite";
    }
    check("clean flight smooth, descent dominates (>3x)", jClean < 5.0 && jDesc > jClean * 3.0,
      "ratio=" + ratioStr);
    check("wash ratio evidence finite (no inf/NaN)",
      !ratioStr.contains("Infinity") && !ratioStr.contains("NaN") && Double.isFinite(jClean),
      ratioStr);

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
        Vector3f d = td.step(fwd, thr, vx, vy, vz, 100f, 1f, false, 0.05f, 1.0f);
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
        Vector3f d = td.step(new Quaternionf(), hover, vx, vy, vz, 100f, 1f, false, 0.05f, 1.0f);
        vx+=d.x(); vy+=d.y(); vz+=d.z(); px+=vx; py+=vy; pz+=vz;
      }
      System.out.printf("    hover: vy=%.4f b/t, |pos drift|=%.3f blocks%n", vy, Math.sqrt(px*px+py*py+pz*pz));
      check("hover converges to ~0 vertical speed (|vy|<0.01)", Math.abs(vy) < 0.01, "vy=" + String.format("%.4f", vy));
      // vertical climb: throttle above hover
      vx=vy=vz=0;
      for (int i=0;i<200;i++){ Vector3f d=td.step(new Quaternionf(),1f,vx,vy,vz,100f,1f,false,0.05f,1.0f); vx+=d.x();vy+=d.y();vz+=d.z(); }
      System.out.printf("    full throttle vertical climb: vy=%.3f b/t%n", vy);
      check("full throttle climbs vertically (vy>0.05)", vy > 0.05, "vy=" + String.format("%.3f", vy));
      // no stall: at zero airspeed, body-up lift still accelerates (full throttle).
      vx=vy=vz=0;
      Vector3f d0 = td.step(new Quaternionf(), 1f, 0,0,0, 100f,1f,false,0.05f,1.0f);
      check("zero airspeed still has active body-up thrust (no stall)", d0.y() > 0.01, "dy=" + String.format("%.4f", d0.y()));
      // on-spot yaw: rotate heading, position should not translate horizontally
      Quaternionf yaw = new Quaternionf().rotateY((float)Math.toRadians(45));
      vx=vy=vz=0; double hx=0,hz=0;
      for (int i=0;i<200;i++){ Vector3f d=td.step(yaw,hover,vx,vy,vz,100f,1f,false,0.05f,1.0f); vx+=d.x();vy+=d.y();vz+=d.z(); hx+=vx; hz+=vz; }
      check("on-spot yaw: no horizontal translation (|dxz|<0.1)", Math.sqrt(hx*hx+hz*hz) < 0.1, "dxz=" + String.format("%.3f", Math.sqrt(hx*hx+hz*hz)));
      // pitch vector: pitch tilt -> horizontal forward speed builds
      Quaternionf pitch = new Quaternionf().rotateX((float)Math.toRadians(20));
      vx=vy=vz=0;
      for (int i=0;i<200;i++){ Vector3f d=td.step(pitch,hover,vx,vy,vz,100f,1f,false,0.05f,1.0f); vx+=d.x();vy+=d.y();vz+=d.z(); }
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

    // ---------- 17. Camera tilt ramp on arm: monotonic, bounded steps, full tilt; disarm back ----------
    System.out.println("\n[17] Camera-tilt ramp (no unlock snap)");
    {
      dev.fpv.flight.CameraTiltRamp ramp = new dev.fpv.flight.CameraTiltRamp(0.4f);
      float dt = 0.016f;
      // arm: target 25 deg
      double v0 = ramp.update(25f, dt);
      boolean monotonic = true; boolean bounded = true;
      double prev = v0;
      for (int i = 0; i < 60; i++) { // ~1s, well beyond 0.4s ramp
        double tt = ramp.update(25f, dt);
        if (tt < prev - 1e-4) monotonic = false;
        if (Math.abs(tt - prev) > 25f * dt / 0.4f + 1e-4) bounded = false;
        prev = tt;
      }
      double armed = prev;
      System.out.printf("    after arm: v0=%.2f final=%.2f deg (ramp=0.4s, dt=%.3f)%n", v0, armed, dt);
      check("tilt starts small (no 25 deg snap on arm)", v0 < 2.0, "v0="+String.format("%.2f", v0));
      check("tilt ramps monotonically up on arm", monotonic, "");
      check("per-frame increment bounded by ramp rate", bounded, "");
      check("tilt reaches full 25 deg", Math.abs(armed - 25f) < 0.5, "final="+String.format("%.2f", armed));
      // disarm: target 0
      boolean monoDown = true; double pprev = armed;
      for (int i = 0; i < 60; i++) {
        double tt = ramp.update(0f, dt);
        if (tt > pprev + 1e-4) monoDown = false;
        pprev = tt;
      }
      System.out.printf("    after disarm: final=%.3f deg%n", pprev);
      check("tilt ramps back to 0 on disarm", pprev < 0.5, "final="+String.format("%.3f", pprev));
      check("disarm ramp monotonic down", monoDown, "");
    }

    // ---------- 18. TrackStore atomic write (I-6) ----------
    System.out.println("\n[18] TrackStore atomic write (no half-written file, round-trips)");
    {
      try {
      java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("fpvtrack");
      java.nio.file.Path tPath = dir.resolve("test.json");
      dev.fpv.race.TrackDoc doc = new dev.fpv.race.TrackDoc();
      doc.setName("Test Course");
      doc.setDefaultWidth(2.5f);
      dev.fpv.race.TrackStore.saveTo(tPath, doc);
      try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
        long tmp = s.filter(f -> f.getFileName().toString().contains(".tmp-")).count();
        check("no leftover atomic temp file", tmp == 0, "tmp=" + tmp);
      }
      String raw = java.nio.file.Files.readString(tPath);
      check("target file exists & non-empty", raw.length() > 10, "bytes=" + raw.length());
      com.google.gson.Gson g = new com.google.gson.Gson();
      dev.fpv.race.TrackDoc back = g.fromJson(raw, dev.fpv.race.TrackDoc.class);
      check("round-trip name preserved", "Test Course".equals(back.getName()), back.getName());
      check("round-trip width preserved", Math.abs(back.getDefaultWidth() - 2.5f) < 1e-5, "w=" + back.getDefaultWidth());
      // Overwrite: previous file must be atomically replaced, never truncated.
      dev.fpv.race.TrackDoc doc2 = new dev.fpv.race.TrackDoc();
      doc2.setName("Test Course"); doc2.setDefaultWidth(3.5f);
      dev.fpv.race.TrackStore.saveTo(tPath, doc2);
      String raw2 = java.nio.file.Files.readString(tPath);
      dev.fpv.race.TrackDoc back2 = g.fromJson(raw2, dev.fpv.race.TrackDoc.class);
      check("overwrite round-trips to new width", Math.abs(back2.getDefaultWidth() - 3.5f) < 1e-5, "w=" + back2.getDefaultWidth());
      } catch (java.io.IOException e) {
        check("TrackStore IO complete", false, e.toString());
      }
    }

    // ---------- 19. Monitor gimbal labels follow hand mode (I-7) ----------
    System.out.println("\n[19] Monitor gimbal labels follow hand mode (reverse lookup)");
    {
      dev.fpv.input.StickSlot RH = dev.fpv.input.StickSlot.RH;
      String m2rh = dev.fpv.input.HandLayout.slotLabel(2, RH);
      String m1rh = dev.fpv.input.HandLayout.slotLabel(1, RH);
      System.out.printf("    Mode2 RH=%s   Mode1 RH=%s%n", m2rh, m1rh);
      check("Mode2 RH = Roll", "Roll".equals(m2rh), m2rh);
      check("Mode1 RH = Yaw", "Yaw".equals(m1rh), m1rh);
      check("same physical slot differs across modes", !m2rh.equals(m1rh), m2rh + " != " + m1rh);
      check("Mode2 LH=Yaw LV=Throttle RV=Pitch",
        "Yaw".equals(dev.fpv.input.HandLayout.slotLabel(2, dev.fpv.input.StickSlot.LH))
        && "Throttle".equals(dev.fpv.input.HandLayout.slotLabel(2, dev.fpv.input.StickSlot.LV))
        && "Pitch".equals(dev.fpv.input.HandLayout.slotLabel(2, dev.fpv.input.StickSlot.RV)), "");
      check("Mode1 LH=Roll LV=Pitch RV=Throttle",
        "Roll".equals(dev.fpv.input.HandLayout.slotLabel(1, dev.fpv.input.StickSlot.LH))
        && "Pitch".equals(dev.fpv.input.HandLayout.slotLabel(1, dev.fpv.input.StickSlot.LV))
        && "Throttle".equals(dev.fpv.input.HandLayout.slotLabel(1, dev.fpv.input.StickSlot.RV)), "");
    }

    // ---------- 20. yaw physical ceiling (I-9) ----------
    System.out.println("\n[20] yaw physical ceiling (analytic vs real plant; below nominal max)");
    {
      FpvConfig cfg = new FpvConfig();
      double nominal = cfg.getYaw().getMax();
      double predicted = cfg.activeAirframe().yawPhysicalMaxDps(0.5f, 1f);
      double[] yawStep = axisStep(2); // yaw full stick at thr=0.5 -> {t63,peak,end,setpoint}
      double measured = Math.abs(yawStep[2]);
      System.out.printf("    analytic=%.1f dps  measured end=%.1f dps  nominal max=%.0f dps%n",
        predicted, measured, nominal);
      check("analytic ceiling finite & >0", predicted > 50.0 && Double.isFinite(predicted), "pred=" + predicted);
      check("measured full-stick yaw ~ analytic (within 20%)",
        Math.abs(measured - predicted) <= 0.20 * predicted,
        "measured=" + String.format("%.0f", measured) + " pred=" + String.format("%.0f", predicted));
      check("physical ceiling below nominal max (the gap we annotate)",
        predicted < 0.75 * nominal,
        "pred=" + String.format("%.0f", predicted) + " nom=" + String.format("%.0f", nominal));
    }

    // ---------- 21. OSD horizon direction (I-10) ----------
    System.out.println("\n[21] OSD horizon direction (roll right -> left-high; nose-down -> rises)");
    {
      // --- right roll: drive the REAL FC with roll=+1 ---
      {
        FpvConfig cfg = new FpvConfig();
        FlightController fc = new FlightController(cfg);
        fc.engage(0f, 0f);
        float hover = cfg.activeAirframe().effectiveHoverThrottle();
        StickChannels warm = sticks(); warm.throttle = hover;
        for (int i = 0; i < (int)(0.3 / DT); i++) fc.step(warm, (float) DT, hover);
        StickChannels c = sticks(); c.roll = 1f; c.throttle = hover;
        for (int i = 0; i < (int)(0.6 / DT); i++) fc.step(c, (float) DT, hover);
        Quaternionf att = new Quaternionf(fc.getAttitude());
        float rollDeg = (float) Math.toDegrees(OsdLayoutMath.rollRad(att));
        float[] e = OsdLayoutMath.horizonEndpointScreenDy(att, 30f);
        System.out.printf("    roll-right: rollRad=%+.1f deg  leftY=%.1f rightY=%.1f%n", rollDeg, e[0], e[1]);
        check("right roll yields positive rollRad (banked-right convention)", rollDeg > 5.0, "roll="+String.format("%+.1f", rollDeg));
        check("right roll -> horizon left-high right-low (leftY<rightY)", e[0] < e[1],
          "leftY=" + String.format("%.1f", e[0]) + " rightY=" + String.format("%.1f", e[1]));
      }
      // --- nose down: drive the REAL FC with pitch=+1 ---
      {
        FpvConfig cfg = new FpvConfig();
        FlightController fc = new FlightController(cfg);
        fc.engage(0f, 0f);
        float hover = cfg.activeAirframe().effectiveHoverThrottle();
        StickChannels warm = sticks(); warm.throttle = hover;
        for (int i = 0; i < (int)(0.3 / DT); i++) fc.step(warm, (float) DT, hover);
        StickChannels c = sticks(); c.pitch = -1f; c.throttle = hover; // -1 = nose down here
        for (int i = 0; i < (int)(0.6 / DT); i++) fc.step(c, (float) DT, hover);
        Quaternionf att = new Quaternionf(fc.getAttitude());
        float pDeg = OsdLayoutMath.pitchDeg(att);
        int dy = OsdLayoutMath.groupDyPx(att);
        System.out.printf("    nose-down: pitch=%+.1f deg  groupDy=%d px (negative = rises)%n", pDeg, dy);
        check("nose-down gives positive pitchDeg", pDeg > 2.0, "p=" + String.format("%.1f", pDeg));
        check("nose-down moves horizon UP on screen (groupDy<0)", dy < 0, "dy=" + dy);
      }
    }

    // ---------- 22. 3D reversible-thrust physics (signed motor) ----------
    System.out.println("\n[22] 3D reversible-3D: negative stick -> reverse thrust + stable reversed yaw");
    {
      FpvConfig cfg = new FpvConfig();
      cfg.setReversible3D(true);
      float dead = cfg.getThreeDThrottleDeadband();

      // (a) Vertical thrust sign via TranslationalDynamics (level attitude, far from ground).
      TranslationalDynamics td = new TranslationalDynamics(cfg.activeAirframe());
      Quaternionf level = new Quaternionf();
      float dyUp   = td.step(level,  0.5f, 0,0,0, 100f, 1f, true, dead, 1.0f).y();
      float dyDown = td.step(level, -0.5f, 0,0,0, 100f, 1f, true, dead, 1.0f).y();
      float dyMid  = td.step(level,  0.02f,0,0,0, 100f, 1f, true, dead, 1.0f).y(); // inside deadband
      System.out.printf("    vertical accel  up=%.4f  reverse=%.4f  mid(deadband)=%.4f%n", dyUp, dyDown, dyMid);
      check("3D positive throttle pushes UP", dyUp > dyDown, "up="+String.format("%.4f",dyUp));
      check("3D negative throttle pushes DOWN (thrust reversed)", dyDown < dyUp, "rev="+String.format("%.4f",dyDown));
      check("3D mid deadband gives gravity-only fall (no thrust, between reverse and up)",
        dyDown < dyMid && dyMid < dyUp && dyMid < 0f, "mid="+String.format("%.4f",dyMid));

      // (b) Rotational: yaw loop stays the SAME direction & finite in reverse; zero at mid.
      dev.fpv.flight.RatePidController pid = new dev.fpv.flight.RatePidController(cfg);
      dev.fpv.flight.RealDynamics rd = new dev.fpv.flight.RealDynamics(cfg, pid);
      float yawSp = cfg.getYaw().getMax();
      float[] sp = new float[]{0f, 0f, yawSp};
      for (int i = 0; i < (int)(0.6 / DT); i++) rd.step(new float[]{0,0,0}, 0.5f, (float) DT, 1f);
      for (int i = 0; i < (int)(1.2 / DT); i++) rd.step(sp, 0.5f, (float) DT, 1f);
      float yawFwd = rd.getRatesDps()[2];
      for (int i = 0; i < (int)(1.5 / DT); i++) rd.step(sp, -0.5f, (float) DT, 1f);
      float yawRev = rd.getRatesDps()[2];
      // mid collective + ZERO attitude demand -> motors settle to ~0, residual rate decays
      for (int i = 0; i < (int)(1.5 / DT); i++) rd.step(new float[]{0,0,0}, 0.0f, (float) DT, 1f);
      float yawMid = rd.getRatesDps()[2];
      System.out.printf("    yaw rate dps  forward=%.1f  reversed=%.1f  mid=%.2f%n", yawFwd, yawRev, yawMid);
      check("forward yaw demand produces a finite rate", Math.abs(yawFwd) > 20f && Float.isFinite(yawFwd), "fwd="+yawFwd);
      check("reversed thrust keeps SAME yaw direction (loop not flipped)", Math.signum(yawFwd) == Math.signum(yawRev) && Math.abs(yawRev) > 10f, "rev="+yawRev);
      check("mid throttle gives ~zero yaw authority (no NaN)", Float.isFinite(yawMid) && Math.abs(yawMid) < 30f, "mid="+yawMid);
    }

    // ---------- 23. Horizon strength fades with bank angle + rise-smoothed ----------
    System.out.println("\n[23] Horizon: leveling strength fades with bank angle; rise PT1-smoothed");
    {
      FpvConfig cfg = new FpvConfig();
      java.util.function.Function<Float,Quaternionf> bank = (rollDeg) ->
        new Quaternionf().rotationYXZ((float)Math.PI, 0f, -(float)Math.toRadians(rollDeg));

      // rise smoothing: a FRESH controller at level+center should start weak and build up.
      dev.fpv.flight.AngleController fresh = new dev.fpv.flight.AngleController();
      fresh.horizonRates(sticks(), bank.apply(0f), (float) DT, cfg);
      float sFirst = fresh.getHorizonStrength();

      dev.fpv.flight.AngleController ac = new dev.fpv.flight.AngleController();
      StickChannels c = sticks();
      float[] str = new float[3];
      float[] banks = new float[]{0f, 60f, 120f};
      for (int b = 0; b < 3; b++) {
        for (int i = 0; i < 500; i++) ac.horizonRates(c, bank.apply(banks[b]), (float) DT, cfg);
        str[b] = ac.getHorizonStrength();
      }
      StickChannels s2 = sticks(); s2.roll = 1f;
      for (int i = 0; i < 200; i++) ac.horizonRates(s2, bank.apply(0f), (float) DT, cfg);
      float sStick = ac.getHorizonStrength();
      System.out.printf("    strength  level=%.2f  bank60=%.2f  bank120=%.2f  fullstick=%.2f  firstStep=%.3f%n",
        str[0], str[1], str[2], sStick, sFirst);
      check("level attitude -> near-full leveling (~1)", str[0] > 0.9f, "lvl="+str[0]);
      check("leveling fades as bank grows (1 > 60 > 120 deg)", str[0] > str[1] && str[1] > str[2],
        "1=" + String.format("%.2f",str[0])+" 60="+String.format("%.2f",str[1])+" 120="+String.format("%.2f",str[2]));
      check("bank60 strength ~ (135-60)/135=0.56", Math.abs(str[1]-0.56f) < 0.12f, "b60="+str[1]);
      check("full stick -> acro (strength ~0)", sStick < 0.05f, "stk="+sStick);
      check("rise is PT1-smoothed (first step << settled)", sFirst < 0.3f && sFirst < str[0], "first="+sFirst);
    }

    // ---------- 24. Angle mode max inclination = 60 deg (BF angle_limit) ----------
    System.out.println("\n[24] ANGLE mode: default max inclination = 60 deg");
    {
      FpvConfig cfg = new FpvConfig();
      check("default angleMaxDeg == 60", Math.abs(cfg.getAngleMaxDeg()-60f) < 0.01f, "max="+cfg.getAngleMaxDeg());
      dev.fpv.flight.AngleController ac = new dev.fpv.flight.AngleController();
      StickChannels full = sticks(); full.roll = 1f; full.pitch = 1f;
      ac.angleRates(full, new Quaternionf(), (float) DT, cfg);
      check("full stick commands target inclination clamped to 60 deg",
        Math.abs(ac.getTargetRollDeg()-60f) < 0.5f && Math.abs(ac.getTargetPitchDeg()-60f) < 0.5f,
        "r="+ac.getTargetRollDeg()+" p="+ac.getTargetPitchDeg());
    }

    // ---------- 25. Default PID matches Betaflight published values ----------
    System.out.println("\n[25] Default PID == BF (R 45/80/30/120, P 47/84/34/125, Y 45/80/0/120)");
    {
      FpvConfig cfg = new FpvConfig();
      var rollP = cfg.getPid().getRoll(); var pitchP = cfg.getPid().getPitch(); var yawP = cfg.getPid().getYaw();
      System.out.printf("    R %.0f/%.0f/%.0f/%.0f  P %.0f/%.0f/%.0f/%.0f  Y %.0f/%.0f/%.0f/%.0f%n",
        rollP.getP(),rollP.getI(),rollP.getD(),rollP.getF(), pitchP.getP(),pitchP.getI(),pitchP.getD(),pitchP.getF(), yawP.getP(),yawP.getI(),yawP.getD(),yawP.getF());
      check("roll P/I/D/F = 45/80/30/120", rollP.getP()==45f&&rollP.getI()==80f&&rollP.getD()==30f&&rollP.getF()==120f, "");
      check("pitch P/I/D/F = 47/84/34/125", pitchP.getP()==47f&&pitchP.getI()==84f&&pitchP.getD()==34f&&pitchP.getF()==125f, "");
      check("yaw P/I/D/F = 45/80/0/120 (yaw D=0)", yawP.getP()==45f&&yawP.getI()==80f&&yawP.getD()==0f&&yawP.getF()==120f, "");
    }

    // ---------- 26. OSD registry: layout round-trip + per-element toggle ----------
    System.out.println("\n[26] OSD data-driven registry: layout round-trip, unit, off=skip");
    {
      // default layout derived from registry, no orphan ids
      var layout = dev.fpv.client.osd.OsdLayout.INSTANCE.defaultLayout();
      check("default layout covers every registry element",
        layout.size() == dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().size(),
        "n="+layout.size());

      // round-trip: mutate position/enabled/unitOverride, serialize, reload.
      for (var e : layout) { if (e.getId().equals(dev.fpv.flight.OsdElements.SPEED)) { e.setEnabled(false); e.setX(77); e.setY(31); e.setUnitOverride("IMPERIAL"); } }
      com.google.gson.Gson gson = new com.google.gson.Gson();
      String json = gson.toJson(layout);
      java.lang.reflect.Type t = new com.google.gson.reflect.TypeToken<java.util.List<dev.fpv.client.osd.OsdElement>>(){}.getType();
      java.util.List<dev.fpv.client.osd.OsdElement> back = gson.fromJson(json, t);
      var spd = back.stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.SPEED)).findFirst().get();
      check("layout round-trip preserves position", spd.getX()==77 && spd.getY()==31, "x="+spd.getX()+" y="+spd.getY());
      check("layout round-trip preserves enabled=false (off=not drawn)", !spd.getEnabled(), "en="+spd.getEnabled());
      check("layout round-trip preserves per-element unitOverride", "IMPERIAL".equals(spd.getUnitOverride()), "u="+spd.getUnitOverride());
      check("all other ids still present after round-trip", back.size()==layout.size(), "n="+back.size());

      // off=skip: a disabled element's record survives as enabled=false; FpvOsd skips it.
      check("disabled speed element enabled flag false persists", !spd.getEnabled(), "");
    }

    // ---------- 27. OSD unit conversions + V/mAh/W formatting ----------
    System.out.println("\n[27] OSD units: km/h<->mph, m<->ft, V/mAh/W");
    {
      var M = dev.fpv.flight.OsdUnit.METRIC; var I = dev.fpv.flight.OsdUnit.IMPERIAL;
      double kmh = dev.fpv.flight.OsdUnit.Companion.speedDisplay(10f, M);
      double mph = dev.fpv.flight.OsdUnit.Companion.speedDisplay(10f, I);
      double ft  = dev.fpv.flight.OsdUnit.Companion.distanceDisplay(100f, I);
      System.out.printf("    10 m/s = %.1f km/h  = %.1f mph ; 100 m = %.1f ft%n", kmh, mph, ft);
      check("10 m/s -> 36 km/h", Math.abs(kmh-36.0)<0.1, "kmh="+kmh);
      check("km/h -> mph factor (36*0.6214)", Math.abs(mph-22.37)<0.5, "mph="+mph);
      check("100 m -> 328 ft", Math.abs(ft-328.084)<1.0, "ft="+ft);

      // text rendering: speed label flips unit; power = V*A; battery shows V.
      var tel = new dev.fpv.flight.OsdTelemetry(0,0,0,0, 10f, 0f,0f,0f,0f,0f,0f,
        16.8f, 4.2f, 82f, 10f, 400f, new float[0], new float[0],
        96f, 0.45f, false, false, "ACRO", true, 123f, dev.fpv.flight.BatteryStage.OK, false, false);
      var speed = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.SPEED)).findFirst().get();
      String sMet = dev.fpv.flight.OsdFormatter.INSTANCE.text(speed, tel, M);
      String sImp = dev.fpv.flight.OsdFormatter.INSTANCE.text(speed, tel, I);
      System.out.println("    speed metric='"+sMet+"' imperial='"+sImp+"'");
      check("speed metric shows km/h", sMet.contains("km/h"), sMet);
      check("speed imperial shows mph", sImp.contains("mph") && !sImp.contains("km/h"), sImp);
      check("power W = V*A = 168W", tel.getWatts()==168.0f, "W="+tel.getWatts());
      var batt = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.BATTERY)).findFirst().get();
      String btxt = dev.fpv.flight.OsdFormatter.INSTANCE.text(batt, tel, M);
      check("battery text carries V and %", btxt.contains("V") && btxt.contains("%"), btxt);
      System.out.println("    battery='"+btxt+"'");
    }

    // ---------- 28. Every TEXT registry element renders; non-text return null ----------
    System.out.println("\n[28] Registry completeness: each TEXT element produces text, graphics elements null");
    {
      var tel = new dev.fpv.flight.OsdTelemetry();
      int textOk = 0, graphicsNull = 0;
      for (var spec : dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY()) {
        String s = dev.fpv.flight.OsdFormatter.INSTANCE.text(spec, tel, dev.fpv.flight.OsdUnit.METRIC);
        if (spec.getRenderer() == dev.fpv.flight.OsdRenderer.TEXT) { if (s != null) textOk++; }
        else { if (s == null) graphicsNull++; }
      }
      long textTotal = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getRenderer()==dev.fpv.flight.OsdRenderer.TEXT).count();
      long graphTotal = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().size() - textTotal;
      // 3 TEXT elements are conditional: HOME(no arm), CRAFT_NAME(empty), LAP_TIME(no lap) -> null on no-signal.
      long conditionalNull = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream()
        .filter(x->x.getRenderer()==dev.fpv.flight.OsdRenderer.TEXT)
        .filter(x-> { String s=dev.fpv.flight.OsdFormatter.INSTANCE.text(x, tel, dev.fpv.flight.OsdUnit.METRIC); return s==null; })
        .count();
      System.out.println("    text-rendered="+textOk+"/"+textTotal+"  graphics-null="+graphicsNull+"/"+graphTotal+"  conditional-null="+conditionalNull);
      check("TEXT elements render except the 3 no-signal conditionals", textOk == (int)(textTotal-conditionalNull) && conditionalNull==3, "ok="+textOk+"/"+textTotal+" cond="+conditionalNull);
      check("graphics/banner elements return null text", graphicsNull == (int)graphTotal, "null="+graphicsNull+"/"+graphTotal);
    }

    // ---------- 29. B2 batch: vario / heading / home / motor-rpm / remaining / efficiency ----------
    System.out.println("\n[29] B2 OSD elements: vario sign, heading+compass, home, motor%, rpm, ETE, efficiency");
    {
      var M = dev.fpv.flight.OsdUnit.METRIC; var I = dev.fpv.flight.OsdUnit.IMPERIAL;
      // base telemetry (28 positional args, new fields defaulted/named)
      var base = new dev.fpv.flight.OsdTelemetry(0,0,0,0, 5f, 12f, 1.5f, 0,0,0,0,
        16.8f, 4.2f, 80f, 10f, 400f, new float[]{6000f,6000f,-6000f,-6000f}, new float[]{0.2f,-0.2f,0.2f,-0.2f},
        96f, 0.45f, false, false, "ACRO", true, 123f, dev.fpv.flight.BatteryStage.OK, false, false);

      // vario: climb shows up-arrow, sink down-arrow, unit switch m/s<->f/s
      var vario = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.VARIO)).findFirst().get();
      String tUp = dev.fpv.flight.OsdFormatter.INSTANCE.text(vario, base.withVario(2.5f), M);
      String tDn = dev.fpv.flight.OsdFormatter.INSTANCE.text(vario, base.withVario(-2.5f), M);
      String tFt = dev.fpv.flight.OsdFormatter.INSTANCE.text(vario, base.withVario(2.5f), I);
      System.out.println("    vario up='"+tUp+"' down='"+tDn+"' imp='"+tFt+"'");
      check("vario climb shows up-arrow", tUp.contains("▲"), tUp);
      check("vario sink shows down-arrow", tDn.contains("▼"), tDn);
      check("vario imperial switches to f/s", tFt.contains("f/s") && !tFt.contains("m/s"), tFt);

      // heading + compass letter
      var heading = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.HEADING)).findFirst().get();
      String hd90 = dev.fpv.flight.OsdFormatter.INSTANCE.text(heading, base.withHeading(90f), M);
      String hd45 = dev.fpv.flight.OsdFormatter.INSTANCE.text(heading, base.withHeading(45f), M);
      System.out.println("    heading 90='"+hd90+"' 45='"+hd45+"'");
      check("heading 90 -> 090E", hd90.equals("090E"), hd90);
      check("heading 45 -> 045NE", hd45.equals("045NE"), hd45);

      // home: bearing+dist, hidden without home
      var home = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.HOME)).findFirst().get();
      String ht = dev.fpv.flight.OsdFormatter.INSTANCE.text(home, base.withHome(90f, 10f), M);
      String nt = dev.fpv.flight.OsdFormatter.INSTANCE.text(home, base.withNoHome(), M);
      System.out.println("    home='"+ht+"' nohome='"+nt+"'");
      check("home shown with bearing letter + dist m", ht.contains("E") && ht.contains("10m"), ht);
      check("home hidden until arm (no home)", nt == null, "null expected");

      // motor diag: signed mixer; esc rpm = |motor|*maxRpm >0
      check("mixerOut carries signed 3D values", base.getMixerOut()[1] < 0f && base.getMixerOut()[0] > 0f, "m0="+base.getMixerOut()[0]+" m1="+base.getMixerOut()[1]);
      check("ESC rpm model-derived >0 from motorRpm[0]", base.getMotorRpm()[0] > 0f, "rpm="+base.getMotorRpm()[0]);
      var esc = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.ESC_RPM)).findFirst().get();
      String et = dev.fpv.flight.OsdFormatter.INSTANCE.text(esc, base, M);
      System.out.println("    escRpm='"+et+"'");
      check("ESC_RPM text carries rpm number", et.trim().length() > 4, et);

      // remaining time positive & scales with discharge rate; efficiency
      var rem = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.REMAINING_TIME)).findFirst().get();
      String ra = dev.fpv.flight.OsdFormatter.INSTANCE.text(rem, base.withRemaining(300f), M);
      String rb = dev.fpv.flight.OsdFormatter.INSTANCE.text(rem, base.withRemaining(600f), M);
      System.out.println("    ETE slow='"+ra+"' fast='"+rb+"'");
      check("remaining time positive mm:ss", ra.startsWith("ETE"), ra);
      check("remaining time grows as discharge slows (600s -> 10:00)", rb.equals("ETE 10:00"), ra+" vs "+rb);
      var eff = dev.fpv.flight.OsdElements.INSTANCE.getREGISTRY().stream().filter(x->x.getId().equals(dev.fpv.flight.OsdElements.EFFICIENCY)).findFirst().get();
      String ef = dev.fpv.flight.OsdFormatter.INSTANCE.text(eff, base.withEfficiency(3.6f), M);
      check("efficiency text carries km/h·A", ef.contains("EFF") && ef.contains("km/h"), ef);
    }

    // ---------- 30. Every B2 element is in the registry, addable, round-trips ----------
    System.out.println("\n[30] B2 registry coverage: new elements registered, addable, round-trip");
    {
      var need = java.util.List.of("vario","heading","altitude","speed_bar","altitude_bar","motor_diag",
        "remaining_time","esc_rpm","compass_bar","home","efficiency","timer2","lap_time","craft_name","power");
      int found = 0;
      for (String id : need) if (dev.fpv.flight.OsdElements.INSTANCE.byId(id) != null) found++;
      check("all 15 B2 elements registered", found == need.size(), "found="+found+"/"+need.size());

      // each new element has a default layout record (addable + toggleable)
      var layout = dev.fpv.client.osd.OsdLayout.INSTANCE.defaultLayout();
      int present = 0;
      for (String id : need) if (layout.stream().anyMatch(e->e.getId().equals(id))) present++;
      check("all B2 elements present in default layout (addable)", present == need.size(), "present="+present);

      // toggle one off, round-trip preserves enabled=false
      for (var e : layout) if (e.getId().equals("vario")) e.setEnabled(false);
      var gson = new com.google.gson.Gson();
      var typ = new com.google.gson.reflect.TypeToken<java.util.List<dev.fpv.client.osd.OsdElement>>(){}.getType();
      var back = gson.<java.util.List<dev.fpv.client.osd.OsdElement>>fromJson(gson.toJson(layout), typ);
      var varioEl = back.stream().filter(x->x.getId().equals("vario")).findFirst().get();
      check("new-element toggle off survives round-trip", !varioEl.getEnabled(), "en="+varioEl.getEnabled());
    }

    // ---------- 31. Replay recorder v2: field round-trip, motor/cam/events, atomic, NaN-free ----------
    System.out.println("\n[31] Flight recorder v2: write->read round-trip, events, atomic, no NaN");
    {
      var recs = new java.util.ArrayList<dev.fpv.replay.ReplayFile.Rec>();
      for (int i = 0; i < 50; i++) {
        var rr = new dev.fpv.replay.ReplayFile.Rec();
        rr.setTSec(i * 0.01); rr.setX(i + 0.5); rr.setY(10.0 + i); rr.setZ(-3.0);
        rr.setQx(0.1f * i); rr.setQy(0.2f); rr.setQz(0.3f); rr.setQw(0.9f);
        rr.setVx(0.01f * i); rr.setVy(-0.02f); rr.setVz(0.03f);
        rr.setGx(10f + i); rr.setGy(20f); rr.setGz(30f);
        rr.setVbat(24f);
        rr.setLq(95f);
        rr.setRollCmd(0.1f * i); rr.setPitchCmd(-0.2f); rr.setYawCmd(0.3f); rr.setThrCmd(0.5f);
        rr.setArmed(true); rr.setModeCode(i % 3);
        for (int k = 0; k < 4; k++) rr.getMotor()[k] = 0.1f * (i + k);
        rr.setCamFovDeg(75f); rr.setCamTiltDeg(25f); rr.setPhase((i % 10) / 10f);
        recs.add(rr);
      }
      var evs = java.util.List.of(
        new dev.fpv.replay.ReplayEvent(0.0, dev.fpv.replay.ReplayEvent.ARM, 0),
        new dev.fpv.replay.ReplayEvent(0.5, dev.fpv.replay.ReplayEvent.GATE, 2),
        new dev.fpv.replay.ReplayEvent(0.9, dev.fpv.replay.ReplayEvent.DISARM, 0));

      java.nio.file.Path tmp;
      try { tmp = java.nio.file.Files.createTempDirectory("fpr"); }
      catch (java.io.IOException ex) { throw new RuntimeException(ex); }
      var out = tmp.resolve("roundtrip.fpr");
      dev.fpv.replay.ReplayFile.Companion.write(out, 120f, "2026-01-01T00:00:00", recs, evs);

      // no atomic temp leftover
      check("atomic write leaves no .tmp sibling", tmp.toFile().listFiles((f,n)->n.endsWith(".tmp")).length == 0, "tmp");
      check("file exists non-empty", out.toFile().length() > 0, "bytes="+out.toFile().length());

      var f = dev.fpv.replay.ReplayFile.Companion.read(out);
      check("sample count round-trips (50)", f.getCount() == 50, "n="+f.getCount());
      check("x[5] round-trips", Math.abs(f.getPx()[5] - 5.5) < 1e-9, "x="+f.getPx()[5]);
      check("gx[10] round-trips", Math.abs(f.getGx()[10] - 20f) < 1e-6, "gx="+f.getGx()[10]);
      check("motor[0][3] round-trips", Math.abs(f.getMotor()[0][3] - 0.3f) < 1e-6, "m03="+f.getMotor()[0][3]);
      check("camTilt[0]=25", Math.abs(f.getCamTiltDeg()[0] - 25f) < 1e-6, "tilt="+f.getCamTiltDeg()[0]);
      check("phase[7]=0.7", Math.abs(f.getPhase()[7] - 0.7f) < 1e-6, "ph="+f.getPhase()[7]);
      check("events round-trip (3)", f.getEvents().size() == 3, "ev="+f.getEvents().size());
      check("event[1] is GATE@2", f.getEvents().get(1).getCode()==dev.fpv.replay.ReplayEvent.GATE && f.getEvents().get(1).getArg()==2, f.getEvents().get(1).toString());

      // no NaN/Inf in read back
      boolean nan = false;
      for (float fl : f.getGx()) if (!Float.isFinite(fl)) nan = true;
      for (float fl : f.getMotor()[0]) if (!Float.isFinite(fl)) nan = true;
      check("read-back contains no NaN/Inf", !nan, "");

      // cleanFloat guard
      check("cleanFloat(NaN)=0", dev.fpv.replay.ReplayFile.Companion.cleanFloat(Float.NaN) == 0f, "");
      check("cleanFloat(Inf)=0", dev.fpv.replay.ReplayFile.Companion.cleanFloat(Float.POSITIVE_INFINITY) == 0f, "");
      check("cleanFloat(3.5)=3.5", dev.fpv.replay.ReplayFile.Companion.cleanFloat(3.5f) == 3.5f, "");

      // interpolation still works
      var s = new dev.fpv.replay.ReplaySample(0.0,0.0,0.0,0.0, 0f,0f,0f,1f, 0f,0f,0f, 0f,0f,0f, 0f,0f,0f, 0f,0f,0f, 0f,0f,0f,0f, false,0,new float[32]);
      f.sampleAt(0.25, s);
      check("sampleAt interpolated attitude finite", Float.isFinite(s.getQx()) && s.getTSec()>0, "qx="+s.getQx());
      System.out.println("    wrote/read="+f.getCount()+" events="+f.getEvents().size()+" x5="+f.getPx()[5]+" m03="+f.getMotor()[0][3]);
    }

    // ---------- 32. Cinematic keyframe track: spline through points, interp switch, slerp, sidecar, aspect ----------
    System.out.println("\n[32] Cinematic track: Catmull-Rom/Linear/Cubic, slerp, sidecar round-trip, canvas");
    {
      var track = new dev.fpv.replay.CinematicTrack();
      // 4 keyframes on a gentle arc: x grows, y dips. CR passes exactly through each keyframe.
      track.add(new dev.fpv.replay.TrackKeyframe(0.0, 0.0, 0.0, 0.0,  0f,0f,0f,1f, dev.fpv.replay.TrackInterp.CATMULL_ROM));
      track.add(new dev.fpv.replay.TrackKeyframe(1.0, 10.0, 2.0, 0.0,  0f,0f,0f,1f, dev.fpv.replay.TrackInterp.CATMULL_ROM));
      track.add(new dev.fpv.replay.TrackKeyframe(2.0, 20.0, 1.0, 0.0,  0f,0f,0f,1f, dev.fpv.replay.TrackInterp.CATMULL_ROM));
      track.add(new dev.fpv.replay.TrackKeyframe(3.0, 30.0, 3.0, 0.0,  0f,0f,0f,1f, dev.fpv.replay.TrackInterp.CATMULL_ROM));
      var pose = new dev.fpv.replay.TrackPose();

      // exact pass-through at keyframe times
      track.sample(1.0, pose);
      check("CR passes through keyframe t=1 (x=10)", Math.abs(pose.getX()-10.0)<1e-6, "x="+pose.getX());
      track.sample(2.0, pose);
      check("CR passes through keyframe t=2 (y=1)", Math.abs(pose.getY()-1.0)<1e-6, "y="+pose.getY());

      // mid-sample finite & smooth (monotonic x between 0 and 30)
      double prevX = -1; boolean mono = true; boolean finite = true; boolean bounded = true;
      for (int ms = 0; ms <= 300; ms++) {
        double t = ms * 0.01;
        track.sample(t, pose);
        if (!Double.isFinite(pose.getX()) || !Double.isFinite(pose.getY())) finite = false;
        if (pose.getX() < prevX - 0.5) mono = false;
        if (pose.getX() < -1e-6 || pose.getX() > 30.0001) bounded = false;
        prevX = pose.getX();
      }
      System.out.println("    CR t=1.5 -> x="+String.format("%.3f", trackSampleX(track,1.5))+" finite="+finite+" monoX="+mono);
      check("CR mid-samples finite", finite, "");
      check("CR x monotonic-ish (no self-intersection)", mono, "");
      check("CR stays within endpoint range (no overshoot)", bounded, "x="+prevX);
      check("CR t=1.5 x between 10 and 20", trackSampleX(track,1.5)>10.0 && trackSampleX(track,1.5)<20.0, "x="+trackSampleX(track,1.5));

      // Linear interp: exactly straight between k1,k2
      var lin = new dev.fpv.replay.CinematicTrack();
      lin.add(new dev.fpv.replay.TrackKeyframe(0.0, 0.0,0.0,0.0, 0f,0f,0f,1f, dev.fpv.replay.TrackInterp.LINEAR));
      lin.add(new dev.fpv.replay.TrackKeyframe(2.0, 100.0,0.0,0.0, 0f,0f,0f,1f, dev.fpv.replay.TrackInterp.LINEAR));
      lin.add(new dev.fpv.replay.TrackKeyframe(4.0, 0.0,0.0,0.0, 0f,0f,0f,1f, dev.fpv.replay.TrackInterp.LINEAR));
      lin.sample(1.0, pose);
      check("Linear t=1 -> x=50 (straight)", Math.abs(pose.getX()-50.0)<1e-6, "x="+pose.getX());

      // Cubic interp finite & passes endpoints
      lin.getKeyframes().get(1).setInterp(dev.fpv.replay.TrackInterp.CUBIC);
      lin.sample(1.0, pose);
      check("Cubic finite at midpoint", Double.isFinite(pose.getX()) && pose.getX()>0, "x="+pose.getX());

      // orientation slerp: two different quats, midpoint = shortest-arc
      var sl = new dev.fpv.replay.CinematicTrack();
      sl.add(new dev.fpv.replay.TrackKeyframe(0.0, 0.0,0.0,0.0, 0f,0f,0f,1f, dev.fpv.replay.TrackInterp.LINEAR));
      sl.add(new dev.fpv.replay.TrackKeyframe(2.0, 0.0,0.0,0.0, 0.7071f,0f,0f,0.7071f, dev.fpv.replay.TrackInterp.LINEAR));
      sl.sample(1.0, pose);
      System.out.println("    slerp mid q=("+String.format("%.3f,%.3f,%.3f,%.3f", pose.getQ().x, pose.getQ().y, pose.getQ().z, pose.getQ().w)+")");
      check("slerp midpoint w finite ~0.92", Math.abs(pose.getQ().w - 0.9239f) < 0.02, "w="+pose.getQ().w);

      // sidecar save -> load round-trip
      java.nio.file.Path tmp;
      try { tmp = java.nio.file.Files.createTempDirectory("fpr2"); }
      catch (java.io.IOException ex) { throw new RuntimeException(ex); }
      var fpr = tmp.resolve("rec_x.fpr");
      dev.fpv.replay.CinematicTrack.Companion.saveSidecar(fpr, track);
      var loaded = dev.fpv.replay.CinematicTrack.Companion.loadSidecar(fpr);
      check("sidecar round-trip keeps 4 keyframes", loaded.getKeyframes().size()==4, "n="+loaded.getKeyframes().size());
      check("sidecar round-trip t=3 x=30", Math.abs(loaded.getKeyframes().get(3).getX()-30.0)<1e-6, "x="+loaded.getKeyframes().get(3).getX());
      check("no sidecar .tmp leftover", tmp.toFile().listFiles((f,n)->n.endsWith(".tmp")).length==0, "");

      // canvas aspect math
      var c169 = dev.fpv.replay.CinematicExport.INSTANCE.canvasFor("1080p","16:9");
      var c239 = dev.fpv.replay.CinematicExport.INSTANCE.canvasFor("1080p","2.39:1");
      var c11  = dev.fpv.replay.CinematicExport.INSTANCE.canvasFor("1080p","1:1");
      var c45  = dev.fpv.replay.CinematicExport.INSTANCE.canvasFor("1080p","4:5");
      System.out.println("    canvas 16:9="+c169.getFirst()+"x"+c169.getSecond()+" 2.39:1="+c239.getFirst()+"x"+c239.getSecond()+" 1:1="+c11.getFirst()+"x"+c11.getSecond()+" 4:5="+c45.getFirst()+"x"+c45.getSecond());
      check("16:9 1080p = 1920x1080", c169.getFirst()==1920 && c169.getSecond()==1080, c169.toString());
      check("1:1 1080p = 1080x1080", c11.getFirst()==1080 && c11.getSecond()==1080, c11.toString());
      check("4:5 1080p = 864x1080", c45.getFirst()==864 && c45.getSecond()==1080, c45.toString());
      check("all canvas dims even", (c239.getFirst()&1)==0 && (c239.getSecond()&1)==0, "");

      // RC overlay mapping
      var sp = dev.fpv.replay.StickOverlay.INSTANCE.stickPos(0f, 0f);
      check("stick centered -> (0.5,0.5)", Math.abs(sp.getFirst()-0.5f)<1e-6 && Math.abs(sp.getSecond()-0.5f)<1e-6, sp.toString());
      var sp2 = dev.fpv.replay.StickOverlay.INSTANCE.stickPos(1f, -1f);
      check("stick full-right/up -> (1.0, 0.0)", Math.abs(sp2.getFirst()-1f)<1e-6 && Math.abs(sp2.getSecond()-0f)<1e-6, sp2.toString());
      check("throttle mid -> 0.5", Math.abs(dev.fpv.replay.StickOverlay.INSTANCE.throttleFill(0f)-0.5f)<1e-6, "");
    }

    // ---------- 33. P-C: FrameConsumer, ffmpeg template + injection guard, PNG sequence, camera JSON exchange ----------
    System.out.println("\n[33] P-C: ffmpeg template/token + injection guard, PNG frames, camera JSON");
    {
      var fc = dev.fpv.replay.FfmpegCommand.INSTANCE;
      String tpl = "-y -f rawvideo -pix_fmt rgb24 -s %WIDTH%x%HEIGHT% -r %FPS% -i - -an -c:v libx264 -pix_fmt %PIXELFMT% %FILENAME%";
      var cmd = fc.build("ffmpeg", tpl, 1920, 1080, 30, "yuv420p", "replay_1.mp4");
      System.out.println("    cmd="+String.join(" ", cmd));
      check("template substitutes width/height", cmd.contains("1920x1080"), cmd.toString());
      check("template substitutes fps", cmd.contains("30"), cmd.toString());
      check("template substitutes pixfmt", cmd.contains("yuv420p"), cmd.toString());
      check("filename is its own arg", cmd.get(cmd.size()-1).equals("replay_1.mp4"), "tail="+cmd.get(cmd.size()-1));

      // injection guard: hostile filename with flags stays ONE argv element, never a standalone flag.
      var evil = fc.build("ffmpeg", tpl, 1920, 1080, 30, "yuv420p", "re.mp4 -vb 1000k");
      boolean standaloneFlag = evil.stream().anyMatch(tok -> tok.equals("-vb") || tok.equals("1000k"));
      check("injection filename never becomes standalone flag args", !standaloneFlag, evil.toString());
      check("sanitize strips path separators", fc.sanitizeFilename("../etc/passwd").equals("passwd"), fc.sanitizeFilename("../etc/passwd"));

      // PNG consumer writes continuously numbered frames.
      java.nio.file.Path pngDir;
      try { pngDir = java.nio.file.Files.createTempDirectory("pngseq"); }
      catch (java.io.IOException ex) { throw new RuntimeException(ex); }
      var png = new dev.fpv.replay.PngFrameConsumer(pngDir);
      byte[] rgb = new byte[4*4*3];
      png.consume(rgb, 4, 4); png.consume(rgb, 4, 4); png.consume(rgb, 4, 4);
      png.close();
      String[] names = pngDir.toFile().list((d,n)->n.endsWith(".png"));
      java.util.Arrays.sort(names);
      check("PNG sequence 3 frames numbered", names.length==3 && names[0].equals("frame_000001.png") && names[2].equals("frame_000003.png"), java.util.Arrays.toString(names));

      // camera JSON exchange: frames = fps * duration.
      var recs = new java.util.ArrayList<dev.fpv.replay.ReplayFile.Rec>();
      for (int i=0;i<60;i++){ var rr=new dev.fpv.replay.ReplayFile.Rec(); rr.setTSec(i*0.02); rr.setX(i); rr.setQw(1f); recs.add(rr); }
      java.nio.file.Path tmp;
      try { tmp = java.nio.file.Files.createTempDirectory("cpath"); }
      catch (java.io.IOException ex) { throw new RuntimeException(ex); }
      var fpr = tmp.resolve("r.fpr");
      dev.fpv.replay.ReplayFile.Companion.write(fpr, 50f, "t", recs, java.util.List.of());
      var f = dev.fpv.replay.ReplayFile.Companion.read(fpr);
      var cpath = tmp.resolve("camera.json");
      int n = dev.fpv.replay.CameraPathExporter.INSTANCE.export(f, null, 30, cpath);
      long durMs = (long)(f.getDurationSec()*1000);
      System.out.println("    camera.json frames="+n+" durationMs="+durMs);
      check("camera JSON frames = fps*duration", n == (int)Math.floor(f.getDurationSec()*30), "n="+n);
      String json;
      try { json = java.nio.file.Files.readString(cpath); }
      catch (java.io.IOException ex) { throw new RuntimeException(ex); }
      check("camera JSON has fps + frames", json.contains("\"fps\"") && json.contains("frames") && json.contains("\"t\""), json.substring(0, Math.min(80,json.length())));
      check("camera JSON atomic (no .tmp leftover)", tmp.toFile().listFiles((d,x)->x.endsWith(".tmp")).length==0, "");
    }

    // ---------- 34. Emergent aerobatics (REAL closed loop, sticks-only) ----------
    // No scripted trajectory: only a stick time-series + initial velocity; every attitude,
    // translation and energy change emerges from attitude->thrust->translation + gravity +
    // drag. Metrics below are the *emergence* evidence, not fixed trajectories.
    System.out.println("\n[34] emergent aerobatics (REAL closed loop, sticks-only)");
    {
      FpvConfig cfg = new FpvConfig();
      cfg.setPhysicsRealism("REAL");
      if (cfg.getPid() != null) cfg.getPid().setEnabled(true);
      cfg.setSetpointSmoothingEnabled(false);
      var aero = new dev.fpv.flight.Aerobatics(cfg);
      float hov = cfg.activeAirframe().effectiveHoverThrottle();
      System.out.println("    hoverThrottle="+hov);
      boolean anyNan=false;

      // (1) matty flip: brief full-roll stick, then release -> inertia completes the 360.
      var roll = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.1, 1f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.8, 1f,0f,0f,hov));
      var tMatty = aero.run(roll, (float)DT, 0,0,0, 0.5);
      float maxRoll=0f, minUp=1f;
      for (var s: tMatty.getSamples()){ maxRoll=Math.max(maxRoll,Math.abs(s.getRollAccumDeg())); minUp=Math.min(minUp,s.getBodyUpY()); }
      anyNan |= tMatty.getNan();
      System.out.printf("    matty: |rollAccum|=%.1f deg minUp=%.2f%n", maxRoll, minUp);
      check("matty flip completes a full roll (>=360 deg, momentum-assisted)", maxRoll>=360f, String.valueOf(maxRoll));
      check("matty passes fully inverted (minUp<-0.9)", minUp<-0.9f, String.valueOf(minUp));

      // (2) power loop: pitch stick + forward momentum -> vertical plane 360.
      var loop = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.1, 0f,-1f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(1.6, 0f,-1f,0f,hov));
      var tLoop = aero.run(loop, (float)DT, 6,0,0, 0.4);
      float maxP=0f, minUpL=1f;
      for (var s: tLoop.getSamples()){ maxP=Math.max(maxP,Math.abs(s.getPitchAccumDeg())); minUpL=Math.min(minUpL,s.getBodyUpY()); }
      anyNan |= tLoop.getNan();
      System.out.printf("    powerloop: |pitchAccum|=%.1f deg minUp=%.2f v0=%.2f vEnd=%.2f%n", maxP, minUpL, tLoop.getStartSpeed(), tLoop.getEndSpeed());
      check("power loop completes a vertical 360 (>=360 deg)", maxP>=360f, String.valueOf(maxP));
      check("power loop passes inverted", minUpL<-0.9f, String.valueOf(minUpL));

      // (3) energy: nose-down dive (throttle cut) retains MORE speed than a level glide ->
      //     gravity does work on the dive; drag bleeds both (drag-heavy plant).
      var dive = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.1, 0f,-1f,0f,0.05f),
        new dev.fpv.flight.Aerobatics.Frame(1.2, 0f,-1f,0f,0.05f));
      var td = aero.run(dive, (float)DT, 4,0,0, 0.0);
      var glide = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.1, 0f,0f,0f,0.05f),
        new dev.fpv.flight.Aerobatics.Frame(1.2, 0f,0f,0f,0.05f));
      var tg = aero.run(glide, (float)DT, 4,0,0, 0.0);
      anyNan |= td.getNan() || tg.getNan();
      System.out.printf("    dive endSpeed=%.2f vs level glide endSpeed=%.2f (gravity adds: dive>glide)%n", td.getEndSpeed(), tg.getEndSpeed());
      check("energy: dive keeps more speed than level glide (gravity contribution)", td.getEndSpeed() > tg.getEndSpeed(),
        String.format("dive=%.2f glide=%.2f", td.getEndSpeed(), tg.getEndSpeed()));

      // (4) initial-condition sensitivity: identical banked-turn stick at two entry speeds ->
      //     materially different altitude response (g*tan(phi)/V geometry: faster = bigger radius).
      var turn = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0.5f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.8, 0.5f,0f,0f,hov));
      var tSlow = aero.run(turn, (float)DT, 0.5,0,0, 1.5);
      var tFast = aero.run(turn, (float)DT, 6.0,0,0, 1.5);
      anyNan |= tSlow.getNan() || tFast.getNan();
      double altSlow = tSlow.getSamples().get(tSlow.getSamples().size()-1).getAltY();
      double altFast = tFast.getSamples().get(tFast.getSamples().size()-1).getAltY();
      System.out.printf("    turn v0=0.5 altEnd=%.2f vs v0=6.0 altEnd=%.2f (environment participates)%n", altSlow, altFast);
      check("initial-speed sensitivity: same sticks, different entry speed -> different result",
        Math.abs(altSlow-altFast) > 0.1, String.format("slow=%.2f fast=%.2f", altSlow, altFast));

      check("all maneuvers finite (no NaN / divergence)", !anyNan, "anyNan="+anyNan);
    }

    // ---------- 35. Remaining named freestyle moves (sticks-only, emergent) ----------
    System.out.println("\n[35] split-S / rubik's / inverted-recover / power-dive");
    {
      FpvConfig cfg = new FpvConfig();
      cfg.setPhysicsRealism("REAL");
      if (cfg.getPid() != null) cfg.getPid().setEnabled(true);
      cfg.setSetpointSmoothingEnabled(false);
      var aero = new dev.fpv.flight.Aerobatics(cfg);
      float hov = cfg.activeAirframe().effectiveHoverThrottle();
      boolean anyNan=false;

      // split-S: half-roll to inverted, then pull out (pitch) -> gravity carries the dive-out.
      var split = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.1, 1f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.45f, 1f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.55f, 0f,1f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(1.6, 0f,1f,0f,hov));
      var tSplit = aero.run(split, (float)DT, 4,0,0, 0.6);
      float minUpS=1f; float endUpS=0f;
      for (var s: tSplit.getSamples()) minUpS=Math.min(minUpS,s.getBodyUpY());
      endUpS = tSplit.getSamples().get(tSplit.getSamples().size()-1).getBodyUpY();
      anyNan |= tSplit.getNan();
      System.out.printf("    split-S: minUp=%.2f endUp=%.2f (inverted mid, recovering level)%n", minUpS, endUpS);
      check("split-S passes inverted mid-maneuver", minUpS<-0.9f, String.valueOf(minUpS));
      check("split-S pulls back toward level (endUp > inverted)", endUpS > minUpS+0.5f, String.valueOf(endUpS));

      // rubik's: combined roll+yaw on the spot (no entry speed) -> bounded drift, finite.
      var rubik = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.1, 0.8f,0f,0.8f,hov),
        new dev.fpv.flight.Aerobatics.Frame(1.2, 0.8f,0f,0.8f,hov));
      var tRubik = aero.run(rubik, (float)DT, 0,0,0, 0.3);
      double altRub = tRubik.getSamples().get(tRubik.getSamples().size()-1).getAltY();
      anyNan |= tRubik.getNan();
      System.out.printf("    rubik's: on-the-spot roll+yaw, altDrift=%.2f (bounded, no runaway)%n", altRub);
      check("rubik's stays near its own altitude (no translational runaway)", Math.abs(altRub) < 8.0, String.valueOf(altRub));

      // power dive: pitch down then pull up -> speeds up in the dive vs level, no crash oscillation.
      var pdive = java.util.List.of(
        new dev.fpv.flight.Aerobatics.Frame(0.0, 0f,0f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.1, 0f,-1f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(0.9, 0f,-1f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(1.0, 0f,1f,0f,hov),
        new dev.fpv.flight.Aerobatics.Frame(1.6, 0f,1f,0f,hov));
      var tPdiv = aero.run(pdive, (float)DT, 4,0,0, 0.3);
      anyNan |= tPdiv.getNan();
      System.out.printf("    power dive: v0=%.2f vEnd=%.2f%n", tPdiv.getStartSpeed(), tPdiv.getEndSpeed());

      check("all remaining maneuvers finite (no NaN/divergence)", !anyNan, "anyNan="+anyNan);
    }

    // ---------- 36. Module 1 optics math (barrel remap + vignette) ----------
    System.out.println("\n[36] module-1 optics math");
    {
      var opt = dev.fpv.flight.OpticsMath.INSTANCE;
      // center stays put under any K.
      float[] c = opt.barrelRemap(0.5f, 0.5f, -0.22f, 0f);
      check("barrel center identity", Math.abs(c[0]-0.5f)<1e-6 && Math.abs(c[1]-0.5f)<1e-6, c[0]+","+c[1]);
      // off (K1=K2=0) == identity everywhere.
      float[] edge = opt.barrelRemap(1.0f, 0.5f, 0f, 0f);
      check("optics off == identity at edge", Math.abs(edge[0]-1.0f)<1e-6 && Math.abs(edge[1]-0.5f)<1e-6, edge[0]+","+edge[1]);
      // negative K1 (wide lens) pulls the edge inward monotonically with radius.
      float[] e1 = opt.barrelRemap(0.75f, 0.5f, -0.22f, 0f);
      float[] e2 = opt.barrelRemap(1.0f, 0.5f, -0.22f, 0f);
      check("wide-lens edge pulled inward (0.75 -> <0.75)", e1[0]<0.75f, ""+e1[0]);
      check("edge displacement monotonic in radius", (e2[0]-0.5f) > (e1[0]-0.5f) && e2[0]<1.0f, e2[0]+"" );
      // vignette: edge darker than center.
      float vC = opt.vignette(0.5f, 0.5f, 0.35f);
      float vE = opt.vignette(1.0f, 0.5f, 0.35f);
      System.out.printf("    vig center=%.3f edge=%.3f%n", vC, vE);
      check("vignette edge darker than center", vE < vC, ""+vE);
      check("vignette off==1 (center)", Math.abs(vC-1f)<1e-6, ""+vC);
    }

    // ---------- 37. Module 2 signal model ----------
    System.out.println("\n[37] module-2 signal model");
    {
      var sig = dev.fpv.flight.SignalModel.INSTANCE;
      double lq0 = sig.linkQuality(0, 120f);
      double lqHalf = sig.linkQuality(120, 120f);
      double lqFar = sig.linkQuality(400, 120f);
      System.out.printf("    lq@0=%.2f lq@120=%.2f lq@400=%.2f%n", lq0, lqHalf, lqFar);
      check("lq clean at home (=1)", lq0 > 0.99, ""+lq0);
      check("lq ~0.5 at half-distance", Math.abs(lqHalf-0.5) < 0.02, ""+lqHalf);
      check("lq drops far from home", lqFar < lqHalf, ""+lqFar);
      double bad = sig.badness(lqFar);
      check("far -> high badness", bad > 0.5, ""+bad);
      check("blockCorrupt triggers beyond 0.25", sig.blockCorruptActive(0.3), "");
      check("frameFreeze triggers beyond 0.40", sig.frameFreezeActive(0.5), "");
      check("clean LQ stays clean tier", sig.tier(sig.badness(0.99)).equals("clean"), sig.tier(sig.badness(0.99)));
      check("off/clean -> no glitch tier", sig.tier(0.0).equals("clean"), sig.tier(0.0));
    }

    // ---------- 38. Module 3 motor tone + beep events ----------
    System.out.println("\n[38] module-3 motor tone / beep");
    {
      var tone = dev.fpv.flight.MotorTone.INSTANCE;
      float pIdle = tone.pitchForSpeed(0f, 0.8f, 2.5f);
      float pHalf = tone.pitchForSpeed(0.5f, 0.8f, 2.5f);
      float pFull = tone.pitchForSpeed(1f, 0.8f, 2.5f);
      System.out.printf("    pitch idle=%.2f half=%.2f full=%.2f%n", pIdle, pHalf, pFull);
      check("whine pitch monotonic in rpm", pIdle < pHalf && pHalf < pFull, pIdle+"<"+pHalf+"<"+pFull);
      check("whine full = base*fullMul", Math.abs(pFull-0.8f*2.5f)<1e-5, ""+pFull);
      check("signed 3D speed uses |m|", Math.abs(tone.pitchForSpeed(-1f,0.8f,2.5f)-pFull)<1e-5, "");
      check("beep ARM on arm", tone.event(3.9f,true,false,false)==dev.fpv.flight.MotorTone.Beep.ARM, "");
      check("beep DISARM on disarm", tone.event(3.9f,false,true,false)==dev.fpv.flight.MotorTone.Beep.DISARM, "");
      check("beep BAT_LOW <3.6", tone.event(3.5f,false,false,false)==dev.fpv.flight.MotorTone.Beep.BAT_LOW, "");
      check("beep BAT_CRIT <3.3", tone.event(3.1f,false,false,false)==dev.fpv.flight.MotorTone.Beep.BAT_CRIT, "");
      check("beep RX_LOST wins", tone.event(3.9f,true,false,true)==dev.fpv.flight.MotorTone.Beep.RX_LOST, "");
    }

    // ---------- 39. Discrete signal bands: tier + hysteresis + dwell ----------
    System.out.println("\n[39] signal band selection (LQ->chain, hysteresis, dwell)");
    {
      var sb = dev.fpv.flight.SignalBands.INSTANCE;
      var CLEAN = dev.fpv.flight.SignalBands.Band.CLEAN;
      var MILD = dev.fpv.flight.SignalBands.Band.MILD;
      var HEAVY = dev.fpv.flight.SignalBands.Band.HEAVY;
      var FROZEN = dev.fpv.flight.SignalBands.Band.FROZEN;
      // CLEAN band -> null chain.
      check("clean badness -> CLEAN, null chain", sb.decide(0.0, CLEAN, 999)==CLEAN && sb.chainId(CLEAN)==null, "");
      // escalation by badness.
      check("badness 0.15 -> MILD", sb.decide(0.15, CLEAN, 999)==MILD, "");
      check("badness 0.30 -> HEAVY", sb.decide(0.30, MILD, 999)==HEAVY, "");
      check("badness 0.50 -> FROZEN", sb.decide(0.50, HEAVY, 999)==FROZEN, "");
      check("chain ids", "fpv_sig_mild".equals(sb.chainId(MILD)) && "fpv_sig_heavy".equals(sb.chainId(HEAVY)) && "fpv_sig_frozen".equals(sb.chainId(FROZEN)), "");
      // hysteresis: in MILD, badness 0.08 is between DN_MILD(0.06) and UP_MILD(0.10) deadband -> stays MILD.
      check("hysteresis deadband holds MILD at 0.08", sb.decide(0.08, MILD, 999)==MILD, "");
      // but below DN_MILD(0.06) it drops back to CLEAN.
      check("below DN_MILD -> CLEAN", sb.decide(0.04, MILD, 999)==CLEAN, "");
      // dwell: at HEAVY, badness jumps above UP_FROZEN but ticksInBand < MIN -> stays HEAVY.
      check("dwell blocks switching within MIN ticks", sb.decide(0.9, HEAVY, 5)==HEAVY, "");
      check("dwell expires -> FROZEN", sb.decide(0.9, HEAVY, 11)==FROZEN, "");
      // selectChain returns (band, chainId); CLEAN -> null.
      var sel = sb.selectChain(0.0, CLEAN, 999);
      check("selectChain CLEAN -> null chain", sel.getSecond()==null, String.valueOf(sel.getSecond()));
      var sel2 = sb.selectChain(0.3, CLEAN, 999);
      System.out.printf("    selectChain bad=0.3 -> %s / %s%n", sel2.getFirst(), sel2.getSecond());
      check("no NaN in thresholds", !Double.isNaN(sb.UP_MILD) && !Double.isNaN(sb.UP_FROZEN), "");
    }

    // ---------- 40. Pure-client racing: gate geometry / serialization / ghost / off-zero ----------
    System.out.println("\n[40] racing: gate hit-test, track round-trip, ghost, off=zero-effect");
    {
      // Gate aperture shape parsing (pure; Vec3 plane-intersect needs MC runtime).
      var g = new dev.fpv.race.GateDef(0,0,0, 0f,0f, 2f,2f, 0, "RECTANGLE");
      check("gate shape RECTANGLE", g.gateShape()==dev.fpv.race.GateShape.RECTANGLE, "");
      var ring = new dev.fpv.race.GateDef(0,0,0, 0f,0f, 2f,2f, 0, "RING");
      check("gate shape RING", ring.gateShape()==dev.fpv.race.GateShape.RING, "");
      check("gate shape unknown -> RECTANGLE fallback", new dev.fpv.race.GateDef(0,0,0,0f,0f,1f,1f,0,"WEIRD").gateShape()==dev.fpv.race.GateShape.RECTANGLE, "");

      // Track serialization round-trip via AtomicFiles (no .tmp left).
      var doc = new dev.fpv.race.TrackDoc();
      doc.setName("t_roundtrip");
      doc.getGates().add(new dev.fpv.race.GateDef(1,2,3, 45f,0f, 1.5f,1.5f, 0, "RECTANGLE"));
      doc.getGates().add(new dev.fpv.race.GateDef(4,5,6, 90f,0f, 2f,2f, 1, "RING"));
      doc.setBestRoundMs(12345L);
      java.nio.file.Path td = java.nio.file.Files.createTempDirectory("trackrt");
      var trackFile = td.resolve("t.json");
      dev.fpv.race.TrackStore.saveTo(trackFile, doc);
      check("atomic write left no .tmp", td.toFile().listFiles((d,n)->n.endsWith(".tmp")).length==0, "");
      var back = new com.google.gson.GsonBuilder().create().fromJson(java.nio.file.Files.readString(trackFile), dev.fpv.race.TrackDoc.class);
      check("track round-trip: gate count", back.getGates().size()==2, ""+back.getGates().size());
      check("track round-trip: gate pos/shape", back.getGates().get(0).getX()==1.0 && back.getGates().get(1).getShape().equals("RING"), back.getGates().get(1).getShape());
      check("track round-trip: best ms", back.getBestRoundMs()==12345L, ""+back.getBestRoundMs());

      // Ghost record/replay quaternion consistency.
      var gs = new dev.fpv.race.GhostSample(0.5f, 1,2,3, 0f,0f,0f,1f, 0.5f);
      check("ghost quaternion identity", gs.quaternion().w()==1f && gs.quaternion().x()==0f, "");

      // Racing OFF by default -> zero effect on freestyle.
      FpvConfig rc = new FpvConfig();
      check("racing disabled by default", (rc.getRace()==null || !rc.getRace().getRaceEnabled()), "raceEnabled="+(rc.getRace()==null?"null":rc.getRace().getRaceEnabled()));
      check("safeName strips path chars", dev.fpv.race.TrackStore.INSTANCE.safeName("a/b\\c").equals("a_b_c"), dev.fpv.race.TrackStore.INSTANCE.safeName("a/b\\c"));
    }

    // ---------- 41. Pure race core: double geometry, injectable-clock timing, boost, penalties ----------
    System.out.println("\n[41] RaceCore: gate geometry / order / jump-start / boost / laps");
    {
      // double geometry: forward hit, reverse wrong-way, miss outside.
      var geo = dev.fpv.race.GateGeo.INSTANCE;
      var fwdN = geo.forward(0f, 0f); // +Z
      int hit = geo.intersect(0,0,-2, 0,0,2,  0,0,0, fwdN[0],fwdN[1],fwdN[2], 1.0,1.0, false);
      check("geo forward inside aperture -> hit", hit==1, ""+hit);
      int rev = geo.intersect(0,0,2, 0,0,-2,  0,0,0, fwdN[0],fwdN[1],fwdN[2], 1.0,1.0, false);
      check("geo reverse -> wrong-way", rev==-1, ""+rev);
      int out = geo.intersect(5,0,-2, 5,0,2,  0,0,0, fwdN[0],fwdN[1],fwdN[2], 1.0,1.0, false);
      check("geo outside aperture -> miss", out==0, ""+out);
      int ringHit = geo.intersect(0.5,0,-2, 0.5,0,2,  0,0,0, fwdN[0],fwdN[1],fwdN[2], 1.0,1.0, true);
      int ringMiss = geo.intersect(1.5,0,-2, 1.5,0,2,  0,0,0, fwdN[0],fwdN[1],fwdN[2], 1.0,1.0, true);
      check("geo RING inside radius -> hit", ringHit==1, ""+ringHit);
      check("geo RING outside radius -> miss", ringMiss==0, ""+ringMiss);

      // Injectable-clock timing: single gate0 at z=0 (+Z).
      long[] clock = {0L};
      var g0 = new dev.fpv.race.CoreGate(0,0,0, 0,0,1, 1.0,1.0, false, false);
      var core = new dev.fpv.race.RaceTimingCore(
        java.util.List.of(g0), /*laps*/1, /*limitNs*/1_000_000_000_000L, /*debounce*/1_000L,
        /*jumpPen*/30_000_000_000L, /*boostDur*/800_000_000L, /*staging*/true, () -> clock[0]);
      core.arm();
      for (double z=-2; z<=-0.2; z+=0.31) { clock[0]+=30_000_000L; core.step(0,0,z); }
      check("clock NOT started before timing gate", !core.getClockStarted(), "");
      for (double z=0.13; z<=5; z+=0.31) { clock[0]+=30_000_000L; core.step(0,0,z); }
      check("clock started after gate0", core.getClockStarted(), "");
      for (double z=4.7; z>=-2.3; z-=0.31) { clock[0]+=30_000_000L; core.step(0,0,z); }
      System.out.printf("    laps=%d validLaps=%d finished=%b%n",
        core.getLapsCompleted(), core.getValidLapsNs().size(), core.getFinished());
      check("one valid lap recorded", core.getValidLapsNs().size()==1, ""+core.getValidLapsNs().size());
      check("lap completes -> finished", core.getFinished(), "");

      // Boost gate g1 at z=10.
      clock[0]=0L;
      var g1 = new dev.fpv.race.CoreGate(0,0,10, 0,0,1, 1.0,1.0, false, true);
      var cB = new dev.fpv.race.RaceTimingCore(java.util.List.of(g1), 1, 1_000_000_000_000L, 1_000L, 30_000_000_000L, 800_000_000L, true, () -> clock[0]);
      cB.arm();
      for (double z=-2; z<=9.0; z+=0.31) { clock[0]+=30_000_000L; cB.step(0,0,z); }
      check("boost not active before gate", !cB.boostActive(), "");
      for (double z=9.3; z<=11; z+=0.31) { clock[0]+=30_000_000L; cB.step(0,0,z); }
      check("boost active right after boost gate", cB.boostActive(), "");
      System.out.printf("    boostUntilNs=%d clock=%d%n", cB.getBoostUntilNs(), clock[0]);

      // --- Latch regression (E2E-001/002): legitimate start, no penalty; event drained once ---
      // No staging -> crossing gate0 is the LEGAL start, must NOT be a jump.
      var cL = new dev.fpv.race.RaceTimingCore(java.util.List.of(g0), 1, 1_000_000_000_000L, 1_000L, 30_000_000_000L, 800_000_000L, true, () -> clock[0]);
      cL.arm();
      clock[0]=0L; cL.step(0,0,-2); cL.step(0,0,2); // legal start crossing
      var ev1 = cL.drainEvents();
      check("legal start -> GATE_HIT, no JUMP", ev1.contains(dev.fpv.race.CoreEvent.GATE_HIT) && !ev1.contains(dev.fpv.race.CoreEvent.JUMP_START), ev1.toString());
      check("penalty 0 on legal start", cL.getPenaltyNs()==0L, ""+cL.getPenaltyNs());
      check("drain empties queue", cL.drainEvents().isEmpty(), "");
      // 300 open-space frames: penalty must NOT accumulate (the old latch bug).
      for (int i=0;i<300;i++){ clock[0]+=30_000_000L; cL.step(100,0,100); }
      cL.drainEvents();
      check("300 empty frames -> penalty still 0", cL.getPenaltyNs()==0L, ""+cL.getPenaltyNs());
      // With staging ON, crossing gate0 before GO = JUMP, delivered once.
      var cS = new dev.fpv.race.RaceTimingCore(java.util.List.of(g0), 1, 1_000_000_000_000L, 1_000L, 30_000_000_000L, 800_000_000L, true, () -> clock[0]);
      cS.arm(); cS.setStagingActive(true);
      clock[0]=0L; cS.step(0,0,-2); cS.step(0,0,2);
      var evS = cS.drainEvents();
      System.out.printf("    staging jump penaltyNs=%d events=%s%n", cS.getPenaltyNs(), evS);
      check("staging pre-GO -> JUMP_START once", evS.contains(dev.fpv.race.CoreEvent.JUMP_START) && cS.getPenaltyNs()==30_000_000_000L, evS.toString());
      cS.drainEvents();
      for (int i=0;i<50;i++){ clock[0]+=30_000_000L; cS.step(100,0,100); }
      cS.drainEvents();
      check("post-jump open frames -> penalty stays 30s", cS.getPenaltyNs()==30_000_000_000L, ""+cS.getPenaltyNs());
    }

    // ---------- 42. LineHelper bearing + live gap ----------
    System.out.println("\n[42] LineHelper next-gate bearing + live gap");
    {
      var lh = dev.fpv.race.LineHelper.INSTANCE;
      // Drone at origin facing +Z (yaw=0); gate straight ahead (+Z) -> 0.
      double fwd = lh.relativeBearingDeg(0,0, 0f, 0, 10);
      check("gate straight ahead -> 0 deg", Math.abs(fwd)<1e-6, ""+fwd);
      // Gate to the right (-X) -> +90.
      double right = lh.relativeBearingDeg(0,0, 0f, -10, 0);
      System.out.printf("    bearing straight=%.2f right=%.2f%n", fwd, right);
      check("gate to the right -> ~+90", Math.abs(right-90.0)<1.0, ""+right);
      // Gate behind -> ~+/-180.
      double back = lh.relativeBearingDeg(0,0, 0f, 0, -10);
      check("gate behind -> ~180", Math.abs(Math.abs(back)-180.0)<1.0, ""+back);
      // Live gap: current lap 5.2s, ghost 5.0s -> +0.2 behind.
      double gap = lh.liveGapSec(5_200_000_000L, 5_000_000_000L);
      System.out.printf("    liveGap=%.2f s%n", gap);
      check("live gap +0.2 behind", Math.abs(gap-0.2)<1e-6, ""+gap);
    }

    // ---------- 44. End-to-end heat: multi-gate loop, boost, reverse rejected ----------
    System.out.println("\n[44] E2E heat: order gates + boost + reverse rejected");
    {
      long[] clk = {0L};
      // gate0 z=0 start, gate1 z=10 boost, loop back to gate0.
      var gg0 = new dev.fpv.race.CoreGate(0,0,0, 0,0,1, 1.0,1.0, false, false);
      var gg1 = new dev.fpv.race.CoreGate(0,0,10, 0,0,1, 1.0,1.0, false, true);
      var heat = new dev.fpv.race.RaceTimingCore(java.util.List.of(gg0,gg1), 2, 1_000_000_000_000L, 1_000L, 30_000_000_000L, 800_000_000L, true, () -> clk[0]);
      heat.arm();
      // start crossing gate0 (legit, no staging)
      for (double z=-2; z<=12; z+=0.31) { clk[0]+=30_000_000L; heat.step(0,0,z); }
      check("clock started on gate0", heat.getClockStarted(), "");
      check("boost armed after gate1", heat.boostActive(), "");
      var ev = heat.drainEvents();
      check("BOOST event emitted once", ev.stream().filter(e->e==dev.fpv.race.CoreEvent.BOOST).count()==1L, ev.toString());
      // return through gate0 -> lap1, then gate1, then gate0 -> lap2 = finish
      for (double z=12; z>=-2; z-=0.31) { clk[0]+=30_000_000L; heat.step(0,0,z); }
      for (double z=-2; z<=12; z+=0.31) { clk[0]+=30_000_000L; heat.step(0,0,z); }
      for (double z=12; z>=-2; z-=0.31) { clk[0]+=30_000_000L; heat.step(0,0,z); }
      System.out.printf("    laps=%d validLaps=%d splits=%d finished=%b avg3=%d%n",
        heat.getLapsCompleted(), heat.getValidLapsNs().size(), heat.getSplitsNs().size(), heat.getFinished(), heat.avgBest3Ns());
      check("2 laps valid", heat.getValidLapsNs().size()==2, ""+heat.getValidLapsNs().size());
      check("heat finished", heat.getFinished(), "");
    }

    // ---------- 45. Boost thrust envelope ----------
    System.out.println("\n[45] boost thrust scale");
    {
      var bm = dev.fpv.race.BoostModel.INSTANCE;
      double base = bm.thrustScale(false);
      double boost = bm.thrustScale(true);
      System.out.printf("    base=%.2f boost=%.2f gain=%.2fx%n", base, boost, boost/base);
      check("base scale = 1.0", Math.abs(base-1.0)<1e-9, ""+base);
      check("boost scale = 1.5", Math.abs(boost-1.5)<1e-9, ""+boost);
      check("boost gain > baseline", boost>base, "");
    }

    // ---------- 46. BOOST scale actually drives translational thrust ----------
    System.out.println("\n[46] boostScale wired into TranslationalDynamics");
    {
      FpvConfig cfg = new FpvConfig();
      TranslationalDynamics td = new TranslationalDynamics(cfg.activeAirframe());
      Quaternionf level = new Quaternionf();
      // Full throttle, level attitude, still: vertical thrust delta.
      Vector3f d1  = td.step(level, 1f, 0.0,0.0,0.0, -1f, 1f, false, 0.05f, 1f);
      Vector3f dB  = td.step(level, 1f, 0.0,0.0,0.0, -1f, 1f, false, 0.05f, 1.5f);
      double up1 = d1.y(), upB = dB.y();
      System.out.printf("    thrust accel up: scale1=%.4f scale1.5=%.4f ratio=%.3f%n", up1, upB, upB/up1);
      check("boost yields more vertical accel", upB > up1, upB+">"+up1);
      check("ratio ~1.5 thrust口径 (gravity-offset)", upB > up1*1.3 && upB < up1*1.7, ""+(upB/up1));
      check("no NaN", !Float.isNaN(dB.y()), "");
    }

    // ---------- 47. BeepScheduler: disarmed silent, throttled continuous alarms ----------
    System.out.println("\n[47] beep scheduling (disarmed silent / throttled)");
    {
      var bs = dev.fpv.flight.BeepScheduler.INSTANCE;
      var NONE = dev.fpv.flight.MotorTone.Beep.NONE; var RX_LOST = dev.fpv.flight.MotorTone.Beep.RX_LOST; var BAT_LOW = dev.fpv.flight.MotorTone.Beep.BAT_LOW;
      // disarmed + rxFail: NONE even at any time.
      check("disarmed rxFail -> NONE", bs.continuousAlarm(10000L, false, true, 3.9f, 0L)==NONE, "");
      check("disarmed BAT_LOW -> NONE", bs.continuousAlarm(10000L, false, false, 3.4f, 0L)==NONE, "");
      // armed + rxFail: first tick fires, within interval NONE, after interval fires again.
      int fires = 0; long t = 0L; long last = -100000L;
      for (int i=0;i<200;i++){ t += 16L; var ev = bs.continuousAlarm(t, true, true, 3.9f, last);
        if (ev==RX_LOST){ fires++; last = t; } }
      System.out.printf("    200 ticks (3.2s) RX_LOST fires=%d (expected ~3)%n", fires);
      check("RX_LOST throttled to bounded count (not per-frame)", fires>=2 && fires<=5, ""+fires);
      // recovered -> NONE immediately.
      check("rxFail recovered -> NONE", bs.continuousAlarm(t, true, false, 3.9f, last)==NONE, "");
      // BAT_LOW throttled.
      int low = 0; last = -100000L;
      for (int i=0;i<300;i++){ t += 16L; var ev = bs.continuousAlarm(t, true, false, 3.5f, last);
        if (ev==BAT_LOW){ low++; last = t; } }
      System.out.printf("    BAT_LOW over 4.8s fires=%d%n", low);
      check("BAT_LOW throttled bounded", low>=2 && low<=6, ""+low);
    }

    System.out.println("\n========================================");
    System.out.println(failures == 0 ? "ALL TESTS PASS" : ("FAILURES: " + failures));
    System.exit(failures == 0 ? 0 : 1);
  }
}

