# 源码来源与取证记录

- 获取时间：**2026-09-29**
- 取证方式：Fabric Loom `genSources`（Vineflower 反编译）+ `javap -p -constants` 核对 merged named jar

## 1. genSources 产物（反编译 mojmap MC 源码）

- 生成命令：
  ```bash
  export JAVA_HOME=~/jdks/jdk-25.0.4.1+1
  export GRADLE_USER_HOME=/home/user/Doubao/chats/38444798710241538/work/gradle-home
  J21=$(echo ~/jdks/jdk-21*)
  work/tools/gradle-9.8.0/bin/gradle -p work/MFPV50C genSources --no-daemon \
      -Porg.gradle.java.installations.paths="$J21" --console=plain
  ```
  结果：`BUILD SUCCESSFUL`，`Decompile cache stats: 0 hits, 6622 misses`。
- sources jar 绝对路径：
  ```
  /home/user/Doubao/chats/38444798710241538/work/MFPV50C/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-2ae02fda0f/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/minecraft-merged-2ae02fda0f-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2-sources.jar
  ```
- 解压目录（本轮精读所用）：
  ```
  /home/user/Doubao/chats/38444798710241538/work/research-v2/mc/mc-src/
  ```
- 关键被精读文件：
  - `net/minecraft/world/entity/LivingEntity.java`（travel/travelFallFlying/updateFallFlyingMovement/updateFallFlying/canGlide）
  - `net/minecraft/world/entity/player/Player.java`（tryToStartFallFlying/startFallFlying）
  - `net/minecraft/client/player/LocalPlayer.java`（sendPosition/aiStep/input）
  - `net/minecraft/client/player/KeyboardInput.java`
  - `net/minecraft/world/entity/projectile/FireworkRocketEntity.java`
  - `net/minecraft/server/network/ServerGamePacketListenerImpl.java`（handleMovePlayer/shouldCheckPlayerMovement/clamp*/teleport）
  - `net/minecraft/network/protocol/game/ServerboundMovePlayerPacket.java`
  - `net/minecraft/client/Camera.java`（setup/setRotation/getNearPlane/getMaxZoom）
  - `net/minecraft/client/renderer/GameRenderer.java`（updateCamera/renderLevel/renderItemInHand/getProjectionMatrix）
  - `net/minecraft/client/Minecraft.java`（setScreen/pause/runTick）
  - `com/mojang/blaze3d/platform/InputConstants.java`
  - `net/minecraft/world/level/gamerules/GameRules.java`

## 2. merged named jar（javap 核对用）

```
/home/user/Doubao/chats/38444798710241538/work/gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/minecraft-merged-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar
```
- 核对工具：`~/jdks/jdk-21.0.12.1+1/bin/javap -p [-constants] -classpath <jar> <fqcn>`
- LWJGL/JOML（`org.joml.Quaternionf` 等）位于：`work/gradle-home/caches/modules-2/`（运行时由 Loom 加入类路径）。

## 3. 工程与构建环境

- 工程：`/home/user/Doubao/chats/38444798710241538/work/MFPV50C`
  - `build.gradle.kts`：`fabric-loom 1.18.2`，`minecraft("com.mojang:minecraft:1.21.11")`，`mappings(loom.officialMojangMappings())`
  - `gradle.properties`：`minecraft_version=1.21.11`，`loader_version=0.19.5`，`fabric_version=0.141.6+1.21.11`
- JDK：运行 Gradle 用 `~/jdks/jdk-25.0.4.1+1`；toolchain 用 `~/jdks/jdk-21.0.12.1+1`
- Gradle：`work/tools/gradle-9.8.0`，`GRADLE_USER_HOME=work/gradle-home`

## 4. 外部参考（反作弊，非 MC 源码，标注为 [外部]/[推测]）

- Grim 移动预测系统（含 PredictionEngineElytra）：https://deepwiki.com/GrimAnticheat/Grim/4-movement-prediction-system
- Grim 官网（服务端移动复刻/版本覆盖说明）：https://grim.ac/ ，https://grim.ac/page/about
- 取阅时间：2026-09-29

## 5. 未读/证据缺口

- 第三方反作弊（Grim/Vulcan/NCP）内部阈值与 setback 逻辑未读源码，仅引用上述公开资料。
- Fabric `KeyBindingRegistry` 注册细节未逐行精读（在 fabric-api sources jar 内）。
- 客户端处理 `ClientboundPlayerPositionPacket` 回弹平滑逻辑未逐行精读。
