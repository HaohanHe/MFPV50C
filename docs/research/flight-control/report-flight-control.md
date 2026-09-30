# 飞控物理研究报告（flight-control research）

- 抓取日期：2026-09-30
- 范围：仅联网研究与留痕，MIT clean-room。只记录公开公式、参数名、架构与来源；未复制任何 GPL 源码文本。
- 用途：为本 MC 鞘翅"运动/物理"重构（单机 6DoF-lite + 远程 vanilla 服务器兼容模式）提供物理基准、参数命名正名、与本模组设计点的对照。
- 证据分级：**[官方]** = 固件官方文档 / 官方 wiki / 官方源码 README；**[教材]** = 机器人/飞行力学教材；**[社区]** = 爱好者博客/教程；**[推测]** = 由上述材料合理外推但未找到一手明文。查不到精确数字一律写"证据缺口"，不编造。

---

## 1. 谱系正名（用户口中的"樱桃/乱码"逐一排查）

### 1.1 主谱系（[官方][社区]）

```
MultiWii (2010, Alexinparis, 8-bit Atmel)
   └── Baseflight (2013, timecop, Naze32 / STM32 32-bit)
          └── Cleanflight (2014, Dominic Clifton, 重写清理)
                 ├── Betaflight (2015, borisbstyle, 竞速/性能向，事实标准)
                 │      └── Butterflight (2018, 因 BF 3.3 移除 Kalman 滤波而分叉)
                 │             └── EmuFlight (2019 夏, Marinus, 基于 HelioRC 开源的 IMUF 代码)
                 └── INAV (2015, DigitalEntity, GPS / 导航 / 固定翼向)
```

- **Betaflight README 自述谱系**：forked from Cleanflight，致敬 Alexinparis(MultiWii)、timecop(Baseflight)、Dominic Clifton(Cleanflight)、Sambas(STM32F4 port)、borisbstyle。["https://gitee.com/finesun/Beta-Flight-Controller"]
- **EmuFlight 官方 FAQ**：2019 年夏 Marinus 为更新 HelioRC 开源的 IMUF 代码而起，先在 Butterflight（Betaflight 的另一个 fork）上改，再由社区投票命名 EmuFlight。["https://emuflight.github.io/Faq%20and%20Issues.html","https://emuflight.github.io/getting-started/What-makes-EmuFlight-different.html"]
- **INAV 官方 welcome 页**：明确叙述 timecop → Naze32/Baseflight → 社区 drama → Cleanflight → 再分叉出 INAV。["https://inavflight.github.io/docs/welcome/"]
- Butterflight 分叉原因（[社区]）：Betaflight 3.3 出于性能原因移除了 Kalman 滤波，papayou66 / rs2k 另起 Butterflight 保留 FAST_KALMAN。["https://www.wearefpv.fr/butterflight-20180304/"]

### 1.2 对"樱桃/乱码"的正名结论

| 用户口中的称呼 | 真实对象 | 判定 |
|---|---|---|
| Betaflight | Betaflight（borisbstyle 2015 从 Cleanflight fork） | 正确，无需正名 |
| INAV | INAV（DigitalEntity 2015 从 Cleanflight fork，GPS/固定翼向） | 正确 |
| "樱桃/乱码"开源 | **EmuFlight**（2019 夏 Marinus，从 Butterflight fork，名字由社区投票产生，标志是一只蓝色大鸟；特色是 αβγ (ABG) 预测滤波 / 低延迟 gyro 滤波） | **正名：EmuFlight，不是独立于 BF 谱系之外的另一支，而是 Cleanflight→Betaflight→Butterflight→EmuFlight 这一支的末端** |
| Cleanflight | Cleanflight（Dominic Clifton 2014 从 Baseflight fork） | 是 BF/INAV/EmuFlight 的共同上游，不是"乱码" |
| ArduPilot / PX4 | 独立于上述 FPV 谱系的工业级自驾仪（见 §5） | 与 BF 谱系无直接分叉关系 |

证据缺口：EmuFlight 名字的具体词源（鸸鹋？还是别的）官方 FAQ 只说"由用户投票选出"，未给词义；本报告不编造。

---

## 2. 四旋翼升力方向与推力模型（本模组 P-A 的物理基准）

### 2.1 升力方向：桨朝上，推力沿机体 +Z（up）

- 四旋翼四个桨盘朝上旋转，推力方向沿**机体坐标系 +Z（body-up）**，即机体竖直向上的方向；重力沿世界系 -Z。这是本模组 P-A 任务 1"升力方向改 bodyUp = attitude·(0,1,0)"的飞控界共识依据。[教材][推测：所有现代多旋翼教材的标准约定]
- 机体姿态由 roll φ、pitch θ、yaw ψ 决定。当机体水平（φ=θ=0）时，body-up = world-up，推力纯竖直。

### 2.2 倾斜时的推力分解（核心公式）

引自 Introduction to Robotics and Perception (roboticsbook.org) §7.2 Multi-rotor Aircraft：机体前倾 θ、需维持竖直支撑力 = 重量 W（例中 W=10）时：

```
垂直平衡：  F_body · cos θ = W                 …(1)
水平前推：  F_horizontal = F_body · sin θ = W · tan θ   …(2)
```

即：
- **水平加速度 ≈ g · tan θ**（单位质量推力 = W·tan θ / m = g·tan θ）；
- **为保持高度不变，必须同时加大总推力 F_body = W / cos θ**；若推力大小不变（悬停油门 T=mg），垂直分量会从 mg 降到 mg·cos θ，**净垂直加速度 = g(cos θ − 1) < 0，机体掉高**。

Bitcraze 博客给出同一组关系的另一种写法：F_y = F_thrust·sin φ，F_z = F_thrust·cos φ（φ 为机体 z 轴与世界 z 轴夹角）。["https://www.roboticsbook.org/S72_drone_actions.html","https://www.bitcraze.io/author/prasad/"]

St. Etienne 大学简化多旋翼模型讲义也给出沿 z 的平动方程：
`m·v̇_z = (F1+F2)·cos θ − b·v_z − m·g`，即阻力项 b·v_z（本模组 P-A 任务 1 的 "k·v² 阻力" 对应位置）。["https://www.dmi.unict.it/santoro/teaching/sr-2024/slides/MultiRotorModel.pdf"]

### 2.3 对本模组的直接结论

| 飞控事实 | 本模组应如何对应 |
|---|---|
| 推力沿 body-up，不沿机头 forward | 现模组"推力沿 fwd=attitude·(0,0,-1)"是**物理错误**；P-A-1 改为 bodyUp=attitude·(0,1,0) |
| pitch 下俯 θ → 前向水平加速度 g·tan θ，垂直分量减为 T·cos θ → 轻微掉高 | P-A-3 预期行为：pitch 前进 + 轻微掉高 ✓ |
| roll 侧倾 φ → 侧向水平加速度 g·tan φ，垂直分量 T·cos φ → 掉高；roll 对运动**有**作用，不是纯视觉 | 现模组"roll 对运动零作用"即源于推力方向错；P-A-3 修后 roll 应侧移 + 掉高 ✓ |
| 合力 = thrust·bodyUp + gravity + 空气阻力 | P-A-1 公式形式与教材一致 |

---

## 3. 悬停油门（hover throttle）概念

### 3.1 定义（所有固件一致）

悬停油门 t_h 是"整机以恒定高度、水平姿态悬停时所需的油门量"。物理条件：

```
T_total(t_h) = m · g     （总推力 = 整机重量）     …(3)
```

即中位油门对应推力恰好抵消重力。

### 3.2 各固件参数命名（[官方]）

| 固件 | 参数名 | 默认值 / 行为 | 来源 |
|---|---|---|---|
| Betaflight | `hover_throttle`（AltHold/PosHold 初始化用）；老版本用 `throttle_mid` + `throttle_expo` 把悬停点映射到中位 | AltHold 启动时先设为 hover_throttle，PID 再在其两侧微调；设太低会立刻掉高、太高会立刻爬升 | ["https://betaflight-com.pages.dev/docs/wiki/guides/current/Position-Hold-2025-12","https://blog.unmanned.tech/betaflight-throttle-mid-expo-hover-point-guide/"] |
| ArduPilot Copter | `MOT_THST_HOVER`（悬停推力占比），`MOT_HOVER_LEARN` / `ATC_HOVER_LEARN` 可自动学习 | 官方建议初值 0.25（或低于预期悬停点），由飞行自动学习收敛 | ["https://ardupilot.org/copter/docs/initial-tuning-flight.html","https://ardupilot.org/copter/docs/basic-tuning.html"] |
| ArduPilot Plane( VTOL ) | `Q_M_THST_HOVER` + `Q_M_HOVER_LEARN` | 目标：任何模式下中位油门即悬停 | ["https://ardupilot.org/plane/docs/qacro-mode.html"] |
| PX4 | `MPC_THR_HOVER`，默认 50%；`MPC_THR_CURVE` 默认 "Rescale to hover thrust" | 中位杆输出 MPC_THR_HOVER，上下线性重映射；赛车/无载重航拍机可降到 ~35% | ["https://docs.px4.io/main/en/flight_modes_mc/manual_stabilized","https://docs.px4.io/v1.13/en/advanced_config/land_detector"] |
| INAV 多旋翼 | `nav_mc_hover_thr`（CLI / Configurator） | 手动悬停后读 OSD 油门值填入；若 >1700(px) 说明动力不足 | ["https://inavflight.github.io/docs/5.0.0/quickstart/Multirotor-guide/","https://inavflight.github.io/docs/5.1.0/quickstart/TROUBLESHOOTING/"] |

### 3.3 对本模组 P-A-2 的对应

- 新增 `hoverThrottle`（airframe 数据），默认由 `mg / 总推力` 反算：`hoverThrottle = m·g / T_max`。
- 中位油门 = hoverThrottle 时，水平姿态下合力 ≈ 0（悬停）。这正是 BF `throttle_mid` / PX4 `MPC_THR_CURVE=Rescale` / ArduPilot "mid-stick hover" 的共同设计。
- 可选：像 ArduPilot `MOT_HOVER_LEARN` 一样做在线学习（[推测]非必需，首版可固定反算值）。

---

## 4. 协调转弯（coordinated turn）——重点：多旋翼 ≠ 固定翼

### 4.1 固定翼口径（[教材][官方]）

- 固定翼转弯靠**副翼压坡度 bank angle φ**，升力向量倾斜后其水平分量提供向心力；方向舵（rudder）用于消除 adverse yaw / 侧滑，使纵向轴与相对风对齐（侧滑角 = 0，balance ball 居中）。["https://groundscholar.com/handbook/afh/3-turns","https://www.wificfi.com/post/slips-skids-and-adverse-yaw"]
- 标准物理关系（水平协调转弯，无侧滑，[教材]）：
  - 向心力：`m·V·ω = L·sin φ`
  - 竖直平衡：`L·cos φ = m·g`
  - 两式相除：**`tan φ = V · ω / g`**，即 `ω = g·tan φ / V`；载荷因子 `n = L/(mg) = 1/cos φ`。
  - 例：60° 坡度 → n = 1/cos60° = 2G（模拟飞行题库标准答案）。["https://www.wjx.cn/xz/379274337.aspx"]
- 标准转弯率：3°/s（2 分钟转一圈），仪表飞行默认。["https://aerocorner.com/blog/turn-coordinator-cockpit/"]
- INAV 固定翼在 RTH/LOITER/CRUISE/WP 模式下自动开启 **TURN ASSIST**，自动打升降舵+方向舵实现协调转弯。["https://inavflight.github.io/docs/core-features/flight-modes-navigation/"]

### 4.2 多旋翼口径（[官方]）

- **多旋翼没有方向舵概念**。PX4 官方 Flying 101 明确：Hover Aircraft（多旋翼/VTOL 悬停模式）下 Pitch=前后、Roll=左右、Yaw=绕机身轴旋转、Throttle=高度。["https://docs.px4.io/v1.13/en/flying/basic_flying"]
- 多旋翼转弯 = 先 roll 倾侧产生侧向力（§2.2），机头指向通过 yaw 控制单独转向；"协调"在多旋翼上意味着**roll 倾侧速率与 yaw 指向协调**，而非副翼-方向舵联动。
- 结论：**多旋翼一般不存在固定翼意义上的 coordinated turn（副翼-方向舵联动）**；BF/INAV 多旋翼靠姿态（roll/pitch）+ yaw 分别控制。[推测：BF 多旋翼固件无 rudder 输出]

### 4.3 对本模组 P-B-2（远程兼容模式"协调转弯"）的对应

本模组在 vanilla 服务端不能塞额外速度，只能把 attitude 反算成 vanilla look 写回。所谓"协调转弯"在这里是一个**映射**，不是固定翼空气动力学：

- roll 量 → 等效 yaw 变化率（让机头绕垂直轴转），可选叠加 pitch 补偿（模拟 §2.2 中 roll 倾侧导致垂直分量下降 → 轻微下俯 pitch 补高度）。
- 默认开、增益可配、可关——与 INAV TURN ASSIST 的"自动开/可配"思路一致，但物理本质是"把 roll 烘焙进 vanilla look"，**不是**真的固定翼协调转弯。报告中必须向用户标注这一区别，避免误导。

---

## 5. ArduPilot / PX4（工业级自驾仪）多旋翼控制要点

- **姿态→推力向量分解**：现代 SE(3)/几何控制器直接把期望世界系加速度 `a_des` 作为指令，反解总推力 `T = m·‖a_des + g·ẑ‖` 和机体 z 轴 `z_b = (a_des + g·ẑ)/‖·‖`，再分配 roll/pitch。["https://kindatechnical.com/autonomous-systems/quadrotor-dynamics-and-flight-control.html","https://arxiv.org/html/2604.13505"]
- **PX4 手动/Stabilized**：roll/pitch 杆控制**姿态角**（不是角速度），松杆自动水平并悬停；yaw 杆控制偏航速率。["https://docs.px4.io/v1.14/en/flight_modes_mc/manual_stabilized"]
- **PX4 推力曲线补偿**：`THR_MDL_FAC` / thrust curve 0.3（PWM ESC）补偿油门→推力的非线性（推力大致 ∝ 油门²）。[官方]["https://docs.px4.io/v1.15/en/config_mc/pid_tuning_guide_multicopter_basic"]
- **ArduPilot Alt Hold**：中位油门死区 40%~60% 内维持高度，之外按杆位爬升/下降。["https://ardupilot.org/copter/docs/altholdmode.html"]

---

## 6. Betaflight / EmuFlight 其他被点名主题的核对

### 6.1 Mixer：QuadX 电机编号与转向（[社区]）

Betaflight 默认 "Props Out"（机桨朝前外）约定（前方朝上看）：

```
Front
   4(CL)      2(CR)
        \  /
         \/
         /\
        /  \
   3(RL)      1(RR)
Rear
```

- M1 = 后右，CW；M2 = 前右，CCW；M3 = 后左，CCW；M4 = 前左，CW。["https://fpv-bible.lacy.sh/building/motors/","https://www.madflight.com/Getting-Started/"]
- 注意存在 "Props In / Butterflight style" 镜像约定（M1 后右变 CCW 等），不同教程数字会相反，以 Betaflight Configurator Motors 页图形为准。["https://itohi.com/snippets/fpv/setup-safety/preflight-checklist/"]
- 对本模组：纯客户端无电机实体，不需要实现 mixer；但 P-A-4 要求"roll 与 pitch 默认 actual rates 一致"，依据见 §6.2。

### 6.2 Rates 默认值（用户给的 center70/max670/expo0 已核实）

- **Betaflight ≥ 4.3 全新配置默认 Actual Rates：Centre Sensitivity = 70°/s、Max Rate = 670°/s、Expo = 0.00，roll/pitch/yaw 三轴一致。** 老 Betaflight Rates 风格默认 RC Rate 1.0、Super Rate 0.70、Expo 0，满杆也约 670°/s。["https://www.unmannedtechshop.co.uk/blogs/knowledge-base/betaflight-rates-explained-how-to-configure-rc-stick-response"]
- 即用户给的"center70/max670/expo0"完全正确，且 roll 与 pitch 默认**必须相同**；本模组 P-A-4 不应让 roll 默认灵敏度与 pitch 不一致。
- 满杆速率公式（[社区]）：`maxRate = (RC Rate × 200) / (1 − Super Rate)`。["https://itohi.com/snippets/fpv/tuning/rate-presets/","https://itohi.com/snippets/fpv/tuning/rates/"]

### 6.3 Airmode（[社区]）

- `airmode_start_throttle_percent` 默认 25：解锁后油门超过该百分比即开启 airmode——**怠速/低油门时仍保持 roll/pitch/yaw 姿态控制输出**，避免松油门时失控翻机。["https://technobee.ru/index.php/item/komandnaya-stroka-cli-betaflight.html"]
- 对本模组 P-A-4：对应"airmode 怠速仍做姿态控制"——低油门时 roll/pitch 不应失效。

### 6.4 Throttle Boost / Throttle Curve / 3D / OSD / Battery（[社区]）

- **Throttle Boost**：dump 示例默认 `Throttle Boost = 5`（ms）、`Throttle Boost Cutoff = 15`；在快速打杆时瞬时提升油门以补角速度。["https://manuals.plus/m/8536fafff391267d6470bea6a7653d62eaf2d0a64e243b03d0dd39dc7db5aacd"]
- **Throttle Mid/Expo**：把悬停点映射到中位、并在中位附近压平曲线（详见 §3.2）。
- **3D 可逆**：电机双向旋转，中位(50%)=零推力，两端为正负最大推力；`3d_deadband_throttle` 默认 50。["https://technobee.ru/index.php/%D0%B8%D0%BD%D1%81%D1%82%D1%80%D1%83%D0%BA%D1%86%D0%B8%D0%B8-guides/item/3d-%D1%80%D0%B5%D0%B6%D0%B8%D0%BC-betaflight.html","https://technobee.ru/index.php/item/komandnaya-stroka-cli-betaflight.html"] 本模组为花飞/比赛关，不需要 3D。
- **OSD**：avg cell voltage、pitch/roll angle、电量警告位等；`osd_avg_cell_voltage_pos`、`osd_pit_ang_pos` 等。["https://support.betafpv.com/hc/en-us/article_attachments/59863253804825"]
- **电池电压默认**：`vbat_max_cell_voltage=430`、`vbat_full_cell_voltage=410`、`vbat_min_cell_voltage=330`、`vbat_warning_cell_voltage=350`（单位 mV/10，即 4.30/4.10/3.30/3.50 V）。["https://blog.uavmodel.com/fpv-drone-throttle-mid-and-expo-setup-throttle-curve-tuning-for-smooth-hover-and-precise-control/"] 对本模组 P-A-4 "电池 sag"：可按 4.10V 满电/3.50V 警告/3.30V 截止作为 sag 衰减基准。

### 6.5 EmuFlight 特色滤波（[官方]）

- ABG (αβγ) 预测滤波：用速度/加速度/jerk 预测下一步 gyro 输出，低延迟；参数 `ABG_Alpha`（滤波强度）、`ABG_Boost`（对快速变化的反应速度）。["https://emuflight.github.io/features/Alpha-Beta-Gamma-filter.html"]
- 对本模组：纯客户端运动模拟不需要 IMU 滤波；本项仅作正名，不引入。

---

## 7. 与本模组重构任务的逐条对照

| 任务条目 | 飞控界依据 | 本模组做法 |
|---|---|---|
| P-A-1 升力改 bodyUp | §2.1/§2.2 推力沿机体 +Z，倾斜时分解为水平 g·tanθ + 竖直 T·cosθ | 合力 = thrust·bodyUp + (0,0,-mg) + k·v² 阻力 |
| P-A-2 hoverThrottle 由 mg/T_max 反算 | §3 公式(3) T(t_h)=mg；BF/PX4/ArduPilot/INAV 全有同概念参数 | airframe 数据，默认反算；中位即悬停 |
| P-A-3 pitch 前进+掉高、roll 侧移+掉高 | §2.2 垂直分量 T·cosθ 减小 | 由 bodyUp 方向自动产生，无需额外逻辑 |
| P-A-4 roll/pitch 默认 rates 一致 | §6.2 BF 4.3+ 默认 70/670/0 三轴一致 | roll 默认 rate = pitch 默认 rate |
| P-A-4 airmode 怠速仍控制 | §6.3 airmode_start_throttle_percent=25 | 低油门时姿态控制不切断 |
| P-A-4 throttle boost / 曲线 / battery sag | §6.4 | 沿用已有电机滞后/boost/曲线/sag，但作用方向改在 bodyUp 上 |
| P-B-1 远程把 attitude 反算 yaw/pitch 写回 look | DABR 标杆（用户提供）+ §4.2 多旋翼 yaw/roll/pitch 解耦 | 不塞 vanilla 外速度，仅改 look |
| P-B-2 roll→等效 yaw"协调转弯" | §4.3 这是 vanilla 兼容层的映射，不是固定翼空气动力学；INAV TURN ASSIST 可配思路 | 增益可配、默认开、可关；GUI 标注"非真协调转弯" |
| P-B-3 烟花火箭提供推力 | vanilla 鞘翅无动力；沿写回后的 look 方向 | throttle 超阈值自动周期 use-item |
| P-B-4 anti-kick / 烟花频率限制 | 反作弊常识（[推测]，未找官方 BF 反作弊文档，证据缺口） | 监听 ClientboundPlayerPosition/PosLook，回退+降频+黑匣子 |

---

## 8. 证据缺口与推测清单

1. [推测] 本模组"k·v² 阻力"的具体系数 k 取值：未查 FPV 真实 drag 系数，由用户在 airframe 数据里调。
2. [证据缺口] Minecraft 反作弊对烟花火箭频率的"max-firework-delay"具体阈值：未找 Mojang/ Paper 官方反作弊文档，仅参考通用反作弊社区经验。
3. [推测] EmuFlight 名字词源：官方 FAQ 只说用户投票选出，未给词义。
4. [证据缺口] BF `throttle_boost` 的精确单位与作用公式（默认 5ms）：dump 里看到数值，但未读 BF 源码；本模组沿用已有 boost 逻辑即可，不重新实现。
5. [推测] 多旋翼"协调转弯"在 BF/INAV 多旋翼固件中不存在独立 rudder 输出：由 PX4 官方"多旋翼无方向舵"表述外推。

---

## 9. 一句话结论

现模组把推力沿机头 forward 是物理错误；正确做法是推力沿 body-up（机体 +Z），倾斜时水平加速度自然 = g·tan(倾角)、垂直分量 = T·cos(倾角) 自动产生掉高，悬停油门由 mg/T_max 反算并放在中位，roll 因此不再纯视觉。远程 vanilla 服务端只能靠反算 look + 烟花火箭 + 可选 roll→yaw 映射来"伪造"轨迹，必须在 GUI 明确标注这是兼容层近似而非真 6DoF。
