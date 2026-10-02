# FPV Craft（`fpv-mod`）

把 Minecraft 鞘翅飞行变成第一视角（FPV）穿越机飞行的**纯客户端** Fabric 模组。

你本人就是一架穿越机：不新增实体、不装在服务端，其他玩家看到的还是普通的你，
而你自己的视角是一套完整滚转 / 俯仰 / 偏航的 FPV 相机，操控手感按 Betaflight
公开的 rates / PID 模型以 clean-room 方式复现。

> 版本 **0.1.0**（开发中）：飞行数学、状态机与计时逻辑均有 headless 无头测试
> 钉住；GUI 视听效果（着色器叠加、摇杆手感、游戏内帮助页）仍需真机验收。
> 详见下文 [Status / 现状边界](#status--现状边界)。

---

## Features

- **全姿态 FPV 相机**：roll / pitch / yaw 三轴姿态完全在客户端施加，穿鞘翅展开
  即进入 FPV 视角，不需要烟花推进（单机本地物理下）。
- **Betaflight 兼容 rates（clean-room）**：Actual / Legacy / Quick 三种速率曲线，
  默认 Actual 70 / 670 / 0（center / max / expo）；支持粘贴 Betaflight
  configurator `diff` / `dump` 的 CLI 文本一键导入。
- **真实刚体飞控**：摇杆 → 角度/角速度 PID（P/I/D/F、I-term relax、anti-gravity、
  TPA）→ Quad-X 混控 → 电机一阶滞后 → 刚体欧拉方程积分；可在 REAL / ARCADE
  两档物理间切换。5 寸 4S 工程起点参数（单电机 12 N）。
- **三种飞行模式 + AUX 功能**：ACRO / ANGLE / HORIZON；AUX 通道可数据化映射到
  ARM / PREARM / ANGLE / HORIZON / ACRO / HEADFREE / HEADADJ / TURTLE / BEEPER /
  FAILSAFE，通道与功能永不硬绑。
- **遥控器支持（GLFW 手柄接口）**：逐通道反向 / 死区 / 行程校准，图形化
  「动杆绑定」向导（居中→逐轴打满→开关逐档），支持 Mode 1/2/3/4（默认 Mode 2），
  内置 EdgeTX F16 等已知设备档案，按设备指纹持久化；无遥控器时自动回退
  键盘/鼠标。
- **首次启动向导**：检测遥控器 → 选手型 → 图形化校准 → 解锁教学 → 首飞提示，
  每步可上一步 / 跳过，完成或跳过后持久化不再自动弹出，配置里可重新打开。
- **OSD 与编辑器**：数据驱动的 OSD 元素注册表（姿态地平仪、vario、航向梯、
  速度 / 高度侧条、电池电压 / 单片 / %、电流、mAh、功率 W、逐电机条、LQ、
  计时器、归航箭头），拖拽式编辑器逐元素开关与移位，公制 / 英制切换。
- **竞速模块**：F9U 风格规则（计时门起、顺序圈数、逆行 / 漏门判罚、
  最快 3 圈均分），门形支持矩形 / 圆环 / 加速门，赛道由用户自建（不随包分发
  现成赛道），幽灵轨迹录制 / 回放。竞速默认关闭。
- **黑匣子与回放**：`R` 开始 / 停止录制 `.fpr`（姿态 + IMU + 四路混控输出 +
  事件块，有界环形缓冲，NaN 防御，原子写盘）；`U` 打开回放界面，支持拖拽
  进度、变速、多视角；可导出 ffmpeg 视频（可自定义命令模板，未装 ffmpeg 时
  回退 PNG 序列）或把相机轨迹导出 JSON 给 Blender / AE。关键帧电影相机轨道
  （Catmull-Rom / Cubic Hermite / Linear + slerp）。
- **沉浸层**：桶形畸变 / 暗角 / 色差后处理、按信号质量（LQ）分级的故障链
  （mild / heavy / frozen 着色器链，带回滞防抖）、电机啸叫音调与 ARM / 低电 /
  失控提示音。
- **原版服务器兼容层**：在 Paper / Velocity 等原版服务器上仍能以「鞘翅 + 烟花」
  近似连续推力与协调转弯（默认开启）；单机 / 创造模式不受限。
- **安全网**：配置原子写 + 轮转备份（10 份）+ 损坏隔离（quarantine），运行时
  每帧 NaN / Inf 守卫，BF 风格解锁拦截码（NO_RX / NOT_CALIBRATED /
  BATTERY_CRITICAL / RX_FAILSAFE）。

## Screenshots

> 截图待真机验收后补入。占位目录：

- `docs/images/onboarding.png` — 首次启动向导（检测遥控器 → 手型 → 图形校准）
- `docs/images/calibration.png` — 逐轴行程校准（实时高亮当前轴）
- `docs/images/osd.png` — FPV 飞行视角与 OSD
- `docs/images/settings.png` — 配置界面（飞控 / 机架 / OSD 编辑器 / 竞速）
- `docs/images/replay.png` — 回放与电影相机轨道

## Requirements / 依赖

| 组件 | 版本（见 `gradle.properties` / `fabric.mod.json`） |
| --- | --- |
| Minecraft | **1.21.11** |
| Fabric Loader | ≥ 0.19（构建用 0.19.5） |
| Fabric API | 0.141.6+1.21.11 |
| Fabric Language Kotlin | 1.14.1+kotlin.2.4.20（Kotlin 2.4.20） |
| Java | **21** |

## Installation / 安装

1. 安装 Fabric Loader 1.21.11。
2. 把 **Fabric API**、**Fabric Language Kotlin** 和本模组的 jar 一起放进
   `.minecraft/mods/`。
3. 启动游戏：首次进入会弹出引导向导；无遥控器时按提示使用键盘 / 鼠标备用
  （见下）。

配置文件：`<gameDir>/config/fpvcraft.json`；备份在
`<gameDir>/config/fpv/backups/`。

## Quick Start / 首次校准与键位

**默认键位**（均可在 选项 → 控制 中改绑；遥控器插好后由向导接管）：

| 按键 | 作用 |
| --- | --- |
| `V` | 进入 / 退出 FPV 模式总开关 |
| `B` | 解锁 / 上锁（arm/disarm）。仅当遥控器未绑定 arm 通道时可用；解锁前油门须最低 |
| `R` | 黑匣子录制 开始 / 停止 |
| `U` | 打开回放 / 导出界面 |
| （默认未绑定） | 打开 FPV 配置界面（在控制菜单里自行绑定） |

**无遥控器时的键盘 / 鼠标备用**（`KeyboardMouseProvider`）：
鼠标移动 → roll / pitch；`←` / `→` → yaw；`↑` / `↓` → 油门（3D 模式下
`↑` 正推、`↓` 反推）。

**上手流程**：

1. 打开 FPV 配置界面，在向导中选择设备，按提示「松手居中 → 逐轴推满行程 →
   拨动每个开关档位」。
2. Mode 2（默认）：右杆 = roll / pitch，左杆 = throttle / yaw。
3. 穿鞘翅升空、在空中展开鞘翅解锁飞行；`V` 切换 FPV 视角，`B` 解锁。
4. 需要自稳就用 AUX 拨杆切 ANGLE / HORIZON；纯竞速默认 ACRO。

## Configuration / 配置与调参

全部数据化、运行时可改，无硬编码：

- **Rates**：Actual / Legacy / Quick 切换；逐轴 rc_rate / super_rate / expo；
  可直接粘贴 Betaflight CLI `dump` 导入。
- **PID**：逐轴 Roll / Pitch / Yaw 的 P / I / D / F、TPA、D-term 低通；
  默认值对齐 Betaflight 公开出厂值。
- **机架档案**：电机推力 / 反扭矩 / 电机时间常数 / 臂长 / 惯量 / 各向异性
  阻力 / 地面效应 / 洗桨扰动，多档案可存。
- **OSD 编辑器**：逐元素显隐、位置、单位覆盖。
- **竞速**：门形 / 赛道 JSON（`TrackStore` 原子写）、幽灵轨迹。
- **服务器兼容层**：烟花推力阈值 / 间隔、协调转弯增益、软限速（默认关）。

## Build from Source / 从源码构建

环境：JDK 21、Gradle 9.x（本仓库未提交 gradle wrapper，用本机 Gradle）。

```bash
gradle build
```

remap 后的产物在 `build/libs/fpv-mod-<version>.jar`。

无头验证（不启动游戏，纯 JVM 跑真实飞控管线的回归测试）：

```bash
# Windows
headless/run_headless.ps1
# Linux / macOS
headless/run_headless.sh
```

## Status / 现状边界

如实标注，避免夸大：

- 单机 / 创造下是完整四旋翼模型（可悬停、原地偏航、垂直爬升）。
- 原版远程服务器上受鞘翅 + 烟花物理限制：**无法**真零速悬停 / 原地偏航 /
  垂直爬升，是近似手感。
- 以下仍需真机验收（代码内已标注 `需真机`）：后处理链的逐帧 LQ 动画、
  游戏内帮助页、各类 GUI 视听效果、真实遥控器杆噪 / 中位漂移、Paper /
  Velocity 上的烟花手感。
- 赛道目录不随包分发；「≥4 道门」「障碍等待 10 s」等 F9U 细节未经规则方确认。
- 推导常数（拉力系数、惯量分布、boost）是工程起点值，待实测台架数据整定。

## Acknowledgments / 数据来源与致谢

**MIT 许可来源（实现可借鉴）：**

- [gym-pybullet-drones](https://github.com/utiasDSL/gym-pybullet-drones) /
  drone-models — 刚体四旋翼方程结构（本项目系数自行标定）。
- [FPVEffect](https://github.com/)（MIT）— 后处理着色器的分级思路。
- [ElytraRacing](https://github.com/)（MIT）— 加速门（boost gate）的
  客户端速度包络思路。
- Flightmare 公开物理测量数据 — 5 寸级机架质量 / 静态拉力的工程参考。

**GPL 许可来源（仅 clean-room 借鉴思路 / 公开公式 / 公开文本格式，未抄录任何
源码）：**

- [Betaflight](https://github.com/betaflight/betaflight) — rates / PID 结构与
  出厂参数、CLI `set name = value` 文本格式。数学本身不受版权保护。
- INAV — 固定翼 / 导航术语参考。
- [ReplayMod](https://github.com/ReplayMod/ReplayMod) — 录像回放 / 相机路径
  概念参考；本项目 `.fpr` 格式与之不兼容、不重注入 S2C 数据包。

第三方名称仅用于兼容性说明，与上述项目无隶属 / 背书关系。

## License / 许可

[MIT](./LICENSE) — © 2026 BI4MIB（何浩然）。
