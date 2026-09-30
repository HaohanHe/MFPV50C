# Minecraft 1.21.11（Fabric / mojmap）纯客户端 FPV 模组可行性核对

- 环境：MC 1.21.11，Fabric Loom 1.18.2，`loom.officialMojangMappings()`（mojmap），客户端工程 `work/MFPV50C`
- 取证方式：Loom `genSources`（Vineflower 反编译，6622 类）产出的 sources jar 精读 + `javap -p -constants` 核对 merged named jar
- 获取时间：2026-09-29
- 标注约定：**[已证实]** = 读到反编译源码/javap；**[推测]** = 未直接读该部分、依据已读代码推断；**[外部]** = 第三方反作弊公开资料，非 MC 源码

---

## 0. 总判断（先说结论）

**"纯客户端 FPV"作为一个整体是可以成立的，但必须拆成两类改动来看：**

1. **纯相机姿态（含 roll 横滚）+ 直接 GLFW 遥控器输入 = 100% 纯客户端、对服务器完全透明。**
   roll 根本不进网络包（移动包只带 X/Y/Z + yRot + xRot + onGround，见 §2），改它等于只改本地渲染视图矩阵，服务器看不见、也不会回弹。这是 FPV 观感的核心，**安全**。

2. **平动/受力/推力的客户端修改（setVelocity、改 elytra 受力、改烟花推力）在单机完全自由；在真实公网服务器上， vanilla 服务器有一套"客户端可信 + 容差校验"模型——小幅改动在容差内可被接受，但一旦超出速度阈值或穿模就会被 teleport 拉回（rubber-band）；而装了预测型反作弊（Grim 等）的服务器会把任何偏离原版物理的平动都判为飞行/加速并 setback/封禁。** 这部分**不能算纯客户端成立**，公网高反作弊服上做就是外挂行为。

一句话：**"只做相机姿态"是安全的纯客户端 mod；"改玩家平动/推力"在单机/自己服可做，在公网服属于会被回弹/封禁的灰色外挂行为。**

---

## 1. 鞘翅全链路（触发 / 维持 / 推力 / 受力方程）

### 1.1 FallFlying 状态的触发

- **[已证实]** 触发入口在 `LocalPlayer.aiStep()`（`net/minecraft/client/player/LocalPlayer.java:825`）：
  ```java
  if (this.input.keyPresses.jump() && !bl5 && !bl && !this.onClimbable() && this.tryToStartFallFlying()) { ... }
  ```
  即：在空中按跳跃键、不在梯子上、且 `tryToStartFallFlying()` 成功。
- **[已证实]** `Player.tryToStartFallFlying()`（`Player.java:1441`）：
  ```java
  if (!this.isFallFlying() && this.canGlide() && !this.isInWater()) { this.startFallFlying(); return true; }
  ```
- **[已证实]** `Player.startFallFlying()`（`Player.java:1450`）：`this.setSharedFlag(7, true);`
  —— flag 7 是实体同步数据（SynchedEntityData）里的 **FALL_FLYING 位**，客户端设置后会随实体数据包同步给服务器。

### 1.2 维持条件（每 tick 谁检查、耐久/烟花）

- **[已证实]** `LivingEntity.canGlide()`（`LivingEntity.java:3045`）——这是能否滑翔的硬条件：
  ```java
  if (!this.onGround() && !this.isPassenger() && !this.hasEffect(MobEffects.LEVITATION)) {
      // 遍历 EquipmentSlot.VALUES，任一槽位物品 canGlideUsing(...)（即鞘翅）即 true
  } else return false;
  ```
  要求：**不在地面、不骑乘、无漂浮效果、身上有可用鞘翅**。
- **[已证实]** `LivingEntity.updateFallFlying()`（`LivingEntity.java:3020`），由 `aiStep()` 在 `isFallFlying()` 时调用（`LivingEntity.java:2954-2956`）：
  - 耐久消耗只在服务端执行（`!this.level().isClientSide()`，行 3022）：每 10 tick 一次，隔一次（`i/10 % 2 == 0`）随机挑一个可用鞘翅槽位 `hurtAndBreak(1, ...)`，并发 `GameEvent.ELYTRA_GLIDE`。
  - **关键回弹点**：服务端这里 `if (!this.canGlide()) { this.setSharedFlag(7, false); return; }`（行 3023-3025）——一旦落地/骑乘/拿到漂浮/鞘翅坏了，**服务端主动清掉滑翔 flag，强制结束滑翔**。
- **[已证实]** `fallFlyTicks`（`LivingEntity.java:251`）：滑翔中每 tick +1（行 2704），否则归 0（行 2706）。

> 推论 **[推测]**：纯客户端把 `setSharedFlag(7,true)` 或本地 `isFallFlying()` 弄成"假装在飞"是没用的——服务端 `updateFallFlying()` 每个 tick 用 `canGlide()` 复核，不满足就清 flag 并发回，客户端表现被拉回。

### 1.3 烟花火箭推力如何施加

- **[已证实]** `FireworkRocketEntity.tick()`（`FireworkRocketEntity.java:109`）。当火箭 `isAttachedToEntity()`（绑在玩家身上，右键鞘翅时使用烟花即此形态）且 `attachedToEntity.isFallFlying()`（行 124）：
  ```java
  Vec3 look = attachedToEntity.getLookAngle();
  Vec3 v = attachedToEntity.getDeltaMovement();
  attachedToEntity.setDeltaMovement(v.add(
      look.x*0.1 + (look.x*1.5 - v.x)*0.5,
      look.y*0.1 + (look.y*1.5 - v.y)*0.5,
      look.z*0.1 + (look.z*1.5 - v.z)*0.5
  ));
  ```
  即每个 tick：`新速度 = 0.5*旧速度 + 0.85*视线方向`（常量 **目标 1.5、线性项 0.1、混合系数 0.5**）。火箭 `lifetime = 10*(1+flightDuration) + rand`（行 67），**爆炸只在服务端**（行 180 `level() instanceof ServerLevel`）。
- **[已证实]** 推力作用在 `attachedToEntity` 的 `setDeltaMovement` 上，实体 tick 在客户端和服务端都跑（同一公式确定性复现），所以烟花推力是**双侧一致预测**的。客户端改本地烟花推力公式不会让服务端采纳——服务端那枚火箭实体自己会再算一遍。

### 1.4 滑翔期 travel/tick 受力方程

- **[已证实]** 调度：`LivingEntity.travel(Vec3)`（`LivingEntity.java:2302`）→ `isFallFlying()` 时走 `travelFallFlying(vec3)`（行 2306）。
- **[已证实]** `travelFallFlying`（`LivingEntity.java:2434`）：若 `onClimbable()` 则退回 `travelInAir` 并 `stopFallFlying()`；否则 `setDeltaMovement(updateFallFlyingMovement(deltaMovement))` 后 `move(SELF, deltaMovement)`；**撞墙伤害/掉落音只在服务端**（行 2443）。
- **[已证实]** `updateFallFlyingMovement(Vec3)`（`LivingEntity.java:2455`）精确方程（f = xRot 弧度，look = 视线单位向量，g = 有效重力，h = cos(f)²）：
  1. `v.y += g * (-1.0 + 0.75*h)`（低头滑翔时重力补偿、抬头时升力）；
  2. 若 `v.y<0` 且有水平视线分量：沿视线 `v += look * (v.y*-0.1*h)`（俯冲拉正）；
  3. 若 `f<0`（抬头）且有水平速度：`v += look * (e*-sin(f)*0.04)`，其中竖直分量 ×3.2（抬头加速爬升）；
  4. 朝水平视线方向收敛 `v.xz += (look.xz/|look.xz|*e - v.xz)*0.1`；
  5. 阻尼 `v *= (0.99, 0.98, 0.99)`。

> 这段物理在**客户端和服务端各跑一份**做预测与权威模拟。客户端 mod 若改 `updateFallFlyingMovement` 的结果（例如额外加推力），客户端位置会偏离服务端预期——进入 §2 的回弹/反作弊范畴。

---

## 2. 客户端移动边界（LocalPlayer → 发包 → 服务端校验）

### 2.1 LocalPlayer 采集输入与发包

- **[已证实]** `LocalPlayer.sendPosition()`（`LocalPlayer.java:260`）每 tick 调用，发的是**客户端自己的真实位置**：
  ```java
  boolean bl  = lengthSquared(dx,dy,dz) > 2.0E-4 || positionReminder >= 20; // 移动>0.01格或每20tick保底
  boolean bl2 = yRotDelta != 0 || xRotDelta != 0;
  // 按情况发 ServerboundMovePlayerPacket.PosRot / Pos / Rot / StatusOnly
  ```
  包内容（`ServerboundMovePlayerPacket`）：`getX/getY/getZ`、`getYRot/getXRot`、`onGround`、`horizontalCollision`。
- **关键 [已证实]**：包里**没有 roll/四元数**，只有 yRot + xRot。→ **roll 永远不上行，服务端对横滚一无所知。**
- **[已证实]** 输入采集：`KeyboardInput.tick()`（`KeyboardInput.java:26`）直接读 `KeyMapping.isDown()` 填 `Input`（前后左右/跳/shift/冲刺）。

### 2.2 服务端位置校验与精确回弹阈值

`ServerGamePacketListenerImpl.handleMovePlayer`（`ServerGamePacketListenerImpl.java:1025`）：

1. **非法值**：`containsInvalidValues`（行 420）任一坐标 NaN 或角度非有限 → 直接 `disconnect("invalid_player_movement")`（行 1034）。
2. **坐标钳制**：`clampHorizontal = ±3.0E7`，`clampVertical = ±2.0E7`（行 424-429）。
3. **速度校验（"moved too quickly"）**，行 1068-1084：
   - `bl = player.isFallFlying()`；
   - `r = bl ? 300.0F : 100.0F`（**行走 100、滑翔 300**，单位"格²"）；
   - `q = receivedMovePacketCount - knownMovePacketCount`（距上一服务端 tick 收到的包数，>5 截为 1）；
   - `p = (dx)²+(dy)²+(dz)²`（相对 `firstGood` 的累计位移平方），`o = player.getDeltaMovement().lengthSqr()`；
   - **若 `p - o > r*q` → `teleport(...)` 把玩家拉回服务端位置**（行 1081），即 rubber-band。
   - 门控 `shouldCheckPlayerMovement(bl)`（行 1164）：`isSingleplayerOwner()` 时**直接 false（单机主玩家跳过速度校验）**；跨维度 false；`gameRule PLAYER_MOVEMENT_CHECK` 关时 false；滑翔时还要求 `ELYTRA_MOVEMENT_CHECK`。
4. **碰撞/位移校验（"moved wrongly"）**，行 1096-1155：
   - 服务端先 `player.move(MoverType.PLAYER, 客户端位移)`，再算残留 `p = 残留位移²`；
   - `bl4 = p > 0.0625`（**残留 > 0.25 格**）且非创造/旁观/换维度/免宽限 → 记 "moved wrongly"；
   - 若 `bl4 && 服务端认为穿进新方块` → **`teleport(i,j,k)` 硬拉回上一服务端位置**（行 1152）；否则 `absSnapTo(客户端报的位置)`（行 1121），**接受客户端位置**。
5. **视角包是否被校验**：正常路径里 yRot/xRot 经 `Mth.wrapDegrees` 后直接采纳（行 1043-1046 在待确认传送时同步角度）；速度违规触发 teleport 时回传的是**服务端自己的 yRot/xRot**（行 1081 传 `player.getYRot()`，不是客户端报的 f,g）。即：正常时视角信任客户端，一旦回弹，角度随传送包一起被服务端值覆盖。

### 2.3 明确结论：哪些能纯客户端持续、哪些会被纠正

| 改动类别 | 单机（集成服） | 局域网/自己的开服 | 公网原版服 | 公网+预测反作弊 |
|---|---|---|---|---|
| 相机 roll / 姿态四元数注入渲染 | 可做 | 可做 | **可做（服务端不可见）** | **可做（渲染层，服务端探测不到）** |
| 视角 yRot/xRot 由遥控器直接驱动 | 可做 | 可做 | 可做（包本就发 yRot/xRot） | 可做，但摇杆加速转动过快可能被"角度/packet 速率"类检查标记 **[推测]** |
| 小幅平动（setVelocity 小量、在 100/300 阈值内、不穿模） | 自由 | 基本可做 | 容差内被接受、可做 | 仍可能被预测引擎判偏离 **[外部]** |
| 大幅加速/瞬移（p-o 超阈值） | 自由 | 自由（主玩家/或关 gamerule） | **回弹（teleport）** | setback/封禁 |
| 穿方块/穿墙移动（残留>0.25格且碰撞） | 自由 | 自由 | **回弹 teleport** | 立即标记 |
| 改 `updateFallFlyingMovement` 推力曲线 | 自由 | 基本可做 | 容差内短期成立、长期漂移回弹 | **预测型 AC 直接判飞行/加速** |
| 客户端伪造烟花推力（不依赖真烟花实体） | 自由 | 可做 | 超出 elytra 物理即回弹 | 判 elytra-flight 作弊 |

**安全做法（依据读到的阈值）：**
- **只做相机姿态**：把所有创新力都放进 `Camera.rotation()`（见 §3），完全不碰 `LocalPlayer.setDeltaMovement`/位置。这是 FPV mod 最稳的形态——飞行动作仍由原版鞘翅+真烟花完成，mod 只负责"看起来像穿越机"。
- **若一定要加客户端平动**：把额外加速度限制在服务端容差内——累计相对 `firstGood` 的位移平方减自身速度平方，行走不超过 `100*q`、滑翔不超过 `300*q`（q≈1，即每 tick 累计约 ≤100 / ≤300 格²，滑翔约对应每秒十几格量级）；且**绝不穿方块**（残留务必 < 0.0625）。更稳妥的是**仅在单机/局域网主玩家时启用平动增强**（`isSingleplayerOwner()==true` 或 `level.isClientSide && hasSingleplayerServer`），公网服自动降级为纯相机模式。

---

## 3. 相机：setup / renderLevel 调用路径与 roll 注入影响

### 3.1 真实调用路径

- **[已证实]** `GameRenderer.render(...)`（`GameRenderer.java:467` 一带）→ `updateCamera(deltaTracker)`（行 706）→
  ```java
  mainCamera.setup(level, cameraEntity,
      !options.getCameraType().isFirstPerson(),  // detached
      options.getCameraType().isMirrored(),       // thirdPersonFront
      partialTick);                               // 行 716-717
  ```
- **[已证实]** 随后 `renderLevel(deltaTracker)`（行 721）→ `extractCamera(f)`（行 728，把 camera pos/rotation 拷进 `cameraRenderState`，行 797 `orientation = new Quaternionf(this.mainCamera.rotation())`）→ 构造视图矩阵：
  ```java
  Quaternionf q = this.mainCamera.rotation().conjugate(new Quaternionf()); // 行 755
  Matrix4f view = new Matrix4f().rotation(q);                            // 行 756
  ```
  → `levelRenderer.renderLevel(..., view, proj, ...)`（行 765）→ 清深度后 `renderItemInHand(f, bl3, view)`（行 776）。

### 3.2 roll 注入点

- **[已证实]** `Camera.setRotation(yRot, xRot)`（`Camera.java:138`）：
  ```java
  this.rotation.rotationYXZ(PI - yRot*deg2rad, -xRot*deg2rad, 0.0F);
  //                                                            ^^^^ 横滚角硬编码 0
  ```
  注入方式：在 `Camera.setup()` 返回后（或 mixin 进 `setRotation` 尾部）对 `this.rotation` 右乘一个 roll 四元数即可；`renderLevel` 在同一帧之后才读 `rotation()`，所以能生效。`forwards/up/left` 三个向量都由 `this.rotation` 旋转得出（行 142-144），会跟随 roll。

### 3.3 注入 roll 后的影响

- **[已证实]** near plane / FOV：`getNearPlane()`（`Camera.java:198`）near 距离写死 **0.05**，up/left 缩放 `tan(fov/2)*0.05*aspect`。roll 只旋转近平面四角，**不改变 FOV、不改变近裁面距离**，投影矩阵 `getProjectionMatrix`（`GameRenderer.java:449`）不含 roll → **画面只是整体旋转，无缩放/透视畸变**。
- **[已证实]** 相机碰撞（第三人称 clip）：`getMaxZoom()`（`Camera.java:112`）沿 `this.forwards` 反方向做 8 个采样 raycast；roll 后 forwards 随 roll → 第三人称拉远方向跟着 roll。第一人称 FPV 不触发拉远，影响可忽略。
- **[已证实]** 第一人称手/手持物：`renderItemInHand` 用的是和世界渲染**同一个 view 矩阵 `matrix4f2`**（行 776），而该矩阵来自含 roll 的 `camera.rotation().conjugate()`。→ **手与物品会和世界一起旋转，即相对屏幕保持固定**，正是 FPV"机架不动、世界倾斜"的观感。
- **[已证实]** 副作用：`getFluidInCamera()`（行 209）用近平面四角探测水/熔岩/细雪雾；roll 90° 时四角采样点偏到侧面，贴水/贴墙处雾效判定可能轻微错位 **[推测，影响很小]**。

---

## 4. 输入：GLFW 摇杆 / KeyMapping / Screen 生命周期

### 4.1 GLFW joystick 可用性与热插拔

- **[已证实]** **原版 1.21.11 客户端没有任何手柄/摇杆抽象**：`InputConstants.Type` 枚举只有 `KEYSYM / SCANCODE / MOUSE`（`InputConstants.java:325-340`），全反编译源码里 `grep joystick/gamepad` 无命中。
  → **USB 遥控器必须 mod 自己直接调 LWJGL 的 GLFW joystick API**（`glfwJoystickPresent / glfwGetJoystickAxes / glfwGetJoystickButtons / glfwJoystickIsGamepad / glfwGetGamepadState`）。
- **[已证实]** MC 在渲染/主线程 `runTick` 里跑 `glfwPollEvents`；热插拔回调 `glfwSetJoystickCallback` 在 `glfwPollEvents` 期间于主线程触发。摇杆轴/按钮状态查询按 GLFW 约定可在轮询线程读——**在客户端 tick 或渲染回调里轮询是安全的**（都在主线程）。**[推测]** 不要在工作线程直接调需事件泵的 GLFW 窗口函数。

### 4.2 KeyMapping 注册

- **[已证实]** 原版键位在 `Options` 里 `new KeyMapping(...)` 构造并注册；Fabric 环境用 Fabric API 的键位注册（`KeyBindingRegistry`，fabric-api 模块，非 MC 源码）。
- **[已证实]** `KeyMapping` 关键方法：`isDown()`（按住态）、`consumeClick()`（边沿触发计数）、静态 `click/set/setAll/releaseAll`。`KeyMapping` 构造支持 `(name, Type, code, Category)`。

### 4.3 Screen 生命周期与"开 GUI 时游戏输入是否暂停"

- **[已证实]** `Minecraft.setScreen(screen)`（`Minecraft.java:1141`）：
  - 开新屏 → `screen.added()` → `mouseHandler.releaseMouse()` + **`KeyMapping.releaseAll()`**（行 1148，所有键态清零）→ `screen.init(...)`；
  - 关屏 → `KeyMapping.restoreToggleStatesOnScreenClosed()` + `mouseHandler.grabMouse()`。
- **[已证实]** 暂停判定（`Minecraft.java:1340`）：
  ```java
  this.pause = hasSingleplayerServer()
      && (screen != null && screen.isPauseScreen() || overlay.isPauseScreen())
      && !singleplayerServer.isPublished();
  ```
  - 暂停**只在单机集成服**发生，且要求 `screen.isPauseScreen()==true`、且世界**未对局域网开放**；
  - 暂停时 `level.animateTick / particleEngine.tick / ServerboundClientTickEndPacket` 全部停发（行 1848-1859）。
- **结论 [已证实+推测]**：
  - 开 Screen 时，原版鼠标指针释放、`KeyMapping.releaseAll()` 使移动键全部视为松开——**原版 WASD/跳跃输入在 GUI 打开时不进入 `KeyboardInput.tick()` 结果**。
  - 但 mod **直接轮询 GLFW 摇杆**不经过 KeyMapping/Screen，所以遥控器轴在 GUI 打开时仍可读。FPV 配置屏应**覆写 `isPauseScreen()` 返回 false**（避免飞行中暂停），并注意：即使不暂停，开屏期间原版键鼠移动已被 releaseAll，飞行控制应完全走遥控器直采。

---

## 5. 多人 / 反作弊边界

- **[已证实]** vanilla 服务端的全部移动纪律即 §2：速度阈值（行走 100 / 滑翔 300 格²·q）、残留碰撞阈值 0.0625（0.25 格）、NaN 断线、坐标 ±3e7/±2e7、两个 gamerule（默认都 true）。**vanilla 本身对"客户端报的位置"相当信任**，只要不瞬移、不穿模就放行。
- **[外部]** 主流预测型反作弊（Grim 开源可查）：服务端**逐 tick 复刻 MC 物理**（含专门的 `PredictionEngineElytra` 滑翔引擎），把客户端实际位置与"物理预测位置"比对，速度类检测精度约 0.01%；位置伪装/实体位移 = setback 乃至封禁。渲染层作弊（视角/相机 roll、ESP、X-Ray）服务端探测不到。
- **风险分级：**
  - **相机 roll / 纯视角观感**：单机/局域网/公网/装不装 AC，**均低风险、不可见**。
  - **遥控器驱动 yRot/xRot**：低风险；仅当转动速率/发包频率异常快时可能被 packet-rate/角度类检查标记 **[推测]**。
  - **客户端平动/推力增强**：单机无风险；自建服低风险（可关 gamerule）；公网原版服中风险（超阈值回弹、体验割裂）；公网 Grim/Vulcan/NCP 服 **高风险，等同 elytra-fly/speed 外挂，会 setback/封禁**。

---

## 附：改动项 × 环境可行性总表

| 改动项 | 单机/集成服 | 局域网(自己开) | 公网原版服 | 公网+预测AC(Grim等) | 原因（类/方法/常量） | 安全做法 |
|---|---|---|---|---|---|---|
| 相机 roll 四元数注入渲染 | 可做 | 可做 | 可做 | 可做 | `Camera.setRotation` roll=0(`Camera.java:141`)；包不含 roll(`ServerboundMovePlayerPacket`) | mixin 进 Camera.rotation()，只动视图矩阵 |
| 手持物/手跟随 roll | 可做 | 可做 | 可做 | 可做 | `renderItemInHand` 共用 view 矩阵(`GameRenderer.java:776`) | 自动跟随，无需额外处理 |
| FOV/near-plane 改动 | 可做 | 可做 | 可做 | 可做 | near=0.05(`Camera.java:201`)；投影矩阵不含 roll | roll 本身不改 FOV |
| 遥控器→yRot/xRot | 可做 | 可做 | 可做 | 可做(低风险) | 包发 yRot/xRot；`sendPosition`(`LocalPlayer.java:272`) | 限转速，避免 packet 速率异常 |
| 遥控器→角色平动 setVelocity | 可做 | 受限 | 受限 | 不可做 | 速度阈值 100/300(`:1078`)；残留 0.0625(`:1109`) | 仅单机启用；公网不做 |
| 改 elytra 推力曲线 | 可做 | 受限 | 受限 | 不可做 | `updateFallFlyingMovement`(`:2455`)双侧复刻；Grim `PredictionEngineElytra` | 公网只用原版鞘翅+真烟花 |
| 客户端伪造烟花推力 | 可做 | 受限 | 受限 | 不可做 | 烟花推力由服务端火箭实体算(`FireworkRocketEntity.java:131`) | 用真烟花实体 |
| 瞬移/穿方块 | 可做 | 可做(主玩家) | 不可做(回弹) | 不可做(封禁) | `teleport` 回弹(`:1081/:1152`)；残留>0.25格 | 不要做 |
| 热插拔检测遥控器 | 可做 | 可做 | 可做 | 可做 | GLFW 无原生支持，自调 API；回调在主线程 | `glfwSetJoystickCallback` |
| 飞行中开配置屏 | 可做 | 可做 | 可做 | 可做 | `setScreen` releaseAll 键(`:1148`)；`isPauseScreen` | 覆写 `isPauseScreen()=false` |

---

## 证据缺口（未读到/未验证，禁止当作结论）

1. **第三方反作弊内部阈值**：Grim/Vulcan/NCP 的具体 setback 阈值、角度/速率检查我只引用了公开文档（DeepWiki/grim.ac），未读其源码；表中"高风险/封禁"是公开模型推断，非实测。
2. **Fabric 键位注册 API**：`KeyBindingRegistry` 在 fabric-api sources 里，本轮未逐行读；KeyMapping 行为基于 MC 本体类。
3. **GLFW joystick 函数的线程安全细节**：依据 GLFW 官方约定推断"轮询可在主线程安全调用"，未在 1.21.11 实测热插拔时序。
4. **客户端预测回弹的体感延迟**：服务端 `teleport` 后客户端如何平滑回拉（ClientPacketListener 处理 `ClientboundPlayerPositionPacket`）本轮未逐行精读，只确认了服务端会发传送包。
