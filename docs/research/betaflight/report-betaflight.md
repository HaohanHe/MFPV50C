# Betaflight 完整信号链 Clean-Room 调研报告

> 调研对象：Betaflight 官方仓库 `https://github.com/betaflight/betaflight`
> Commit：`744f95fa31542c4c906f18072348a366ab11b6b7`（短 hash `744f95f`）
> Commit 作者日期：2026-09-28 18:06:59 +0200（Subject: `Add Bosch Bmi423 Gyro driver (#15742)`）
> 本地获取日期：2026-09-29（浅克隆 `--depth 1`）
> 调研目的：为自研 Minecraft Fabric 纯客户端 FPV 模组（锁 MC 1.21.11，Kotlin）提供可 clean-room 落地的模块蓝图。
> 合规：仅学习算法、结构、默认参数、单位；禁止逐字复制 .c 源码；不引用任何 GPL 注释原文。

本报告按 10 个子系统组织，每个子系统给出：源文件/函数、公式、默认参数表（带单位）、配置项、clean-room 重写要点。所有数值均直接来自 `src/main/` 下的 PG 重置函数或头文件宏，未编造。

---

## 1. RX / 通道语义

**源文件**：`src/main/rx/rx.c`、`src/main/pg/rx.c`、`src/main/rx/rx.h`、`src/main/target/common_defaults_post.h`、`src/main/fc/rc_modes.c`

### 1.1 通道归一化

- 物理通道宽度以 µs 为单位。合法脉宽窗口：`PWM_PULSE_MIN=750µs`，`PWM_PULSE_MAX=2250µs`（`rx.h:38-39`）。
- 标准 RC 量程：`PWM_RANGE_MIN=1000`，`PWM_RANGE_MAX=2000`，中点 `RX_MID_USEC=1500`（`rx.h:33-36`，`common_defaults_post.h:97-99`）。
- 丢包检测窗口：`RX_MIN_USEC=885`，`RX_MAX_USEC=2115`。任何前 4 个通道之一超出该窗口即视为坏通道（`isPulseValid()`，rx.c:234）。
- 上电后 `rcData[i]` 全部初始化为 `midrc=1500`；油门在非 3D 模式下初始化为 `rx_min_usec=885`（rx.c:353-358）。
- 通道映射（`rcmap`）：默认 `"AETR1234"`（即 Roll=Ch1, Pitch=Ch2, Yaw=Ch3, Throttle=Ch4，其余为 AUX）。`rcChannelLetters="AERT12345678abcdefgh"`（rx.c:76, pg/rx.c:parseRcChannels）。
- 通道校准：`scaleRangefInit(..., min, max, 1000, 2000)`，把接收机实际 min/max 线性映射到 1000–2000（rx.c:348-350）。
- `NON_AUX_CHANNEL_COUNT=4`，`MAX_SUPPORTED_RC_CHANNEL_COUNT=18`，默认 AUX 通道数 12（`DEFAULT_AUX_CHANNEL_COUNT`）。

### 1.2 信号丢失 / Failsafe 阶段

**阶段 1（保持）**：
- 单通道坏脉宽后，保持最后有效值 `rcData[channel]` 最多 `MAX_INVALID_PULSE_TIME_MS=300ms`（rx.c:131, 791-794）。
- 整帧丢失判定：`RXLOSS_TRIGGER_INTERVAL=150ms` 无新帧即标记 `rxSignalReceived=false`；之后每 `RX_FRAME_RECHECK_INTERVAL=50ms` 复检（rx.c:137, 615-632）。
- 300ms 后坏通道进入 `getRxfailValue()`：ROLL/PITCH/YAW→`midrc=1500`；THROTTLE→`rx_min_usec=885`（3D 未激活时）；AUX→按通道 failsafe 模式（AUTO/HOLD/SET）。

**阶段 2（failsafe 动作）**：源文件 `src/main/flight/failsafe.c`
- 默认参数：
  - `failsafe_delay = 15`（单位 0.1s，即 1.5s 阶段 1 周期）
  - `failsafe_throttle = 1000`（阶段 2 油门 µs）
  - `failsafe_throttle_low_delay = 10`（0.1s，"刚 disarm" 判定油门低时间）
  - `failsafe_landing_time = 60`（秒，自动降落最长时间）
  - `failsafe_recovery_delay = 5`（0.1s=500ms 恢复链路需要的有效数据时长）
  - `failsafe_procedure = FAILSAFE_PROCEDURE_DROP_IT`（默认直接降落/disarm；可选 AUTO_LAND、GPS_RESCUE）
  - `failsafe_switch_mode = FAILSAFE_SWITCH_MODE_STAGE1`（BOXFAILSAFE 开关行为同链路丢失）
  - `failsafe_stick_threshold = 30`（%，退出 GPS Rescue 所需杆量）
- 状态机：`FAILSAFE_IDLE → RX_LOSS_DETECTED → (LANDING | LANDED | GPS_RESCUE) → RX_LOSS_MONITORING → RX_LOSS_RECOVERED → IDLE`。阶段 2 进入 LANDED 后调用 `disarm(DISARM_REASON_FAILSAFE)`。

### 1.3 RSSI

- 内部表示 0–1023（`RSSI_MAX_VALUE=1023`）。来源可配：ADC、AUX 通道、帧错误率、MSP、CRSF dBm。
- ADC RSSI：`rssi = adcSample / (4096/1024) = adcSample/4`（rx.c:110, 934）。
- AUX 通道 RSSI：把 1000–2000µs 线性映射到 0–1023（`scaleRangePwmRssi`，rx.c:910-918）。
- 输出：`getRssi() = rssi_scale/100 * rssi + rssi_offset * (1024/100)`（rx.c:999），默认 `rssi_scale=100`、`rssi_offset=0`。
- 平滑：PT1 滤波器，增益 `k=(256-rssi_smoothing)/256`，默认 `rssi_smoothing=125`。
- CRSF dBm：范围 [-130, 0]；RSNR 范围 [-30, +20]。

### 1.4 AUX / 模式开关解析

源文件 `fc/rc_modes.c`：
- 每个模式由一个 MAC（mode activation condition）描述：`{modeId, auxChannelIndex, range{startStep,endStep}, modeLogic(AND/OR), linkedTo}`。
- 通道值先 constrain 到 `[CHANNEL_RANGE_MIN=900, CHANNEL_RANGE_MAX=2100)`。
- 激活判定（`isRangeActive`，rc_modes.c:101-110）：
  ```
  channelValue ∈ [900 + startStep*25, 900 + endStep*25)
  ```
  step 单位 25µs，范围 0–48（共 49 个档位）。
- 多 MAC 通过 AND/OR 逻辑组合（`updateMasksForMac`）；`BOXPARALYZE`/`BOXBLACKBOXERASE` 为 sticky 模式（上电后第一次离开即锁存）。
- `airmodeEnabled = featureIsEnabled(FEATURE_AIRMODE) || IS_RC_MODE_ACTIVE(BOXAIRMODE)`（rc_modes.c:203）。

### Clean-room 重写要点

- Kotlin 端把"遥控器"抽象为 `RcChannels`：`roll/pitch/yaw/throttle: Double`（-500..500 / 1000..2000）+ `aux: List<Int>`（µs）。
- Failsafe 状态机可直接照状态图重写：300ms 保持 → 1.5s 阶段1 → 阶段2（DROP=立即 disarm；LANDING=60s 后 disarm）。
- AUX 档位用 `(value-900)/25` 得 step，区间判断即可。

---

## 2. Rates / 油门

**源文件**：`src/main/fc/rc.c`、`src/main/fc/controlrate_profile.c`、`src/main/fc/rc_controls.c`

### 2.1 输入归一化（updateRcCommands，rc.c:697）

- Roll/Pitch/Yaw：`rc = constrain(rcData[i] - midrc, -500, 500)`，再 `fapplyDeadband(rc, deadband)`。Yaw 额外乘 `-yaw_control_reversed`。
- 默认 `deadband=0`、`yaw_deadband=0`（rc_controls.c:81-82）。
- `rcCommandDivider = 500 - deadband`（roll/pitch），`rcCommandYawDivider = 500 - yaw_deadband`（rc.c:822-823）。即 stick 偏转归一化到 [-1, +1]。

### 2.2 Rates 类型（applyRates 函数指针表）

默认 `rates_type = RATES_TYPE_ACTUAL`。默认参数（controlrate_profile.c:42-67）：
- `rcRates[RPY] = 7`（Actual 模式下 = 中心灵敏度 70 dps）
- `rates[RPY] = 67`（Actual 模式下 = 最大速率 670 dps）
- `rcExpo[RPY] = 0`
- `rate_limit[RPY] = 1998 dps`（`CONTROL_RATE_CONFIG_RATE_LIMIT_MAX`）

**Actual Rates**（`applyActualRates`，rc.c:232-242）：
```
expof   = rcExpo/100
expof   = |x| * (x^5 * expof + x * (1-expof))
centerSensitivity = rcRates * 10          // 默认 70
stickMovement     = max(0, rates*10 - centerSensitivity)  // 默认 670-70=600
angleRate         = x * centerSensitivity + stickMovement * expof
```
即 `out = C·x + (M-C)·[e·x⁵ + (1-e)·x³·|x|]`，与 agent-hint 一致。

**Betaflight (legacy) Rates**（`applyBetaflightRates`，rc.c:190-208）：
```
if expo: x = x*(x²*expof + (1-expof))     // 三次 expo
rcRate = rcRates/100
if rcRate>2: rcRate += 14.54*(rcRate-2)   // 2.0 以上非线性扩展
angleRate = 200 * rcRate * x
if superfactor: angleRate /= max(1 - |x|*rates/100, 0.01)
```

**Quick Rates**（rc.c:244-266）：
```
rcRate    = rcRates * 2                    // 默认 14 dps 每单位
maxDPS    = max(rates*10, rcRate)          // 默认 670
sfConfig  = (maxDPS/rcRate - 1) / (maxDPS/rcRate)
curve     = x³*expof + x*(1-expof)         // quickRatesRcExpo=0 时用 |x|
superFactor = 1 / max(1 - curve*sfConfig, 0.01)
angleRate = constrain(x * rcRate * superFactor, -1998, 1998)
```

（RaceFlight、KISS 类型存在但非默认，略。）

### 2.3 油门曲线（initRcProcessing，rc.c:820-864）

- 默认 `thrMid8=50`、`thrExpo8=0`、`thrHover8=50`。两段二次贝塞尔：
  - 段1：控制点 (0,0)→(cp1x,cp1y)→(thrMid,thrHover)，其中 `cp1x=thrMid/2`，`cp1y=thrHover/2*(1+expo)`。
  - 段2：(thrMid,thrHover)→(cp2x,cp2y)→(1,1)，`cp2x=(1+thrMid)/2`，`cp2y=1+(thrHover-1)/2*(1+expo)`。
  - expo=0 时两段退化为直线，整条曲线退化为 y=x。
- 输出写入 12 项查找表 `lookupThrottleRC[0..11]`，范围映射到 1000–2000µs，运行时线性插值（`rcLookupThrottle`，rc.c:164-182）。
- 非 3D 油门输入预处理：`tmp = constrain(rcData[THR], mincheck=1050, 2000) - mincheck`，再 `*1000/(2000-mincheck)` 归一到 0..1000（rc.c:718-719）。
- 低压切断时 `tmp *= lvcPercentage/100`（rc.c:722-724）。
- `throttle_limit_type` 默认 OFF；可选 SCALE/CLIP，`throttle_limit_percent=100`。

### 2.4 3D 油门（rc.c:714-745, mixer.c:119-226）

- 3D 模式下油门输入不扣 mincheck，直接 `tmp = rcData[THR] - 1000`（0..1000）。
- `deadband3d_throttle=50`（µs，相对 midrc=1500 的死区半宽）。
- `neutral3d=1460`、`deadband3d_low=1406`、`deadband3d_high=1514`（rc_controls.c:95-103）。
- BOX3D 激活时：摇杆在 midrc 上方 → 正向电机（输出映射到 1500–2000）；下方 → 反向（1000–1500）。
- 电机反转后 250ms 内 `pidResetIterm()`（mixer.c:223-226）。

### 2.5 Throttle Boost

- `throttle_boost=5`、`throttle_boost_cutoff=15Hz`（pid.c:167-168）。
- 实现（mixer.c:738-743）：`throttleHpf = throttle - pt1FilterApply(throttleLpf, throttle)`；`throttle = constrain(throttle + throttleBoost * throttleHpf, 0, 1)`。即油门变化量的高通前馈。

### Clean-room 重写要点

- Kotlin 端 Rates 只需实现 Actual（默认）+ Quick 即可，公式如上。单位 dps。
- 油门贝塞尔曲线可在初始化时算 12 项 LUT，运行时 O(1) 插值。
- 3D 模式对 MC 模组意义不大（MC 没有反转电机），可只实现单向油门。

---

## 3. Setpoint 预处理

**源文件**：`src/main/fc/rc.c`（RC smoothing、Feedforward）、`src/main/flight/pid.c`（TPA）

### 3.1 RC Smoothing（PT3 滤波器）

- 默认 `rc_smoothing=1`（启用）。`rc_smoothing_auto_factor_rpy=30`、`rc_smoothing_auto_factor_throttle=30`（pg/rx.c）。
- 自动截止频率（rc.c:346-383）：
  ```
  autoFactorSp      = 1.5 / (1 + auto_factor_rpy/10)        // 默认 1.5/4 = 0.375
  setpointCutoffHz  = max(15, smoothedRxRateHz * autoFactorSp)
  throttleCutoffHz  = max(15, smoothedRxRateHz * autoFactorThr)
  ```
  即 50Hz RX → 18.75Hz setpoint cutoff；默认下限 15Hz。
- 手动覆盖：`rc_smoothing_setpoint_cutoff=0`（auto）、`rc_smoothing_throttle_cutoff=0`（auto）。
- 滤波器类型：PT3（三阶），对 setpoint、feedforward、rcDeflection（仅 roll/pitch 在 HORIZON 模式）分别滤波。
- `smoothedRxRateHz` 通过一阶 LPF（α=0.1）跟踪实际 RX 帧率，带 outlier 拒绝（与均值偏差 >20% 视为 outlier，连续 3 个同向 outlier 才 snap）。

### 3.2 Feedforward（calculateFeedforward，rc.c:432-562）

- `setpointSpeed = Δsetpoint * rxRateHz`（dps/s）。
- 抖动衰减：`jitterAttenuator = min(1, ((|ΔrcCmd| + prev|ΔrcCmd|)/2 + 1) * feedforwardJitterFactorInv)`，默认 `feedforward_jitter_factor=7`（即 inv=1/7≈0.143）。
- 两次 PT1 平滑：setpointSpeed → setpointSpeedDelta。
- Feedforward boost：`feedforwardBoost = setpointSpeedDelta * rxRate * feedforwardBoostFactor`，默认 `feedforward_boost=15`。
- Yaw 额外有 `feedforwardYawHold = feedforwardYawHoldGain * (setpoint - lpfYaw(setpoint))`，默认 gain=15、time=100ms。
- 默认 `feedforward_averaging = 2_POINT`、`feedforward_max_rate_limit=90`、`feedforward_smooth_factor=65`。

### 3.3 TPA（pid.c:414-470）

- 默认 `tpa_mode = TPA_MODE_D`（只衰减 D）；可选 `TPA_MODE_PD`。
- 经典 TPA 公式（getTpaFactorClassic）：
  ```
  tpaRate  = tpa_rate/1000 * max(throttle - tpa_breakpoint, 0)   // 默认 tpa_rate=65, breakpoint=1350
  tpaFactor = 1 - tpaRate
  ```
  即油门 1350µs 以上，每增加 1% 油门，D 增益下降 0.065%。
- 低油门 TPA：`tpa_low_rate=20`、`tpa_low_breakpoint=1050`、`tpa_low_always=0`。
- `getTpaFactor`：TERM_P 在 TPA_MODE_PD 时乘 tpaFactor；TERM_D 总是乘；TERM_I/F 不乘。

### Clean-room 重写要点

- PT3 滤波器可直接用 3 个级联 PT1 实现（`y += k*(x-y)` 三次），k 由 `pt1FilterGain(cutoffHz, dT)` 给出。
- TPA 在 MC 模组里可简化为"油门越高 D 越小"的线性衰减，默认 breakpoint=50% 油门。
- Feedforward 对 MC 竞速体感重要，但可先只实现 setpointSpeed 项，boost/yaw hold 后置。

---

## 4. 陀螺仪

**源文件**：`src/main/sensors/gyro.c`、`src/main/sensors/gyro_filter_impl.c`、`src/main/sensors/gyro.h`

### 4.1 校准流程

- 上电校准时长：`gyroCalibrationDuration=125`（单位 0.01s，即 1.25s，gyro.c:123）。
- 校准期间累加 `gyroADCRaw` 并计算标准差；`gyroMovementCalibrationThreshold=48`（gyro ADC LSB）。若任一轴标准差超阈值则重新开始（gyro.c:218-272）。
- 零偏：`gyroZero[axis] = sum[axis] / cycles`；Yaw 额外减 `gyro_offset_yaw/100`（默认 0）。
- 校准完成后 `gyroADC[axis] = gyroADCRaw[axis] - gyroZero[axis]`，再按板旋转矩阵对齐。

### 4.2 软滤波链顺序（gyro_filter_impl.c:23-95）

每帧 gyro 采样后：
1. 下采样（平均或 LPF2）到 PID 循环频率。
2. RPM filter（若启用）。
3. `notchFilter1`（静态陷波1）。
4. `notchFilter2`（静态陷波2）。
5. `lowpassFilter`（陀螺仪 LPF1，动态或静态）。
6. `dynNotchFilter`（FFT 动态陷波，若启用）。
7. 输出 `gyro.gyroADCf[axis]`（单位 dps）。
8. 再经 `imuGyroFilter`（PT1）输出给 IMU/姿态解算。

### 4.3 默认参数（gyro.c:121-150）

| 参数 | 默认值 | 单位 |
|---|---|---|
| gyro_hardware_lpf | GYRO_HARDWARE_LPF_NORMAL | - |
| gyro_lpf1_type | PT1 | - |
| gyro_lpf1_static_hz | 250（=GYRO_LPF1_DYN_MIN_HZ_DEFAULT） | Hz |
| gyro_lpf1_dyn_min_hz | 250 | Hz |
| gyro_lpf1_dyn_max_hz | 500 | Hz |
| gyro_lpf1_dyn_expo | 5 | /10 |
| gyro_lpf2_type | PT1 | - |
| gyro_lpf2_static_hz | 500（GYRO_LPF2_HZ_DEFAULT） | Hz |
| gyro_soft_notch_hz_1 | 0（关） | Hz |
| gyro_soft_notch_cutoff_1 | 0 | Hz |
| gyro_soft_notch_hz_2 | 0（关） | Hz |
| gyro_soft_notch_cutoff_2 | 0 | Hz |
| yaw_spin_recovery | AUTO | - |
| yaw_spin_threshold | 1950 | dps |

动态 LPF 截止随油门变化（`dynLpfCutoffFreq`，pid.c:1630）：
```
curve = throttle*(1-throttle)*expo/10 + throttle
cutoff = (max-min)*curve + min
```

### Clean-room 重写要点

- MC 模组没有真实 gyro，可直接用"虚拟 gyro"= 期望角速度的数值积分/差分。但滤波链对 MC 意义有限，可只保留 D 项前的一阶 LPF（默认 100Hz yaw lowpass、D-term LPF1 动态 75–150Hz、LPF2=150Hz）。
- 校准流程在 MC 里不需要（虚拟传感器），但可保留"起飞前 1.25s 零偏"语义。

---

## 5. PID 核心

**源文件**：`src/main/flight/pid.c`、`src/main/flight/pid.h`、`src/main/flight/pid_init.c`

### 5.1 默认 P/I/D/FF（pid.h:64-71）

| 轴 | P | I | D | FF |
|---|---|---|---|---|
| Roll | 45 | 80 | 30 | 120 |
| Pitch | 47 | 84 | 34 | 125 |
| Yaw | 45 | 80 | 0 | 120 |

比例缩放（pid.h:46-48）：
- `Kp = P * 0.032029`
- `Ki = I * 0.244381`
- `Kd = D * 0.000529`
- Kf = FF 项（feedforward 增益）。

### 5.2 控制律（pid.c:1270-1532）

每个轴每 PID 循环：
1. `setpoint = getSetpointRate(axis)`（dps），经 accelerationLimit（`rateAccelLimit=0` 默认关）。
2. Angle 模式：`pidLevel()` 把 setpoint 转成角度目标。
3. `errorRate = setpoint - gyroRate`。
4. **P**：`P = Kp * errorRate * tpaFactor(TERM_P)`。Yaw 的 P 再经 `ptermYawLowpass`（默认 100Hz）。
5. **I**：
   - `iTermChange = (Ki + itermAccelerator) * dT * itermErrorRate`。
   - `itermErrorRate` 可被 itermRelax 衰减。
   - `I = constrain(I + iTermChange, -itermLimit, +itermLimit)`。
   - Roll/Pitch `itermLimit = 400`（默认，即 pidSumLimit=500 的 80%）；Yaw `itermLimitYaw = 400`（pidSumLimitYaw=400）。
6. **D**：
   - `delta = -(gyroRateDterm - prevGyroRateDterm) * pidFrequency`（注意负号：D 项对 gyro 微分为**负**，pid.c:1430）。
   - `preTpaD = Kd * delta * dMaxMultiplier`。
   - `D = preTpaD * tpaFactor(TERM_D)`。
   - D 输入前经过 dtermNotch（默认关）+ dtermLowpass1（动态 75–150Hz）+ dtermLowpass2（静态 150Hz）。
7. **FF**：`F = Kf * pidSetpointDelta`（来自 rc.c 的 calculateFeedforward）。
8. **Sum**：`Sum = P + I + D + F + S`（S 项仅固定翼）。
9. Sum 经 mixer：`scaledPid = constrain(Sum, ±pidSumLimit) / PID_MIXER_SCALING(=1000)`。

### 5.3 Anti-gravity（pid.c:472-491, 1144-1156, 1516-1525）

- 油门导数：`throttleDeriv = |throttle - prevThrottle| * pidFreq * (1-throttle)²`，油门上升时再乘 `(1-throttle)*0.5`。
- PT2 滤波（默认 cutoff=5Hz）。
- `itermAccelerator = throttleDeriv * antiGravityGain(=80) * ANTIGRAVITY_KI`。
- P 项额外 boost：`P *= 1 + throttleDeriv * antiGravityPGain(=100) / max(|setpoint|/50, 1)`。

### 5.4 I-term Relax（pid.c:881-915）

- 默认 `iterm_relax = ITERM_RELAX_RP`（Roll/Pitch）、`iterm_relax_cutoff=15Hz`、`iterm_relax_type=SETPOINT`。
- `setpointLpf = PT1(setpoint, 15Hz)`；`setpointHpf = |setpoint - setpointLpf|`。
- `factor = max(0, 1 - setpointHpf / 80dps)`（`ITERM_RELAX_SETPOINT_THRESHOLD`）。
- 在 SETPOINT 模式下 `itermErrorRate *= factor`——setpoint 快速变化时不积分，防止绕弯时 I 累积。

### 5.5 Crash Recovery（pid.c:685-757）

- 默认 `crash_recovery=OFF`。启用后阈值：
  - `crash_dthreshold=50 dps/s²`（D 项突变）
  - `crash_gthreshold=400 dps`（gyro 速率）
  - `crash_setpoint_threshold=350 dps`（setpoint 必须低于此值）
  - `crash_time=500ms`、`crash_delay=0ms`、`crash_limit_yaw=200`
  - 恢复条件：姿态偏差 < `crash_recovery_angle=10°` 且三轴 gyro < `crash_recovery_rate=100 dps`。

### 5.6 Angle / Horizon 模式

- **Angle**（pidLevel，pid.c:567-683）：
  - `angleTarget = angleLimit(=60°) * setpoint/maxRcRate`。
  - `errorAngle = angleTarget - currentAngle`（°）。
  - `angleRate = errorAngle * angleGain + angleFeedforward`。
  - 经 PT3 滤波（ATTITUDE_CUTOFF_HZ≈50Hz）。
- **Horizon**（calcHorizonLevelStrength，pid.c:542-562）：
  - `levelStrength = max((horizonLimitDegrees(=135) - currentInclination), 0) / horizonLimitDegrees`
  - `*= max((horizonLimitSticks - |maxStickDeflection|), 0) / horizonLimitSticks`
  - `*= horizonGain`（默认 pid[LEVEL].P=50）。
  - 最终 setpoint = `acroSetpoint*(1-levelStrength) + angleRate*levelStrength`。
  - `horizon_delay_ms=500`（PT1 平滑 strength 的上升）。

### Clean-room 重写要点

- MC 模组的"虚拟陀螺速率"可由玩家输入直接给定（dps），P/I/D 控制律可直接照搬。
- D 项符号务必是负的（`delta = -(gyro - prevGyro)*freq`），这是最容易写错的地方。
- Anti-gravity 和 itermRelax 是 Betaflight 手感的关键，建议都实现。
- Angle/Horizon 对 MC 模组可作为"自稳模式"参考。

---

## 6. Mixer / 输出

**源文件**：`src/main/flight/mixer_init.c`、`src/main/flight/mixer.c`、`src/main/drivers/motor.c`、`src/main/drivers/dshot.c`、`src/main/pg/motor.c`

### 6.1 Quad-X 混控表（mixer_init.c:84-89）

列顺序 `{throttle, roll, pitch, yaw}`：

| 电机 | throttle | roll | pitch | yaw |
|---|---|---|---|---|
| M1 REAR_R | +1.0 | -1.0 | +1.0 | -1.0 |
| M2 FRONT_R | +1.0 | -1.0 | -1.0 | +1.0 |
| M3 REAR_L | +1.0 | +1.0 | +1.0 | +1.0 |
| M4 FRONT_L | +1.0 | +1.0 | -1.0 | -1.0 |

输出公式（mixer.c:770-781）：
```
motorMix[i] = scaledRoll * roll_i + scaledPitch * pitch_i + scaledYaw * yaw_i
```
其中 `scaledRoll = constrain(pidSum_Roll, ±500)/1000`，`scaledYaw = constrain(pidSum_Yaw, ±400)/1000`。Yaw 输出取负（除非 `yaw_motors_reversed`）。

### 6.2 Airmode（mixer.c:555-590, 656-675）

- Airmode 关闭且油门 < 0.5 时，混控权限随油门线性过渡：0 油门 → 50% 权限，0.5 油门 → 100%。
- Airmode 开启时全程 100% 权限（低油门也能做 RPY 修正）。
- `airModeActivateThreshold=25`（%，RX 配置，未在主流程强制使用）。

### 6.3 油门到电机输出（mixer.c:119-277）

- 非 3D：`throttle = (rcCommand[THR] - 1000)/1000`（0..1）。
- `motorRangeMin = motorOutputLow + dynIdleOffset*(high-low)`；`motorRangeMax = motorOutputHigh`。
- 每个电机：`motorOutput = motorOutputMin + motorOutputRange * (motorOutputMixSign * motorMix[i] + throttle * throttle_i)`（mixer.c:461-465）。
- 最后 constrain 到 `[motorRangeMin, motorRangeMax]`。

### 6.4 默认电机端点（pg/motor.c, drivers/dshot.c:59-80）

| 参数 | 默认值 | 单位 |
|---|---|---|
| motorIdle (brushless) | 550 | ‰（=5.5%） |
| motorIdle (brushed) | 700 | ‰ |
| maxthrottle | 2000 | µs（PWM） |
| mincommand | 1000 | µs |
| DSHOT_MIN_THROTTLE | 48 | - |
| DSHOT_MAX_THROTTLE | 2047 | - |
| DSHOT_3D_FORWARD_MIN_THROTTLE | 1048 | - |
| motorPoleCount | 14 | 极 |

DShot 非 3D：`outputLow = 48 + 5.5%*(2047-48) ≈ 159`；`outputHigh = 2047`。

### 6.5 3D 可逆

- 3D 模式下油门中点 1500 为零推力。低于 1450（1500-50）反转，高于 1550 正转。
- `motorOutputMixSign = ±1` 切换混控符号。
- 反转后 250ms 内清 I-term。

### Clean-room 重写要点

- MC 模组里"电机"映射为 4 个虚拟推力（或直接映射到 MC 实体的角速度/速度）。混控表可直接照搬。
- Airmode 语义：无 airmode 时低速 RPY 权限减半，这对 MC 里"贴地漂移"手感很重要。
- 3D 可逆在 MC 里可省略（除非做倒飞翻转）。

---

## 7. 电池

**源文件**：`src/main/sensors/battery.c`、`src/main/sensors/voltage.c`、`src/main/sensors/current.c`

### 7.1 电压分压（voltage.c:161-166）

```
Vbat (0.01V) = (adc * vbatscale * vrefMv/10 + 0xFFF*5) / (0xFFF * vbatresdivval) / vbatresdivmultiplier
```
- 默认 `vbatscale=110`、`vbatresdivval=10`（10:1 分压）、`vbatresdivmultiplier=1`。
- ADC 12bit（0xFFF=4095），vref=3.3V。
- 即 `Vbat = adc/4095 * 3.3 * 10 * 1.10`。

### 7.2 电芯数自动判定（battery.c:205-212）

```
cells = (displayFiltered / vbatmaxcellvoltage) + 1
cells = min(cells, 8)
```
- 默认 `vbatmaxcellvoltage=430`（4.30V）、`vbatfullcellvoltage=410`、`vbatwarningcellvoltage=350`、`vbatmincellvoltage=330`。
- 即满电 4.10V/节、警告 3.50V/节、临界 3.30V/节。
- `vbatnotpresentcellvoltage=300`（<3.0V/节视为无电池）。

### 7.3 mAh 累计模型（current.c:128-131）

```
mAhDrawnF += amperageLatest * lastUpdateAt / (100 * 1000 * 3600)
```
- amperageLatest 单位 0.01A（centi-amp），lastUpdateAt 单位 µs。
- 化简：`mAh += (A * s) / 3600`（标准库仑积分）。
- Wh 累计：`Wh += Vbat * ΔmAh / 100000`（battery.c:278）。
- 百分比剩余（无容量配置时）：线性插值 `(Vbat - Vmin)/(Vmax - Vmin)`（battery.c:569-579）。

### 7.4 低压告警

- 状态机 OK→WARNING→CRITICAL，带滞回 `vbathysteresis=1`（0.01V）。
- `vbatDurationForWarning=0`、`vbatDurationForCritical=0`（立即触发）。
- LVC（低压切断油门）：`lvcPercentage=100`（默认关），若启用则 10s 内从 100% 线性降到设定百分比（battery.c:323-341）。

### Clean-room 重写要点

- MC 模组可虚拟电池：`cells=4S`，电压随虚拟油门/sag 下降。mAh 累计直接用 `∫I dt/3600`。
- 告警阈值：warning=3.50V/cell、critical=3.30V/cell，可直接照搬。

---

## 8. OSD

**源文件**：`src/main/osd/osd.h`、`src/main/osd/osd_elements.c`

### 8.1 坐标体系

- SD（PAL）：`OSD_SD_COLS=30` 字符列、`OSD_SD_ROWS=16` 字符行（MAX7456）。
- HD：`OSD_HD_COLS=53`、`OSD_HD_ROWS=20`。
- 位置编码 16bit：低 5 位 X（0–31），中 5 位 Y（0–31），bit10 扩展 X 到 0–63，bit14-15 为类型标记。`OSD_POS(x,y)` 宏。
- 每个元素位置可配置（用户在 OSD 菜单里移动），无固定布局——除了 `OSD_CROSSHAIRS`、`OSD_ARTIFICIAL_HORIZON`、`OSD_HORIZON_SIDEBARS` 这几个 HUD 类元素按屏幕中心自动绘制。

### 8.2 元素全集（osd_items_e，osd.h:122-237）

共约 100+ 元素，核心包括：RSSI、电池电压、交叉线、人工地平仪、侧杆、Timer1/2、飞行模式、机名、油门位置、VTX 通道、电流、mAh、GPS 速度/卫星/经纬度、高度、Roll/Pitch/Yaw PIDs、功率、PID rate profile、警告、平均电芯电压、调试值、俯仰/滚转角、电池用量、DISARMED、Home 方向/距离、数值航向/垂直速度、罗盘条、ESC 温度/RPM、预计剩余时间、RTC 时间、核心温度、AntiGravity、G-force、电机诊断、日志状态、翻转箭头、链路质量、飞行距离、左右摇杆叠加、飞行员名、ESC RPM 频率、rate/pid/profile 名、RSSI dBm、RC 通道、相机边框、效率、总飞行次数、上下参考、TX 上行功率、Wh 累计、AUX 值、Ready 模式、RSNR、护目/VTX 电压/码率/延迟/距离/LQ/DVR/温度/风扇、GPS 圈速当前/上圈/最佳3、DEBUG2、自定义消息 0-3、Lidar 距离、自定义串口文本、电池 profile 名、航点元素、导航地图、位置保持就绪、空速。

### 8.3 单位约定

- 电压：0.01V（显示时除 100）；电芯：mV。
- 电流：0.01A；mAh：mAh。
- 角度：0.1°；陀螺速率：dps。
- 高度：m；距离：m；速度：m/s 或 km/h（可配）。
- 时间：秒/分:秒（可配精度 1s/0.1s/0.01s）。
- RSSI：0–100%；dBm：dBm；LQ：%。

### Clean-room 重写要点

- MC 模组的 OSD 可简化为 HUD：交叉线+人工地平仪+电池+飞行模式+圈速。坐标用 MC 的 2D 屏幕像素即可，不必照搬字符网格。
- 固定布局的只有 HUD 中心元素；其他元素位置用户可配。

---

## 9. Blackbox

**源文件**：`src/main/blackbox/blackbox.c`、`blackbox_encoding.c`、`blackbox_fielddefs.h`

### 9.1 采样率

- 默认 `sample_rate = BLACKBOX_RATE_QUARTER`（即每 4 个 PID 循环记录一帧；枚举：1=全速率、2=半速、3=1/4、4=1/8、5=1/16）。
- 设备默认 `BLACKBOX_DEVICE_NONE`（需用户配 SPI Flash/串口）。
- 串口默认 115200 baud。
- `mode = BLACKBOX_MODE_NORMAL`；`high_resolution = false`。

### 9.2 字段（blackbox.c:200+）

每帧 I-frame（ intra ）+ P-frame（预测帧）。核心字段：
- `loopIteration`（unsigned）、`time`（µs）。
- `axisP/I/D/F`[0..2]（Roll/Pitch/Yaw）。
- `axisS`（固定翼）。
- `rcCommand[0..3]`（roll/pitch/yaw/throttle）。
- `setpoint[0..2]`、`gyroADC[0..2]`、`accSmooth[0..2]`。
- `motor[0..N]`、`pwm[0..N]`。
- `latitude/longitude`、`altitude`、`vbatLatest`、`amperageLatest`、`mAhDrawn`、`rssi`、`airSpeed`、`flightModeFlags`、`stateEstimator`、`failsafePhase` 等。

### 9.3 编码

- I-frame：字段全量，用 `UNSIGNED_VB`（可变长度无符号）或 `SIGNED_VB`。
- P-frame：基于预测器（PREVIOUS / INC / STRAIGHT_LINE / PREDICT_0）只记录增量，编码用 `SIGNED_VB`、`TAG8_4S16`（4 个 16bit 打包）、`TAG2_3S32`（3 个 32bit 打包）等紧凑格式。
- 头部 ASCII：`H Product:Blackbox flight data recorder by Nicholas Sherlock\n` + `H Data version:2\n`，字段定义表（name/signed/I-predictor/I-encoding/P-predictor/P-encoding/condition）。

### Clean-room 重写要点

- MC 模组遥测/圈速记录不需要照搬 Blackbox 编码，可直接用 CSV 或 Protobuf。但**字段集合**可参考：time、rcCommand、setpoint、gyro、P/I/D/F、motor、battery、flightMode。
- 圈速记录参考 `fc/gps_lap_timer.c`（本报告未深读，列为证据缺口）。

---

## 10. Modes 功能表

**源文件**：`src/main/fc/rc_modes.h`（boxId_e）、`src/main/fc/rc_modes.c`、`src/main/fc/runtime_config.h`（flightModeFlags）

### 10.1 boxId_e 全表

| Box | 语义 | 触发条件 |
|---|---|---|
| BOXARM | 解锁电机 | AUX 档位 + 全部 arming 检查通过（陀螺仪校准、RX 链路、锁存等） |
| BOXANGLE | 自稳（角度）模式 | AUX 档位激活，切到 pidLevel 角度控制 |
| BOXHORIZON | 水平模式（自稳+acro 混合） | AUX 档位激活，按杆量过渡 |
| BOXMAG | 罗盘锁定（航向） | AUX 档位 |
| BOXALTHOLD | 定高 | AUX 档位 |
| BOXHEADFREE | 无头模式 | AUX 档位；setpoint 经 `imuQuaternionHeadfreeTransformVectorEarthToBody` 旋转 |
| BOXCHIRP | 频响 chirp 测试 | AUX 档位 |
| BOXPASSTHRU | 透传（固定翼） | AUX 档位 |
| BOXFAILSAFE | 手动触发 failsafe | AUX 档位 |
| BOXPOSHOLD | 位置锁定 | AUX 档位 |
| BOXGPSRESCUE | GPS 救援返航 | AUX 档位 |
| BOXAUTOPILOT | 航点飞行 | AUX 档位 |
| BOXANTIGRAVITY | 手动开关 anti-gravity | AUX 档位（通常由 feature 自动） |
| BOXHEADADJ | 罗盘航向校准 | AUX 档位 |
| BOXCAMSTAB | 相机增稳 | AUX 档位 |
| BOXBEEPERON | 蜂鸣器开 | AUX 档位 |
| BOXLEDLOW | LED 低亮度 | AUX 档位 |
| BOXCALIB | 校准 accel | AUX 档位 |
| BOXOSD | OSD 调整模式 | AUX 档位 |
| BOXTELEMETRY | 遥测开 | AUX 档位 |
| BOXSERVO1/2/3 | 舵机通道 | AUX 档位 |
| BOXBLACKBOX | 黑匣子记录触发 | AUX 档位 |
| BOXAIRMODE | Airmode | AUX 档位（或 FEATURE_AIRMODE） |
| BOX3D | 3D 模式 | AUX 档位 |
| BOXFPVANGLEMIX | FPV 相机角度混合 | AUX 档位（roll/yaw setpoint 旋转） |
| BOXBLACKBOXERASE | 擦除黑卡 | sticky 模式 |
| BOXCAMERA1/2/3 | 相机控制 | AUX 档位 |
| BOXCRASHFLIP | 翻机（坠机后翻面） | AUX 档位 |
| BOXPREARM | 预解锁（安全开关） | AUX 档位 |
| BOXBEEPGPSCOUNT | GPS 蜂鸣 | AUX 档位 |
| BOXVTXPITMODE | VTX PIT 模式 | AUX 档位 |
| BOXPARALYZE | 锁住电机（sticky） | sticky 模式 |
| BOXUSER1-4 | 用户自定义 | AUX 档位 |
| BOXPIDAUDIO | PID 音频调参 | AUX 档位 |
| BOXACROTRAINER | Acro 训练器（角度限位） | AUX 档位 |
| BOXVTXCONTROLDISABLE | 禁用 VTX 控制 | AUX 档位 |
| BOXLAUNCHCONTROL | 发射控制 | AUX 档位 |
| BOXMSPOVERRIDE | MSP 接管 | AUX 档位 |
| BOXSTICKCOMMANDDISABLE | 禁用杆命令 | AUX 档位 |
| BOXBEEPERMUTE | 蜂鸣静音 | AUX 档位 |
| BOXREADY | Ready 模式 | AUX 档位 |
| BOXLAPTIMERRESET | 圈速计时器重置 | AUX 档位 |

### 10.2 Headfree 坐标变换（imu.c:914-928）

- 进入 HEADFREE 时记录当前航向偏航角 `yaw0`，构造偏移四元数 `offset = (cos(yaw0/2), 0, 0, sin(yaw0/2))`。
- 之后每帧把地球系摇杆向量 `(rollCmd, pitchCmd, yawCmd)` 乘 `offset * currentQuaternion` 旋转到机体系。
- 即：摇杆"向前"永远指向解锁时的机头方向，而不是当前机头方向。
- 仅在 acro 模式下对 yaw 也变换；在 Angle/Horizon/AltHold/PosHold/Rescue 模式下 yaw 保持机体系（rc.c:746-762）。

### Clean-room 重写要点

- MC 模组至少需要：ARM、ANGLE（自稳）、HORIZON、AIRMODE、FAILSAFE、BEEPER。HEADFREE 可选。
- 模式激活就是"AUX 值落在 [900+25·start, 900+25·end)"的区间判断。
- Headfree 变换对 MC 竞速有意义（固定参考方向），可用四元数照搬。

---

## 全局证据缺口

1. **固定翼 S-term / WING 专用逻辑**：本报告聚焦多旋翼，`S_TERM_SCALE`、SPA、Wing TPA speed 等未展开。
2. **dyn_notch_filter / FFT**：动态陷波的 FFT 参数（min frequency、Q 值、宽度）未精读 `flight/dyn_notch_filter.c`。
3. **RPM filter**：`flight/rpm_filter.c` 的谐波阶数、Q 值未深读。
4. **GPS Rescue / Autopilot**：超出 10 子系统范围，未读。
5. **OSD 元素具体绘制函数**：osd_elements.c 每个元素的像素/字符对齐细节未逐个列。
6. **Blackbox 完整字段表**：仅列核心字段，完整 list 在 blackbox.c:200-400。
7. **PID 环路频率**：`targetPidLooptime` 默认值（通常 1/8kHz 或 1/4kHz）未在本报告记录，因目标相关。
8. **GPS 圈速计时器**：`fc/gps_lap_timer.c` 未读。
9. **Simplified tuning**：CLI 简化调参如何缩放 P/I/D 默认值未展开。
10. **vbat sag compensation / dyn idle / ez landing**：仅记录默认参数，闭环细节未逐行分析。
