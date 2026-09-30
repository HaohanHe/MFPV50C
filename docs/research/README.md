# Research index（研究资料总索引）

本目录收录为 MFPV50C（MC 1.21.11 Fabric，纯客户端 FPV 模组）做的联网研究与数据规格。
所有内容为 MIT clean-room：只记录公开公式、参数名、架构与来源，不复制 GPL 源码文本。

## 目录

| 子目录 | 内容 | 日期 |
|---|---|---|
| `flight-control/` | **本轮**飞控谱系正名（Cleanflight/Betaflight/INAV/Butterflight/EmuFlight）、四旋翼升力方向（body-up）、倾斜推力分解、悬停油门、协调转弯、ArduPilot/PX4 对照 | 2026-09-30 |
| `f9u/` | F9U（FAI Volume F9 + Annex + 2025 提案 + TWG2025 + 2026 中国规则）一手规则精读：赛制、门/障碍净空、计时、排名、起降、罚则、反乌龟、硬件 | 2026-09-30 |
| `betaflight/` | Betaflight 全栈：mixer/airmode/PID/rates/throttle/3D/OSD/电池；armingDisableFlags、osd_items 源码枚举 | 2026-09-30 |
| `mc/` | Minecraft 1.21.11 可行性：鞘翅 travel、相机注入点、移动包/反作弊、GLFW joystick、mixin 点 | 2026-09-30 |
| `competitor/` | 竞品（Uncrashed / Liftoff / DRL / VelociDrone 等）单文件分析 HTML（自包含） | 2026-09-30 |

每个子目录通常含三件：`report-*.md`（中文报告）、`data-*.json`（机器可读，均通过 json 校验）、`sources-*.md`（来源清单）。

## 未包含在本仓库的研究（云端工作区，未在切机前取回）

以下研究在云端 Linux 工作区产出，切到 Windows 再切回 Linux 的过程中本机无副本；
其中部分在整理时未保留可下载的公网链接，故未收录原文，仅在此登记，需要时重新生成：

- `research-v2/phys-const/`：物理常数开源依据（Flightmare 质量/惯量、5" 桨实测静拉力、LiPo 内阻、ArduPilot SITL）。
- `research-v2/replay/`：ReplayMod 录制/回放/离屏逐帧/FFmpeg 导出管线研究。
- `research-v2/input-bind-report.md`：全通道引导式 move-to-bind 输入绑定报告。

## 外部参考（图像，不在仓库）

- Uncrashed 简中“高级-无人机设置 / 选项-控制”截图：用于参数结构与校准流程对照（闭源，只学结构不抄数值）。
