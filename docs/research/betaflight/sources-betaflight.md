# Sources — Betaflight 完整信号链调研

## 本地源码

- 仓库：`https://github.com/betaflight/betaflight.git`
- 克隆命令：`git clone --depth 1 https://github.com/betaflight/betaflight.git betaflight`
- 克隆位置（本工作区）：`/home/user/Doubao/chats/38444798710241538/work/betaflight/`
- Commit：`744f95fa31542c4c906f18072348a366ab11b6b7`（短 hash `744f95f`）
- Commit 日期：2026-09-28 18:06:59 +0200
- Commit subject：`Add Bosch Bmi423 Gyro driver (#15742)`
- 本地获取日期：**2026-09-29**

### 逐子系统引用的本地文件

| 子系统 | 本地路径（相对 `src/main/`） |
|---|---|
| 1. RX | `rx/rx.c`、`pg/rx.c`、`rx/rx.h`、`target/common_defaults_post.h`、`flight/failsafe.c`、`fc/rc_modes.c` |
| 2. Rates/Throttle | `fc/rc.c`、`fc/controlrate_profile.c`、`fc/rc_controls.c` |
| 3. Setpoint 预处理 | `fc/rc.c`（RC smoothing / feedforward）、`flight/pid.c`（TPA） |
| 4. 陀螺仪 | `sensors/gyro.c`、`sensors/gyro_filter_impl.c`、`sensors/gyro.h` |
| 5. PID 核心 | `flight/pid.c`、`flight/pid.h`、`flight/pid_init.c` |
| 6. Mixer/输出 | `flight/mixer_init.c`、`flight/mixer.c`、`drivers/motor.c`、`drivers/dshot.c`、`pg/motor.c` |
| 7. 电池 | `sensors/battery.c`、`sensors/voltage.c`、`sensors/current.c`、`sensors/battery.h` |
| 8. OSD | `osd/osd.h`、`osd/osd_elements.c`、`osd/osd.c` |
| 9. Blackbox | `blackbox/blackbox.c`、`blackbox/blackbox_encoding.c`、`blackbox/blackbox.h` |
| 10. Modes | `fc/rc_modes.h`、`fc/rc_modes.c`、`fc/runtime_config.h`、`flight/imu.c` |

## 官方文档 / Wiki URL（备查，本轮未在线抓取，仅作为交叉参考）

- Betaflight 官方文档站：https://betaflight.com/docs/
- Betaflight Wiki（GitHub）：https://github.com/betaflight/betaflight/wiki
- CLI 命令参考：https://betaflight.com/docs/wiki/cli-commands/BB
- Rates 说明（Actual/Quick/Legacy）：https://betaflight.com/docs/wiki/guides/current/Rates-Tuning
- 滤波器调参指南：https://betaflight.com/docs/wiki/guides/current/Frequency-Response-and-Filter-Tuning
- 3D 模式：https://betaflight.com/docs/wiki/guides/current/3D-Setup
- Blackbox 日志格式：https://github.com/betaflight/betaflight-blackbox
- OSD 元素列表：https://betaflight.com/docs/wiki/features/OSD
- Failsafe 配置：https://betaflight.com/docs/wiki/guides/current/Failsafe-Settings

## 合规说明

- Betaflight 源码为 GPLv3。本调研仅阅读、总结算法结构、公式、默认参数与单位，未逐字复制源码到交付物中。
- 交付物（report-betaflight-full.md / data-betaflight-full.json）面向自研 Minecraft Fabric Kotlin 模组的 clean-room 重写，不包含任何 GPL 代码片段、注释原文或逐行翻译。
- 本轮未对 `work/MFPV50C` 工程源码做任何修改。
