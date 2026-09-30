# Changelog

All notable changes to this mod are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project uses
Semantic Versioning; `main` is the development branch and `stable` points at the
latest fully verified (zero-warning build + all verification scripts PASS)
revision. No formal GitHub Release is published until real-hardware acceptance.

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
