import dev.fpv.flight.FlightController;
import dev.fpv.flight.FpvConfig;
import dev.fpv.flight.TranslationalDynamics;
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

    System.out.println("\n========================================");
    System.out.println(failures == 0 ? "ALL TESTS PASS" : ("FAILURES: " + failures));
    System.exit(failures == 0 ? 0 : 1);
  }
}
