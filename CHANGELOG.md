# Changelog

All notable changes to this mod are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project uses
Semantic Versioning; `main` is the development branch and `stable` points at the
latest fully verified (zero-warning build + all verification scripts PASS)
revision. No formal GitHub Release is published until real-hardware acceptance.

## [Unreleased] — REAL physics calibration + headless flight-controller tests

### Fixed
- **Prop-wash oscillation never animated**: `FlightController` re-created a fresh
  `PropwashModel` every frame, resetting its oscillation phase, so the 15-40 Hz
  sine degenerated to a constant offset (no visible shake). The model is now a
  persistent field; `PropwashModel.step(airframe, ...)` takes the active airframe
  per frame so profile switching still works.
- **Bare `AirframeProfile` fallback thrust** was 35 N/motor (~3x real); corrected
  to 12 N to match the Freestyle 5" reference.
- **Body-frame drag was rotated the wrong way to world**: both prop-speed and
  frame drag used `attitude.invert()` for the body->world transform (should be
  `attitude`), misdirecting the drag acceleration.

### Changed
- **Per-axis mixer differential authority** replaces the single 0.22 value:
  roll 0.25 / pitch 0.27 (thrust differential) and yaw 0.55 (reaction-torque
  differential). Full stick now reaches ~670 dps roll/pitch and ~400 dps yaw
  (yaw is reaction-torque limited, as on a real quad).
- Reaction torque per motor calibrated 0.08 -> 0.15 N·m (5" torque/thrust ~0.012).
- **Complete anisotropic airframe drag**: added body-frame quadratic drag that
  acts in every direction even at zero throttle (glide) — vertical (prop disk)
  0.022 > forward 0.018 > lateral 0.012 — alongside the world linear term and
  the rpm-scaled prop drag. Drag is no longer only a post-throttle-cut slowdown.

### Added
- **`headless/FlightControlCheck.java` + `run_headless.sh`** — reproducible
  headless verification of the REAL pipeline actually shipped on main
  (rates -> PID -> Quad-X mixer -> motor lag -> rigid body -> attitude, plus
  prop-wash): three-axis step (rise time, overshoot, pitch/roll symmetry),
  zero-stick rate-hold, descent-vs-clean prop-wash, 30 s closed-loop stability
  with NaN guards, and per-direction translational glide drag (sign +
  anisotropy). (The earlier `PhysicsCheck` exercised a different, unmerged
  rigid-body implementation and does not apply to main.)

## [Unreleased] — graphical radio calibration + P-B remote compatibility

### Added
- **Graphical 4-step `CalibrationScreen`** replacing the text-only move-to-bind:
  - HAND: live twin-stick map colored by channel (Roll/Pitch/Yaw/Throttle), redrawn
    on every Mode 1..4 change.
  - CENTER (new): release-all step with a live per-axis bar and a settle detector
    (every raw source still for CENTER_FRAMES) that gates the binding baseline.
  - BIND: large scope draws the live gimbal dot and measured endpoints (analog;
    horizontal/vertical resolved from the hand-mode slot) or the detected switch
    positions; travel not swept to both ends shows a yellow guide instead of silently
    accepting a short sweep.
  - New zh/en lang keys: cal.fpv.center_hint/center_ok/center_wait, travel_good/
    travel_low, switch_more.

### Changed
- Bind mini stick map uses a label-free mode (highlight stick + axis only) so it does
  not overlap the step title.

## [Unreleased] — P-B remote vanilla-server compatibility layer

### Added
- **`ServerCompatConfig`** nested block (migrate() fills defaults): compatEnabled,
  coordinatedTurn, coordTurnGain, coordPitchCompensation, fireworkEnabled,
  fireworkThrottleThreshold, fireworkMinIntervalTicks, antiKick, softSpeedLimit(+cap).
  Remote defaults on; creative / single-player are never speed-limited.
- **`dev.fpv.flight.ServerCompatLogic`** — Minecraft-free, headless-tested logic:
  nose = attitude*(0,0,-1) -> vanilla yaw/pitch; inverse of `Entity.turn(d,e)`'s 0.15
  scale (so one per-tick call reaches the target look, yaw wrapped shortest way);
  roll -> "coordinated turn" yaw/pitch bias baked into the look; fireworks gating
  (threshold + minimum interval + holding a firework + fall-flying); setback reaction
  widens the firework interval and softens the turn gain.
- **`LocalPlayerMixin`** (remote only; does NOT cancel travel, does NOT inject velocity):
  maps attitude onto vanilla look each tick, fires fireworks for throttle, defers to the
  server on setback. Wrapped in runCatching. [NEEDS LOCAL VERIFICATION] on a real
  Paper/Velocity server.

### Headless verification (ServerCompatTest, plain JVM)
- nose->yaw/pitch round-trip exact; lookDelta reconstructs target via *0.15; yaw wrap
  shortest way; coordinated turn sign + monotonic + off->zero; fireworks gating
  (threshold/gliding/held/interval); setback widens interval 20->80 and softens gain.
  15/15 PASS.

## [Unreleased] — Condition-driven prop-wash (washout disturbance)

### Added
- **`dev.fpv.flight.PropwashModel`** — headless-testable washout model. The old
  always-on sine shim is gone; disturbance strength is now CONDITION-DRIVEN:
  `strength = descentF * slowF * thrF` (falling through own downwash, low airspeed,
  high power). Only then does it inject 15-40 Hz roll/pitch gyro noise and a
  proportional thrust drop. Clean fast forward flight (fresh inflow) gets nothing.
- New tunables on the airframe: `propwashAmpDps`, `propwashFreqLowHz/HighHz`,
  `propwashThrustDrop`, `propwashDescentBpt`, `propwashSlowBpt`; whole effect switchable.
- `FlightController.setTranslationState(vy, horizSpeed)` feeds the flight condition;
  the disturbance is injected on the actual tracked rates in BOTH REAL and ARCADE
  physics paths, and `propwashThrustScale` is folded into the thrust derate by the
  translation mixin (washout loses lift).

### Headless verification (PropwashTest, plain JVM)
- Washout regime (descending, slow, high throttle): dominant freq ≈16 Hz (in 15-40),
  rms gyro 6.8 dps, thrust scale 0.91 (drop). Clean forward: disturbance 0.0, scale 1.0.
  Global toggle off -> silent. All PASS.

## [Unreleased] — P-A translational lift (body-up thrust, hover, aero, ground effect, sag)

### Added
- **`dev.fpv.flight.TranslationalDynamics`** — Minecraft-free, headless-testable
  translational plant. Lift now points along **BODY-UP** `up = attitude*(0,1,0)`
  (the prop-disk normal), not the old virtual-nose `(0,0,-1)`. Net accel =
  `thrustN*groundEffect*derate/m * up + gravity + world drag + prop-speed body drag`.
  At level attitude + `effectiveHoverThrottle()` the vertical net force is ~0; tilting
  the disk yields a horizontal component (translation) and a reduced vertical component
  (natural altitude loss) — the real multirotor trade.
- Hover: `effectiveHoverThrottle()` solves `totalThrustN(t_h)=m*g` (linear/quadratic,
  clamped); GUI shows the hover point.
- **Ground effect** (switchable, tunable): thrust multiplier
  `1 + gain*exp(-agl/heightBlocks)` near the deck (public open-form model).
- **Prop-speed body drag** `F = -diag(cxy,cxy,cz) * sumRpm * vBody`.
- Battery sag already derates thrust (`batteryDerate = vbat/nominalV`); reaction torque
  feeds yaw from the Stage-1 rigid plant.
- `LivingEntityMixin` now only gathers inputs (attitude, throttle, velocity, AGL,
  derate) and writes back the delta + `move()`; all physics lives in the pure class.

### Headless verification (TranslationsTest, plain JVM)
- Hover: net vertical accel 0.000000, no lateral accel.
- Pitch 20° nose-down → forward accel + altitude loss; Roll 20° → lateral accel + loss.
- Ground effect near deck: thrust boost (mult 1.25) > far (1.0).
- Battery sag 0.7 vs 1.0 derates thrust. T/W(max)=7.5. 20k random steps finite. All PASS.

## [Unreleased] — P-D rotational realism (motor lag + rigid-body inertia)

### Added
- **Real vs Arcade rotational physics switch** (`FpvConfig.physicsRealism`,
  "REAL"/"ARCADE"), data-driven, persisted, toggled on the Advanced config page.
  ARCADE keeps the legacy near-instant tracked-rate path (old feel).
- **`dev.fpv.flight.RealDynamics`** — a Minecraft-free, headless-testable rigid-body
  plant: setpoint dps → PID (or simple P) differential → Quad-X mixer
  (`MixerTables`) → 4 motor commands → motor first-order lag `tau*dm/dt = -m+sqrt(u)`
  → per-motor thrust `maxThrustPerMotorN*m^2*batteryDerate` and reaction torque
  `reactionTorquePerMotorNm*m^2` → lever-arm roll/pitch/yaw moments → rigid-body
  Euler equation `I*w_dot = tau - w x (I w) - rotDamp*w` → actual body dps.
  Clean-room rigid-body math modeled on the public gym-pybullet-drones / drone-models
  quadrotor equations (MIT); the PID→motor-differential path is a clean-room rewrite
  of the published Betaflight structure (no GPL source text copied).
- New airframe fields (all tunable, engineering starting values pending real-machine
  tuning): `motorTauSec`, `armLength`, `reactionTorquePerMotorNm`, `rpmMaxPerMotor`,
  `differentialAuthority`, `simpleRatePGain`, `rotDampXX/YY/ZZ`, `hoverThrottle` +
  `autoHoverThrottle()`; derived `kf()`/`km()`.
- `RatePidController.runDifferential()`: returns the clamped pid sum normalized to a
  [-1,1] motor differential (Betaflight-style sum→motor mix) for the REAL plant; the
  ARCADE `run()` path is unchanged.
- Battery sag now derates the real motor plant (`FlightController.batteryDerate =
  vbat/nominalV`).

### Headless verification (StageResponseTest, plain JVM)
- Real pitch 200 dps step: rise ≈52 ms, single ≈24% overshoot, settles to 200 dps
  (non-instant, weighty). Doubling inertia raises rise 52→70 ms (heavier = slower).
  Roll/yaw sign correct; 20k-step random input stays finite and bounded; ARCADE path
  remains near-instant. All PASS.

## [Unreleased] — Safety net (config safety, runtime guards, traceability)

### Added
- **Config safety (B):** atomic writes (`AtomicFiles`), timestamped rotated
  backups (default keep **10**, under `<configDir>/fpv/backups`), schema version
  field, parse + migrate + sanity validation, automatic fallback to the newest
  parseable backup, quarantine (never delete) of a bad file into
  `<configDir>/fpv/quarantine`, and a Safety/Backups config screen with
  reset-to-defaults and one-click backup restore.
- **Headless config-safety self-test** (`ConfigSafety`, runnable in a plain JVM):
  good/bad/missing-field loads, backup creation and rotation to 10, quarantine,
  and atomic-write integrity — all PASS.
- **Runtime safety (C):** central `SafetyGuards` validates rates, attitude
  quaternion, body rates and velocity every frame; non-finite values are
  replaced (neutral command / identity attitude / zero velocity) so NaN can never
  enter the attitude integral, the camera or movement. Incoming channels are
  clamped; recorder `.fpr` finalization is atomic.
- **Incident traceability (D):** the blackbox header records mod version, config
  SHA-256 and device fingerprint; a severe fault in the flight step is flushed to
  the blackbox with an event marker before the exception unwinds.

### Notes / evidence gaps
- Offline frame readback, FFmpeg end-to-end output, CHASE-camera offset sign,
  120 Hz recording cost and all real-transmitter behaviour still require local
  hardware verification (annotated in code).

### Fixed
- **ANGLE pitch positive feedback (camera "spasm"/neck snapping back):** the
  body-frame attitude error hand-typed the pitch sign backwards, so a nose-up
  disturbance fed back and flipped the craft. The error is now projected through
  the same `BodyAxis` table used by rate measurement (single source of truth).
  A headless simulation confirms roll and pitch (nose-up/down) 20° disturbances
  converge monotonically to ~0 in ANGLE and HORIZON.
- **Arming used SA instead of SF, and SA was both arm and mode switch:** a
  one-time schema migration (schema 1 -> 2) re-applies the known-device arm/modes
  seed (F16: SF/button-1 -> ARM) only for the old-default fingerprint, decouples
  the legacy arm/mode indices and syncs the arm button; a generic guard also
  blocks a legacy mode-cycle axis from sharing the arm axis. A headless test
  confirms SF arms/disarms and the mode stays ACRO.

## [0.1.0] — 2026-09-30 — Cloud-verified (`a453b95`)

### Added
- Data-driven FPV flight core: `StickSlot` physical stick slots, `HandLayout`
  Mode 1–4 (logical channel -> stick slot), `SlotCalib` raw bindings (unbound by
  default), `BodyAxis`, and a central `Defaults` token set.
- Flight controllers: ACRO / ANGLE / HORIZON with a real attitude PD loop;
  optional inner rate PID (`RatePidController`: P/I/D/F, I-term relax,
  anti-gravity, TPA, dynamic D-term LPF); third-order setpoint smoothing;
  airframe inertia/angular-drag first-order tracking and CG thrust-arm coupling.
- Full airframe physics parameterization with multiple saveable profiles
  (`AirframeProfile`) and live derived performance (`AirframeDerivation`:
  thrust / top speed / aerodynamics).
- EdgeTX-style dynamic AUX channels and a guided move-to-bind calibration
  wizard; per-device-fingerprint mapping profiles and a channel monitor.
- F9U-aligned racing module (data-driven rules `F9URules`: 3 laps / 180 s /
  timing-gate start / sectors / +30 s penalty / landing-zone validation /
  average-of-best-3-laps ranking / turtle flip), plus a track editor. No track or
  gate layout is shipped — tracks are built entirely by the user; a new track is
  an empty start-only template.
- Motion回溯/cinematic pipeline: high-density `.fpr` recorder (attitude + IMU +
  telemetry, no screen capture), data-driven replay with scrub/speed/multi-view,
  and offline cinematic export (16:9 / 9:16 / 2.35:1 / 2.39:1 / 21:9,
  motion blur, FFmpeg with PNG-sequence fallback).
- Drag-and-drop OSD editor; OSD elements aligned with Betaflight (artificial
  horizon with pitch ladder, attitude degrees, throttle, battery voltage/cell/%,
  current, mAh drawn, LQ, flight timer, ARM/mode indicators, REC/REPLAY).
- Default mode is freestyle ACRO; racing/gates default to off; multiplayer
  translation is off by default (camera roll only), single-player translation on.

### Fixed
- Channel misassignment (roll/yaw) and arming via digital switches; artificial
  horizon roll/pitch direction; throttle translation (full travel takeover at
  `travel` HEAD); main-framebuffer color attachment readback (no black frames)
  with vertical-flip correction.
- Default physics grounded in open data (Flightmare / measured 5"-class static
  pull): per-motor thrust 12 N (4S) / 18 N (6S); corrected inertia; battery sag
  as I·R.
- Client-equivalent arming checks (NO_RX / NOT_CALIBRATED / BATTERY_CRITICAL /
  RX_FAILSAFE, plus low throttle for keyboard arming) with all reasons listed.

### Known issues / evidence gaps
- Fixed-size catalog of square/flag/ring/tunnel gates is absent from the source
  rules; gate openings are parameterized. "≥4 gates" and "10 s obstacle wait"
  are not confirmed F9U rules.
- Derivation constants (Ct, load factor β, Cd·A), boost, CG and exact inertia
  distribution are engineering starting values pending thrust-stand / wind-tunnel
  and in-flight tuning.
- Controlify source-level review and third-party anti-cheat internals were not
  available (network isolation).
