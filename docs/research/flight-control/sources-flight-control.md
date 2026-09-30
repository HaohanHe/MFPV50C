# 来源清单 — flight-control research

- 抓取日期：2026-09-30
- 证据分级：**[官方]** 固件官方文档/wiki/源码 README；**[教材]** 机器人/飞行力学教材；**[社区]** 爱好者博客/教程；**[推测]** 由材料外推、未找到一手明文。
- 说明：MIT clean-room，仅记录公开公式、参数名与架构，未复制任何 GPL 源码文本。下列 URL 均为研究时实际访问的页面。

## 谱系正名（Cleanflight / Betaflight / INAV / Butterflight / EmuFlight）
- [官方/镜像] Betaflight README 自述谱系（forked from Cleanflight）：https://gitee.com/finesun/Beta-Flight-Controller
- [官方] EmuFlight FAQ（2019 夏 Marinus，社区投票命名）：https://emuflight.github.io/Faq%20and%20Issues.html
- [官方] EmuFlight 特色（ABG 预测滤波）：https://emuflight.github.io/getting-started/What-makes-EmuFlight-different.html
- [官方] INAV welcome（timecop→Baseflight→Cleanflight→INAV）：https://inavflight.github.io/docs/welcome/
- [社区] Butterflight 分叉（BF 3.3 移除 Kalman）：https://www.wearefpv.fr/butterflight-20180304/

## 升力方向与倾斜推力分解
- [教材] Roboticsbook（Introduction to Robotics and Perception）§7.2 多旋翼：F·cosθ=W、F·sinθ=W·tanθ：https://www.roboticsbook.org/S72_drone_actions.html
- [官方] Bitcraze 博客（F_y=F·sinφ、F_z=F·cosφ）：https://www.bitcraze.io/author/prasad/
- [教材] St. Etienne 大学多旋翼模型讲义（m·vż=F·cosθ−b·vz−m·g）：https://www.dmi.unict.it/santoro/teaching/sr-2024/slides/MultiRotorModel.pdf

## 悬停油门（hover throttle）
- [官方] Betaflight Position Hold（hover_throttle）：https://betaflight-com.pages.dev/docs/wiki/guides/current/Position-Hold-2025-12
- [社区] Betaflight throttle mid/expo/hover point：https://blog.unmanned.tech/betaflight-throttle-mid-expo-hover-point-guide/
- [官方] ArduPilot 初始/基础调校（MOT_THST_HOVER）：https://ardupilot.org/copter/docs/initial-tuning-flight.html ；https://ardupilot.org/copter/docs/basic-tuning.html
- [官方] ArduPilot Plane VTOL（Q_M_THST_HOVER）：https://ardupilot.org/plane/docs/qacro-mode.html
- [官方] PX4 Stabilized（MPC_THR_HOVER / MPC_THR_CURVE）：https://docs.px4.io/main/en/flight_modes_mc/manual_stabilized ；https://docs.px4.io/v1.13/en/advanced_config/land_detector
- [官方] INAV 多旋翼指南（nav_mc_hover_thr）：https://inavflight.github.io/docs/5.0.0/quickstart/Multirotor-guide/ ；https://inavflight.github.io/docs/5.1.0/quickstart/TROUBLESHOOTING/

## 协调转弯（coordinated turn）
- [教材] 固定翼转弯（tanφ=Vω/g、载荷因子）：https://groundscholar.com/handbook/afh/3-turns
- [社区] Slips/skids/adverse yaw：https://www.wificfi.com/post/slips-skids-and-adverse-yaw
- [社区] 60° 坡度=2G（题库）：https://www.wjx.cn/xz/379274337.aspx
- [官方] 标准转弯率 3°/s：https://aerocorner.com/blog/turn-coordinator-cockpit/
- [官方] INAV TURN ASSIST：https://inavflight.github.io/docs/core-features/flight-modes-navigation/
- [官方] PX4 Flying 101（多旋翼 Pitch/Roll/Yaw/Throttle，无方向舵）：https://docs.px4.io/v1.13/en/flying/basic_flying

## ArduPilot / PX4 多旋翼控制
- [社区] 四旋翼动力学与几何控制：https://kindatechnical.com/autonomous-systems/quadrotor-dynamics-and-flight-control.html
- [学术] 几何/SE(3) 控制器：https://arxiv.org/html/2604.13505
- [官方] PX4 Stabilized（杆控姿态角）：https://docs.px4.io/v1.14/en/flight_modes_mc/manual_stabilized
- [官方] PX4 推力曲线补偿（THR_MDL_FAC）：https://docs.px4.io/v1.15/en/config_mc/pid_tuning_guide_multicopter_basic
- [官方] ArduPilot Alt Hold（中位死区 40–60%）：https://ardupilot.org/copter/docs/altholdmode.html

## Betaflight 其他主题（Mixer / Rates / Airmode / Boost / 3D / OSD / 电池）
- [社区] QuadX 电机编号（Props Out）：https://fpv-bible.lacy.sh/building/motors/ ；https://www.madflight.com/Getting-Started/
- [社区] Props-In 镜像约定：https://itohi.com/snippets/fpv/setup-safety/preflight-checklist/
- [社区] Betaflight Actual Rates 默认 70/670/0：https://www.unmannedtechshop.co.uk/blogs/knowledge-base/betaflight-rates-explained-how-to-configure-rc-stick-response
- [社区] 满杆速率公式 / rate presets：https://itohi.com/snippets/fpv/tuning/rate-presets/ ；https://itohi.com/snippets/fpv/tuning/rates/
- [社区] Airmode（airmode_start_throttle_percent=25）：https://technobee.ru/index.php/item/komandnaya-stroka-cli-betaflight.html
- [社区] Throttle Boost（dump 5/15）：https://manuals.plus/m/8536fafff391267d64720bea6a7653d62eaf2d0a64e243b03d0dd39dc7db5aacd
- [社区] 3D 可逆（3d_deadband_throttle=50）：https://technobee.ru/index.php/%D0%B8%D0%BD%D1%81%D1%82%D1%80%D1%83%D0%BA%D1%86%D0%B8-guides/item/3d-%D1%80%D0%B5%D0%B6%D0%B8%D0%BC-betaflight.html
- [官方] Betaflight OSD 元素：https://support.betafpv.com/hc/en-us/article_attachments/59863253804825
- [社区] 电池电压 4.30/4.10/3.50/3.30：https://blog.uavmodel.com/fpv-drone-throttle-mid-and-expo-setup-throttle-curve-tuning-for-smooth-hover-and-precise-control/
- [官方] EmuFlight ABG 滤波：https://emuflight.github.io/features/Alpha-Beta-Gamma-filter.html

## 证据缺口（未找到一手明文）
1. “k·v² 阻力”具体系数 k：未查 FPV 真实 drag 系数，留 airframe 可调。
2. Minecraft/Paper 反作弊对烟花频率的 max-firework-delay 精确阈值：无官方文档。
3. EmuFlight 名称词源：官方 FAQ 仅称社区投票。
4. Throttle Boost 精确单位/公式（dump 显示 5/15，未读源码）。
5. BF/INAV 多旋翼是否存在独立 rudder 输出：由 PX4“多旋翼无方向舵”表述外推（[推测]）。
