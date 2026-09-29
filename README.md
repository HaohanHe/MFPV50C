# MFPV50C — FPV Craft

A **client-side Fabric mod** that turns Minecraft elytra flight into full-attitude FPV.

You effectively become an FPV quad: **no new entities and no server-side mod** — other
players still see the normal you, while your own view is a full roll / pitch / yaw FPV
camera with Betaflight-compatible *actual rates* feel.

## Features

- Full 3-axis attitude (roll / pitch / yaw) camera, applied entirely on the client
- Betaflight-compatible **Actual & Legacy rates** (clean-room), defaults 70 / 670 / 0
- USB transmitter / joystick support through GLFW: per-channel mapping, reverse, deadband
- **Calibration wizard**: stick mode (1/2/3/4) → center sticks → move each axis to auto-bind
- Flight modes:
  - **ACRO** — sticks command angular velocity (racing)
  - **ANGLE** — self-leveling, limited bank angle
  - **3D** — reversible thrust, throttle centered at zero (inverted hover)
- FPV OSD: speed, throttle %, artificial horizon, reticle
- Keyboard + mouse fallback

## Requirements

- Minecraft **1.21.11**, Fabric Loader + Fabric API, Java **21**

## Build

```
./gradlew build
```

The remapped jar is produced in `build/libs/`.

## Install & Use

1. Put the jar (and Fabric API) in your `mods/` folder.
2. In Controls, bind **Open FPV Settings**; `V` is the master toggle.
3. Open FPV Settings, select the device and run the **calibration wizard**
   (Mode 2 default: right stick = roll/pitch, left stick = throttle/yaw).
4. Wear an elytra, launch, and open the elytra in the air to enter FPV. No fireworks needed.

## Clean-room & License

Released under the **MIT License**. All flight math is implemented independently from
publicly documented models; **no Betaflight (GPL) source code is copied** — mathematics
itself is not copyrightable. Third-party names are used only for compatibility reference.

© 2026 BI4MIB (Haohan He)
