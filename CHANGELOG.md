# Changelog

All notable changes to this mod are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project uses
Semantic Versioning; `main` is the development branch and `stable` points at the
latest fully verified (zero-warning build + all verification scripts PASS)
revision. No formal GitHub Release is published until real-hardware acceptance.

## [Unreleased] — OSD refactor: data-driven element registry

### Added (module 4: emergent aerobatics harness)
- **`flight/Aerobatics.kt`**: a closed-loop headless harness that drives the REAL flight
  controller (stick setpoint -> PID -> motor lag -> torque -> rigid-body integration) AND the
  translational dynamics (tilted-disc thrust + gravity + anisotropic drag) in one loop. The
  caller supplies ONLY a stick time-series + initial velocity — no scripted trajectory, no
  setpoint->rate shortcut. The trace records attitude, body-up, velocity, altitude, rates.
- headless [34] proves emergence (not trajectories): matty flip completes a full roll via
  momentum (452 deg), power loop a vertical 360 (805 deg), both pass fully inverted; a
  throttle-cut dive retains more speed than a level glide (gravity does work); the same
  banked-turn stick at v0=0.5 vs v0=6.0 gives a measurably different altitude response
  (environment participates); all finite.

### Added (P-B GUI: cinematic keyframe timeline editor)
- **`replay/CinematicEditorScreen.kt`**: scrub rail with keyframe ticks (click to select / scrub),
  "Set KF here" (upsert at cursor), Delete KF, cycle interpolator (CR/Cubic/Linear, live label),
  nudge keyframe time ±0.1s, atomic Save to the sidecar, Done. Real-button on ReplayScreen
  "Cameras...". ReplayManager upsert/delete/cycle/save helpers.

### Added (P-C: export decoupling, ffmpeg template, camera exchange)
- **`replay/FrameConsumer.kt`**: `FrameConsumer` interface decoupling "rendered a frame" from
  encoding; `PngFrameConsumer` is the continuous-numbered PNG fallback.
- **`replay/FfmpegCommand.kt`**: user-customizable ffmpeg template (`ExportConfig.ffmpegTemplate`,
  tokens %WIDTH% %HEIGHT% %FPS% %PIXELFMT% %FILENAME%); output filename whitelisted to
  `[A-Za-z0-9._-]` and passed as its own argv element, so a hostile name cannot inject flags.
  Missing ffmpeg already falls back to PNG with a status message.
- **`replay/CameraPathExporter.kt`**: dumps the per-frame camera pose (recorded or spline-sampled)
  to JSON (`{fps, frames:[{t,x,y,z,qx,qy,qz,qw,fov}]}`) for Blender/AE import; atomic write.
- Optional RC joystick overlay (`ExportConfig.rcOverlay`, default off).
- headless [33]: template substitution + injection guard, PNG continuous numbering, camera JSON
  frames = fps×duration.

### Added (P-B: cinematic keyframe camera track)
- **Keyframe camera track** (`replay/CinematicTrack.kt`, clean-room): ordered `(tSec, pos, quat, interp)`
  keyframes persisted atomically to a `<rec>.cam.json` sidecar (versioned schema, shareable). Position
  splines: **Catmull-Rom α=0.5 centripetal (default)**, **Cubic Hermite (configurable tension)**, **Linear**;
  orientation = shortest-arc slerp. Boundary segments clamp to linear so endpoints pass exactly.
- **Playback integration** (`replay/ReplayManager.kt`): loads the sidecar on replay load; when
  `trackActive`, `cameraTransform()` samples the spline at the cursor instead of the recorded pose.
- **Export canvas** (`replay/CinematicExport.kt`): added `4:5` and `1:1` aspects; even-dimension canvas
  math extracted to a headless-testable `canvasFor()`.
- **Optional RC overlay mapping** (`replay/StickOverlay.kt`): recorded normalized RC -> joystick
  position / throttle fill (pure, headless-tested; rendering wiring pending on-screen).
- headless [32]: spline passes exactly through keyframes, monotonic/non-overshooting, interp switch,
  slerp midpoint, sidecar round-trip, canvas sizes, RC overlay mapping.

### Added (P-A: schema-driven flight recorder v2)
- **`.fpr` format bumped to v2** (`replay/ReplayFile.kt`): per-sample now records four mixer outputs
  (`motor[4]`, signed in 3D), camera FOV/tilt, and sub-frame `phase`; plus a discrete **event block**
  (ARM / DISARM / GATE / MODE, timestamped). v1 files still parse (missing fields default). A
  self-describing `ReplaySample.FIELD_SCHEMA` (name/type/doc) documents every field (betaflight-blackbox
  field-schema idea, clean-room).
- **Bounded ring buffer** (`replay/FlightRecorder.kt`): samples kept in an `ArrayDeque` ring capped by
  `ReplayConfig.maxSamples` (default 30000 ≈ 4 min @120 Hz); oldest dropped on long runs so memory stays
  bounded. New `recordingEnabled` master switch.
- **NaN/Inf defence**: every sample field passes `ReplayFile.cleanFloat()` before storage; the file never
  holds a non-finite value. Atomic write via `flight/AtomicFiles` leaves no half-written file.
- headless [31]: write→read field round-trip (pos, gyro, motor[4], camTilt, phase), event block, no atomic
  temp leftover, read-back NaN-free, interpolation intact.

### Added (B2 batch elements, real telemetry)
- **Core flight telemetry widgets**: vario (vertical speed m/s·f/s with ▲/▼ arrow), numeric heading
  (0–359 + compass letter N/NE/E…), true ground speed (body horizontal speed, replacing player-movement
  estimate), altitude (player Y), plus Uncrashed-style speed & altitude side **bars** and a heading
  **compass ladder** (N/E/S/W ticks + centre marker).
- **Power/efficiency/estimators**: power W = vbat×currentA, remaining flight time ETE = battery%/
  discharge-rate estimate, ESC RPM = |mixer|×ESC_MAX_RPM (model-derived), efficiency km/h·A.
- **Race & diagnostics**: 4 per-motor % bars (signed in 3D, reverse fills downward), second timer,
  current+best lap (reuses `RaceManager`), home arrow+distance (arm-point derived; hidden until arm),
  craft/pilot name static text.
- All new elements live in `OsdElements.REGISTRY` with unit/precision/default position; addable &
  independently toggleable in the OSD editor, metric/imperial applied, layout round-trips via
  `AtomicFiles`. Model-derived items are flagged in the label (*).
- No fake data: GPS lat/lon/satellites, ESC/core temp, RTC, independent airspeed, LIDAR, navigation
  map and DJI SYS family remain unimplemented (no real source).

### Added
- **OSD element registry** (`flight/OsdModel.kt`): `OsdElements.REGISTRY` declares every element by
  id / zh+en label / renderer type (TEXT·BAR·LADDER·GAUGE·ICON·HORIZON·BANNER) / measured quantity /
  default enable·position / colour. A frozen, Minecraft-free `OsdTelemetry` snapshot (attitude·rates·
  speed·alt·vario·home·vbat/perCell/%·current·mAh·W·throttle·motor rpm·mixer out·LQ·mode·armed·timer)
  feeds the pure `OsdFormatter`, so text rendering and unit math are headless-testable. Adding an
  element is a one-line registry entry; the renderer main loop is untouched.
- **Unit system** (`OsdUnit` METRIC/IMPERIAL; `FpvConfig.osdUnit`, `OsdElement.unitOverride`):
  global metric/imperial toggle (plus per-element override) covering speed km/h↔mph, distance m↔ft.
  Editor gains a "Units: MET/IMP" button. New `POWER` element (W = V·A) added disabled-by-default as
  the power-channel data interface.
- The data-interface slots for the pending batch of new elements are declared on `OsdTelemetry`
  (GPS ground speed = body ground speed, coords = world X/Z, altitude = Y, vario = vertical speed,
  home = unlock point, ESC rpm = motor model, per-motor % = mixer output, current/mAh/W = battery).

### Changed
- `FpvOsd` now walks the persisted layout and dispatches on each spec's renderer type; positions,
  enable and units come from layout data (no hard-coded position `when`). Existing elements
  (speed/target/battery/LQ/timer/throttle/attitude/current/mAh/mode/horizon/sidebars/crosshair/warning)
  are migrated with identical on-screen output.
- `OsdLayout.defaultLayout()` is derived from the registry (single source of truth); editor labels
  read `labelEn` from the registry.
- Headless [26]–[28]: layout serialize→load round-trip (position/enabled/per-element unitOverride),
  unit conversions (10 m/s = 36 km/h = 22.4 mph; 100 m = 328 ft; power = V·A = 168 W), and registry
  completeness (10/10 TEXT elements render, 5/5 graphic elements return null text).

## [Unreleased] — Flight-mode parity with Betaflight (3D reversible physics / Horizon / Angle / PID)

### Added
- **Reversible-3D rotational physics** (`flight/RealDynamics.kt`): the per-motor command is now
  signed `m ∈ [-1,1]` when `cfg.reversible3D`, tracking `sign(u)*sqrt(|u|)` through the motor
  first-order lag. Per-motor lift and reaction torque carry `m*|m|`, so reversing the motors flips
  the roll/pitch **and** the yaw reaction-torque moments together — exactly like a reversible-3D
  airframe. Below the 3D throttle deadband the collective is ~0 (zero net thrust); normal flight is
  unchanged (`m≥0 ⇒ m*|m| = m²`). Pairs with the already-implemented bidirectional vertical thrust
  in `TranslationalDynamics`. Headless [22]: up-thrust +0.138 / reverse-thrust −0.238 / mid −0.05;
  yaw demand forward and reversed give the same 451 dps direction (loop not flipped); mid settles ~0.
- **Horizon leveling strength now fades with bank angle + rise-smoothing**
  (`flight/AngleController.horizonRates`, per BF `pid.c:542-561`):
  `strength = max((135°−|inclination|)/135°,0) · max(1−|stick|,0)` then passed through a rise-limited
  PT1 (`HORIZON_SMOOTH_TAU_SEC`=0.5s; smooth up, immediate down). Center stick/level = full leveling,
  full stick/steep bank = full acro. Headless [23]: level 0.99 → bank60 0.56 → bank120 0.11, full
  stick 0, first step 0.01 (rise-limited).

### Changed
- **ANGLE default max inclination 50 → 60 deg** (`Defaults.ANGLE_MAX_DEG`), matching BF `angle_limit`.
  Headless [24].
- **I-term relax uses the BF hard threshold** `max(0, 1 − hpf/40 dps)`
  (`RatePidController.kt`) instead of the asymptotic `1/(1+k|x|)`; at/above 40 dps the integrator is
  fully frozen (setpoint- or gyro-keyed, RP/RPY/OFF scope unchanged).
- **Airmode low-throttle authority floor** (`Defaults.AIRMODE_LOW_THROTTLE_AUTHORITY`, default 0 = off):
  parameterized minimum mixer authority retained below `AIRMODE_ENGAGE_THROTTLE`=0.2 so attitude control
  persists the instant the craft leaves the ground. Default no-op; no change to validated feel.
- Default Roll/Pitch/Yaw PID confirmed equal to BF published values (45/80/30/120, 47/84/34/125,
  45/80/0/120; yaw D=0) — now pinned by headless [25].

## [Unreleased] — P2 cloud-verifiable fixes (track atomicity / monitor hand-mode / yaw ceiling / OSD horizon)

### Fixed
- **I-6 Track save atomicity** (`race/TrackStore.kt`): `save()` now serializes through a new
  `saveTo(path, doc)` that reuses `flight/AtomicFiles` (sibling temp file + atomic move/replace)
  instead of a direct `Files.writeString`. A crash mid-save can no longer leave a half-written
  track; a failed write leaves the previous file intact. Headless [18] checks no temp-file
  residue, non-empty target, and round-trip field preservation across an overwrite.
- **I-7 Monitor gimbal labels follow the hand mode** (`input/HandLayout.kt` reverse lookup;
  `client/gui/MonitorScreen.kt`): the monitor no longer hard-codes Mode-2 channel names. It now
  resolves each physical stick slot to its logical channel via `HandLayout.slotLabel(handMode, slot)`,
  so switching Mode 1↔2 relabels LH/LV/RH/RV correctly. Headless [19] verifies Mode-1 vs Mode-2
  names for the same physical slot.
- **I-9 yaw physical ceiling annotation** (`flight/AirframeProfile.yawPhysicalMaxDps()`,
  `FpvConfig.yawPhysicalMaxDps()`): the nominal yaw rate max (default 670 dps) exceeds what the
  Quad-X reaction-torque mixer can actually deliver. The new data-driven formula balances the
  saturated yaw reaction torque against viscous yaw damping (incl. motor [minThrottle,1] clamp) and
  reports ~406 dps at thr=0.5; the airframe config page prints "YAW full-stick physical ~N dps".
  No hard speed limit; roll/pitch feel unchanged. Headless [20] matches the analytic value to the
  real full-stick yaw step to within tolerance (406 vs 406).
- **I-10 OSD horizon direction pinned** (`flight/OsdLayoutMath.rollRad`/`groupDyPx`/new
  `horizonEndpointScreenDy`; `client/osd/FpvOsd.kt` now derives roll+shift from the shared math):
  the instrument overlays the horizon using the same JOML Matrix3x2f transform it draws with.
  Headless [21] drives the real plant and asserts right roll -> left endpoint higher than the right
  (leftY=-27.2 < rightY=+27.2 at 65 deg) and nose-down -> the horizon group rises on screen
  (groupDy=-179 px). NOTE: an intermediate cloud edit attempted to negate `rollRad`; the empirical
  FC-driven check showed that inverted the overlay, so the sign was kept at `atan2(bodyUp.x, bodyUp.y)`.
- **I-11 wash-ratio headless artifact** (`headless/FlightControlCheck.java` [3]): when clean-flight
  jitter is below 1 dps^2 the ratio is now reported as N/A instead of dividing by a near-zero
  denominator (which produced a huge/inf blow-up); added an explicit finite (no inf/NaN) assertion.

### Notes
- **I-12 (SE / server-compat landing point)**: no code change this pass; the real-world Monitor
  landing-point behaviour still needs on-hardware verification.

## [Unreleased] — E2E audit fixes: BF CLI import + rate-type selector

### Added
- **Betaflight CLI import** (`flight/BetaflightCli.kt`, clean-room parser of the public
  `set name = value` text format): per-axis rc_rate/super_rate/expo, global rc_rate/
  rc_expo/rc_yaw_expo, throttle_mid/throttle_expo/throttle_limit_type/percent,
  per-axis roll/pitch/yaw P/I/D/F, motor_idle, tpa_rate/tpa_breakpoint. Out-of-range
  values and unknown domain names are reported explicitly (never silently dropped);
  unrelated dump noise (serial/vtx/rx/led) is skipped and counted. UI: advanced page
  "Import BF CLI…" button opens `BfImportScreen` (paste box + Import + results).
- **Rate-type selector** (`FpvConfig.rateType` = ACTUAL/LEGACY/QUICK, default ACTUAL):
  the ACRO setpoint map now branches on the selected type, activating the previously
  dead `Rates.legacy` (rcRate/superRate) and `Rates.quick` branches. BF import sets
  rateType automatically. GUI cycle button on the advanced page.
- **Remote rubber-band setback wiring** (`flight/SetbackDetector.kt` pure classifier;
  `ClientPacketListenerMixin` on `handleMovePlayer`): absolute position snaps farther
  than `setbackThresholdBlocks` (default 2.0, debounced by `setbackCooldownMs`=800)
  now actually invoke `ServerCompatLogic.onSetback()` — firework interval widens
  10→40 ticks and the coordinated-turn gain softens. Relative-axis deltas and small
  corrections never trip it; single-player never reaches this path.
- **Continuous-axis binding path for S1/S2 knobs** (wizard `CalibrationScreen`):
  move-to-bind wizard gained S1/S2 analog steps (skippable; records measured
  min/mid/max + deadzone just like LS/RS). The bound channel becomes a named AUX
  entry that the existing Modes page can route to any FlightFunction band (e.g.
  ANGLE/HORIZON/HEADFREE). Default device profiles still leave S1/S2 unbound —
  nothing is guessed; the pilot binds them once with the wizard. AuxAnalyzer itself
  remains unused (auto-grouping of ghost buttons was not needed).
- **Unlock camera-tilt smoothing** (`flight/CameraTiltRamp.kt`): the FPV camera tilt
  (default 25 deg) no longer snaps in on arm — it ramps in at a bounded per-frame
  rate over `CAMERA_TILT_RAMP_SEC` (default 0.4s) and ramps back to 0 on disarm,
  eliminating the reported "unlock camera jerk". Measured per-frame delta is bounded
  so a lag spike can never produce a visible jump.

### Fixed
- `AxisRates` gained legacy-only `rcRate`/`superRate` fields (defaults 2.0/0.7);
  ACTUAL/QUICK behaviour unchanged (default values identical to before).
- `ServerCompatLogic.onSetback()` previously had zero callers (dead wire); the
  packet mixin + `FpvClient` event bus now connect detection → reaction.

### Verification
- headless [13] actual+legacy CLI samples parse correctly, out-of-range line captured;
  [14] same roll=0.5 stick yields ACTUAL=185 / LEGACY=308 / QUICK=335 dps (all finite,
  mutually distinct, full-stick ACTUAL=670); [15] big snap fires, small/relative/
  cooldown-suppressed cases verified, onSetback widens interval 10→40; [16] a
  continuous S1 aux at 0.8 engages ANGLE, at 0.2 releases it, an unbound channel
  never participates. [17] tilt ramps in monotonically (first frame only 1 deg, not
  25), reaches 25 deg at 0.4s, ramps back to 0 on disarm. Zero-warning build.

## [Unreleased] — quadcopter feel + firework-beat & high-speed root causes

### Root causes found (bytecode / headless, not guessed)
- **Remote firework beat was dead**: `LocalPlayerMixin.fpvCompatTick` built a fresh
  `ServerCompatLogic(sc)` every tick, resetting `ticksSinceFirework=MAX` so
  `shouldFirework` fired every tick above threshold — the whole anti-spam beat was
  bypassed (present since remote-compat commit `14d190b`). Now ONE persistent logic
  instance is held.
- **Why 122 km/h pinned (user OSD)**: disassembled `1.21.11` merged jar — an attached
  firework applies `vel <- 0.5*vel + 0.85*look` per tick, steady pin **1.7 b/t =
  122 km/h**; `lifetime = 10*power + rand(6) + rand(7)`, power=1 → mean **15.5 ticks**,
  onset ≈ **1 tick**. The pin is *vanilla elytra+firework terminal velocity*, NOT our
  (default-off) softSpeedLimit.
- **High-speed limit cycle**: my early `speedLimitGain` was applied unconditionally and
  its roof (1.7 b/t) coincides with the firework pin → gain→0 gaps rockets, speed sags,
  gain back → periodic on/off. Fixed: limiter is gated behind `cfg.softSpeedLimit`
  (default off) and uses a continuous cosine taper (no bang-bang).

### Added
- **`FireworkEnvelope.kt`** — data-driven, parameterized firework thrust envelope +
  `Fleet` simulator (MIT header citing 1.21.11 bytecode offsets: relax=0.5,
  accel=0.85, lifetime formula). Beat interval is derived from the envelope
  (`interval = lifetime/overlap`, overlap≈1.5) for continuous coverage, not a fixed
  sparse interval; throttle hysteresis (latch 0.25 / off 0.22), immediate stop below
  threshold, spool-down = already-lit rockets burn out naturally.
- **Quadcopter local model** (already body-up via `TranslationalDynamics`): verified
  zero-speed hover hold, on-spot yaw, vertical climb, no low-speed stall, pitch
  thrust-vector forward — local is a true quadcopter; remote remains an honest
  elytra+firework approximation.
- **Bank passive turn**: pure roll (yaw=0) integrates heading at
  `omega = gain·tan(φ)/V`; releases freezes (no unwind).
- **`MotionBlurCompositor.kt`** + **`OsdLayoutMath.kt`**: scene accumulates into blur
  history; OSD composited on top and never enters history; layout uses the SAME
  smoothed `flight.attitude` as the camera, integer pixel rows (no sub-pixel shimmer).

### Changed
- `fireworkThrottleThreshold` 0.5 → 0.25 (mid-throttle now a working boosted range).
- New Defaults: FW_*, TURN_*, SPEED_LIM_*, LOOK_LPF_HZ.

### Headless before → after (remote, throttle gears 0.3/0.5/0.7/1.0)
- gap fraction: **0.462 → 0.002**; high-band(>8Hz) absolute vertical RMS:
  **0.013 vyStd / limit-cycle → 0.0045 b/t**; spool-down bounded (≤ lifetime).
- Local full-throttle terminal: passes the 1.7 b/t vanilla pin → **3.81 b/t (274 km/h)**
  with settled-tail high-band ≈0 (no periodic oscillation).
- Pure-roll turn (yaw=0): **0 → 27.7°/100t** matching `g·tanφ/V`; released drift 0.
- Quadcopter: hover vy→0, on-spot yaw horizontal drift 0, vertical climb +3.7 b/t,
  no-stall dy>0, pitch-vector builds forward speed.
- `[1]-[12] ALL TESTS PASS`; zero-warning build (e:/w:=0).

### Remote can / cannot (elytra+fireworks physics)
- CAN: continuous thrust beat, bank coordinated turn, look low-pass mapping, smooth
  optional soft limiter.
- CANNOT: true zero-speed hover, on-spot yaw, pure vertical climb, stall-free low-speed
  flight. Local/create is the full quadcopter; remote is an approximation.

### Evidence gaps [NEEDS LOCAL VERIFICATION]
- Real Paper/Velocity firework feel & anti-kick trigger; real GL FBO/OSD layering &
  slow-shutter HUD ghosting; real R/C stick noise/center drift.

## [Unreleased] — fix disarmed mouse fight + coordinated-turn tremor

### Fixed
- **Disarmed/idle camera fought the mouse (forced recenter + tremor)**: on a remote
  server `LocalPlayerMixin` mapped `flight.attitude` onto the player look every tick
  even while disarmed (`flight.ready=false`); the disengaged attitude is level/south,
  so moving the mouse got pulled back and fought it. Added a `flight.ready` gate so
  the look is rewritten only while actually flying.
- **Coordinated-turn used yaw RATE instead of roll ANGLE**: `LocalPlayerMixin` passed
  `bodyRates[2]` (yaw angular velocity, noisy) where the bank angle was required,
  jittering the baked turn bias. Roll is now extracted from the attitude up vector
  (`atan2(up.x, up.y)`), which is smooth.

### Added
- **`headless/run_headless.ps1`** Windows counterpart to run the headless verification.
- Headless section [6]: frame-rate dt robustness — zero-stick hover at 60 fps
  (dt .0166) and under variable/hitched dt (up to .033) stays upright with no NaN,
  confirming the plant is stable at real render rates without fixed sub-stepping.

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
