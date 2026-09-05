# Velox — 一个代替多个的 Fabric 优化模组（Minecraft 1.21.11）v1.0-release

**Velox = Lithium（里和纳，逻辑）+ Sodium（钠，渲染）+ Sodium Extra（钠扩展版，额外选项）合为一体，外加原创优化。**
装上 Velox，**不要再装 Lithium / Sodium / Sodium Extra**——Velox 已经把它们的等效优化整合进同一个模组，装重复的只会互相覆盖、谁先谁后不确定，反而可能负优化。

---

## 1.0-release 改了什么

这一版在 1.0-beta6（已含 Lithium 等效逻辑优化 + 帧率稳定器 + 动态剔除）的基础上**再进一步优化**。三条新优化全部只动渲染路径，**不触碰任何游戏逻辑**：

- **原创 · 经验球剔除（全新，`render.experience_orb_distance`）**：刷怪塔和末影龙会在地上留下成百上千个经验球，每个都是带动画的模型。现在它们有自己的剔除半径（`aggressive` 档默认 32 格）。**经验球照常被拾取，只是远处的不画**——玩法与逻辑完全不变。
- **剔除判定改为单轴提前退出**：距离判定用平方距离，若某一条轴的平方已经超过阈值，三轴之和必然也超过，剩下两条轴的乘法和加法就是纯浪费。现在第一条轴越界即判定。结果与逐位求和**完全相同**，只是对被剔除的对象（占绝大多数）少算约三分之二的算术。
- **自适应剔除节流 + 提前返回**：动态剔除原先每帧调整一次半径，会在"帧时间刚好跨过目标"的临界点来回抖动，表现为远处物体在剔除边缘反复闪现。现在每 `stability.adapt_interval` 帧（默认 6）才重新评估一次；并且当帧率健康、各半径都已回到配置基准时直接返回、不做任何计算——机器不卡时这部分开销为零。

上一版已有的：

- **原创 · 动态剔除距离**：`VeloxStabilizer` + `VeloxFast.adaptCulling` 把平滑后的 FPS 交给剔除器。FPS 低于目标时**实时收紧**实体 / 方块实体 / 掉落物 / 经验球的剔除距离，FPS 恢复后自动**回弹**到配置基准，零游戏逻辑改动。
- **原创 · FPS / 剔除状态监控**：稳定器每约 10 秒报告平均 FPS、最差帧、卡顿次数、粒子上限，以及当前实时剔除距离（`e/be/item/xp`）。对应 Sodium Extra 的「显示」概念，以日志呈现，无任何外部 API 依赖。
- **明确覆盖 Sodium Extra**：启动期一次性图形设置（关云、关实体阴影、最小粒子、快速图形、最便宜 AO）覆盖 Sodium Extra 的对应选项。

---

## Velox 怎么替代「锂 / 钠 / 钠扩展版」

| 被替代的模组 | Velox 中等效的优化 | 是否原创 |
|---|---|---|
| **Lithium** | `tick.*`：生物 AI 降频（`tick.mob_ai_throttle`）、目标选择器空转跳过（`tick.goal_selector_*`）、邻近查询缓存 | 部分是 |
| **Sodium** | 实体 / 方块实体 / 掉落物距离剔除、粒子限流、相机缓存、动态剔除（降载） | 动态剔除为**原创** |
| **Sodium Extra** | 启动期图形 boost（云/阴影/粒子/AO）、FPS 与剔除状态监控 | 监控为**原创** |
| — | **帧率稳定器**（原创）、**内存看门狗**（原创） | 原创 |

> 和前版一样，Velox 的设计铁律是：**关掉的优化根本不注入**，所以「关」意味着零运行时成本；每个 mixin 配置 `required:false`、注入 `require=0`，目标方法在版本里变了也只是日志告警、绝不崩。

---

## 三档预设

改 `config/velox.properties` 第一行（注：配置文件名沿用 `velox.properties`，模组的对外身份已是 Velox）：

```properties
profile=aggressive     # 1.0-release 默认，开箱即最强
```

| 档位 | 开启内容 | 适用 |
|---|---|---|
| **safe** | 启动期图形设置 + 物品剔除(32) + 2 个 tick 跳过 | **先从这个开始** |
| **balanced** | 上述 + 粒子限流(400/帧) | 有粒子风暴场景 |
| **aggressive** | 上述 + 方块实体剔除(64) + 实体剔除(96) + 远处 AI 降频 + 动态剔除常开 | **确认是 GPU 瓶颈时** |

单键覆盖（取消注释即可，活键永远赢过预设）：

```properties
profile=safe
render.block_entity_distance=64    # 其余仍按 safe
```

A/B 对比用 JVM 参数最快，不用反复改文件：

```
-Dprofile=safe -Drender.block_entity_distance=64
```

---

## 安装（重要：别用预览 jar）

**沙箱里的预览 jar 装进游戏必然无效**——它只是结构样本，没经过 Loom 重映射。必须本地构建一次：

```bash
cd Project
gradle wrapper --gradle-version 8.12    # 若压缩包里没有 gradlew
.\gradlew.bat build
```

产物：`Project/build/libs/velox-1.0-release.jar`（需 JDK 21 + 首次联网约 1–2 GB，依赖会被 Gradle 全局缓存复用）

放进 `mods/`，**然后删掉 `config/velox.properties`** 让它重新生成。

---

## 原创优化说明

### 帧率稳定器（`VeloxStabilizer` + `GameLoopMixin`）

每帧测一次真实帧时间，维护滚动平均 FPS。两项原创调节都**只做测量与预算/距离调节，从不触碰游戏逻辑**，因此绝不可能改变玩法：

1. **自适应粒子**：FPS 低时收紧每帧粒子预算（先削粒子风暴、保住其余画面帧数），恢复后回弹。
2. **自适应剔除（本版新增）**：FPS 低时收紧实体/方块实体/掉落物剔除距离，恢复后回弹。

### 内存看门狗（`MemoryWatchdog`）

周期性堆采样 + 报告 + 高位告警。本身不省内存——刻意不调 `System.gc()`、不背着游戏清缓存。

---

## 各优化项

### 启动期一次性设置（`VeloxClientBoost`）— 主力，覆盖 Sodium Extra

强制快速图形、关云、关实体阴影等。启动瞬间设置一次，热路径开销为零，GPU 收益持续整个会话。反射访问 `Options` 并尝试多个候选字段名，找不到就跳过——名字错了只丢一项，不崩。

### 掉落物 / 经验球 / 实体 / 方块实体剔除（覆盖 Sodium 渲染优化）

默认开的只有掉落物剔除（32）。经验球剔除（`render.experience_orb_distance`）与远处实体/方块实体剔除在 `aggressive` 档打开。全部设为 `0` 时对应 mixin 完全不注入。

经验球、掉落物这类"数量极多、单个极小"的实体收益最明显：省下的绘制调用远多于丢掉的像素。经验球照常被拾取，只有绘制被跳过。

判定采用**单轴提前退出**：平方距离的第一条轴若已超过阈值，其余两轴不再计算。结果与完整求和逐位一致，但对绝大多数（被剔除的）对象少做约三分之二的算术。

### 粒子限流（覆盖 Sodium）

限制每帧新粒子数，已在场的不受影响，现有特效不会突然消失。

### 动态剔除（原创，对应 Sodium 动态降载）

`stability.adaptive_culling=true`（默认开）时，稳定器在 FPS 低于 `stability.target_fps*0.9` 时收紧剔除距离，不低于 `stability.min_cull_distance`（默认 48 格）；FPS 恢复后回弹到配置基准。

调整每 `stability.adapt_interval` 帧（默认 6）才发生一次，而不是每帧：每帧微调会让半径在临界点附近来回抖动，表现为远处物体在剔除边缘反复闪现。另外，当帧率健康且各半径都已回到配置基准时，这段逻辑直接返回、不做任何计算。

---

## 调优：自己测，别信默认值

你的瓶颈只有你的机器知道：

1. `profile=safe` 跑一次，记 fps
2. 只开一项，重启，再记
3. 掉了就说明这项在你的机器上亏本，关掉

```bash
-Dprofile=safe                                    # 基线
-Dprofile=safe -Drender.block_entity_distance=64  # 只测方块实体剔除
-Dprofile=safe -Drender.particle_budget=400       # 只测粒子限流
```

### 看日志确认生效

```bash
grep -E "Velox.*(PASS|FAIL|ACTIVE|Graphics boost|Not injected)" logs/latest.log
```

- `Graphics boost: N setting(s) applied`：启动期改了几项
- `FPS stabilizer: avg ... fps, ... cull(e/be/item/xp): ...`：实时剔除距离（实体/方块实体/掉落物/经验球）随 FPS 变化
- `Not injected (off in config, so zero runtime cost): ...`：因配置关闭而从未注入的项，运行时开销严格为零

**注意 `PASS` 不等于"在跑"**：`PASS` 只说明目标方法存在、可以注入；该项是否开启看上面那行 `Not injected`。

---

## 版本锁定

| 组件 | 值 |
|---|---|
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.4` |
| Fabric Loom | `1.14-SNAPSHOT`（已固定 1.14.10） |
| Java | `21` |

1.21.11 是**最后一个混淆版本**（26.1 起不混淆），必须用带重映射的 Loom。

构建失败先试：`.\gradlew.bat build --refresh-dependencies`

---

## 许可证

MIT
