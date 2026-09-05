# Optima — Fabric 优化模组（Minecraft 1.21.11）v1.0-beta4

一个针对 GPU / CPU / 内存占用的优化模组，带三档预设和逐项开关。

---

## 1.0-beta4 改了什么

这一版聚焦「**稳定帧率**」，并把 **Lithium（里和纳）** 的核心优化思路吸收进 Optima——装了 Optima 就不再需要单独装 Lithium。

- **帧率稳定器（全新）**：`OptimaStabilizer` + `GameLoopMixin` 每帧测一次真实帧时间，维护滚动平均 FPS；当 FPS 低于目标值时自动收紧粒子预算（卡顿尖峰先削粒子风暴、保住其余画面的帧数），恢复后自动回弹。全程只做测量与预算调节，**绝不改动游戏逻辑**，因此不可能改变玩法。
- **默认档位改为 `aggressive`**：开箱即把每个剔除 + 远处生物 AI 节流全部打开，达到「优化非常好」。
- **内置 Lithium 等效逻辑优化**：`tick.*`（生物 AI 节流、目标选择器空转跳过）对应 Lithium 的 entity_ai / goal 优化；`render.*`（掉落物/实体距离剔除、粒子限流）对应其渲染与实体优化；`memory.watchdog` + 帧率稳定器进一步平滑体验。fabric.mod.json 已声明「安装 Optima 即无需 Lithium」。

配置新增 `stability.*` 一组（生成于 `config/optima.properties`）：`fps_governor`、`target_fps`、`adaptive_particles`、`log_stutters`、`stutter_ms`。

---

## 1.0.3 改了什么

1.0.2 的思路是「加优化」，1.0.3 的思路是**把优化自身的开销拿掉**。

前两版一直在修同一个错误，只是每次发现它藏得更深：

| 版本 | 发现的问题 | 处理 |
|---|---|---|
| 1.0.1 | 统计代码压在热路径，比优化本身还贵 | 改成 plain `long`，默认关闭 |
| 1.0.2 | 未重映射时直接崩 | 全部包 `try/catch`，改由 mixin 触发 |
| **1.0.3** | **关掉的优化仍在每次调用时收费** | **关掉的 mixin 根本不注入** |

---

### ① 关掉的 mixin 根本不注入（主要改动）

**1.0.2 的漏洞**：一个在配置里关闭的优化，代码其实还在。它只是在第一行 `return`。

听起来免费，其实不是。一个 `cancellable` 的注入点会让 Mixin **每次调用都分配一个 `CallbackInfo`**，并让目标方法变大、更难被 JIT 内联。

```java
// 1.0.2：safe 档下面 blockEntityDistance=0，这段代码
// 仍然为每个方块实体、每帧分配一个 CallbackInfo，然后什么都不做
private void optima$cullByDistance(...) {
    if (!OptimaFast.beCullEnabled) return;   // 分配已经发生了
    ...
}
```

这正是 1.0.1 统计代码犯过的同一个错误，只是往下藏了一层：**为一个已经关闭的功能，付真实的、每次都付的代价。**

**1.0.3 的做法**：新增 `OptimaMixinPlugin`，在类被加载前决定这个 mixin 是否允许存在。返回 `false` 意味着这个 mixin 从未被合并进目标类——没有注入代码、没有 `CallbackInfo`、目标方法没有变大、JIT 看到的就是原版方法。

**safe 档下的实际差别：**

| | 1.0.2 | 1.0.3 |
|---|---|---|
| 注入的 mixin 数 | 8 | 5 |
| 方块实体渲染 | 每个每帧 1 次 `CallbackInfo` 分配 | 无注入 |
| 粒子 `add` | 每个粒子 1 次 `CallbackInfo` 分配 | 无注入 |
| `Mob#serverAiStep` | 每生物每 tick 1 次分配 | 无注入 |

**失败方向是安全的**：这个类里任何异常、任何不认识的名字，都返回 `true`——也就是回到 1.0.2 的行为，每个 mixin 自己在运行时判断开关。所以这个类出错了**只会慢，不会错**。

**代价**：改配置必须重启。这一点本来就是如此（mixin 在类加载时织入），现在只是没有例外了。

---

### ② 粒子 mixin 一分为二

`ParticleEngineMixin` → `ParticleEngineTickMixin` + `ParticleEngineAddMixin`。

- `tick` 注入：重置预算、推进帧时钟。不可取消，每帧一次，开销可忽略。**永远注入。**
- `add` 注入：限流，必须可取消。**只在 `render.particle_budget > 0` 时注入。**

`add` 是整个模组最热的钩子——粒子风暴时每秒几千次——而且它必须可取消才能生效，所以它是 `CallbackInfo` 分配的头号来源。限流关着的时候，它没有任何理由存在。

拆分带来一个新风险，也一并堵上了：预算重置依赖 `tick` 注入成功，如果那个注入失效，预算会填满一次然后**永久**挡掉所有新粒子。所以预算消耗走 `OptimaFast.tryConsumeParticleBudget()`，它自带退路——心跳长时间不来就改成按调用次数重置。失效时限流变宽松，但绝不会卡死。

### ③ 掉落物判定：字符串比较 → 一次类引用比较

1.0.2 判断"这是不是掉落物"用的是：

```java
ITEM_ENTITY_CLASS.equals(entity.getClass().getName())   // 40 字符的 String.equals
```

每个实体、每帧一次。这是一个优化模组最不该出现在自己最热的循环里的东西。

1.0.3 把 `ItemEntity` 的 `Class` 解析一次，之后每次判定是一次 `isInstance`。解析不到就永久当作"不是掉落物"，退化成统一用 `entity_distance`，不会崩。

### ④ 相机缓存：每 64 次调用 → 每帧一次

1.0.2 的相机缓存每 64 次调用刷新一次。在一个有几千个方块实体的存档里，一帧之内仍然会刷新几十次——而相机在一帧内根本不动。

1.0.3 用帧时钟（`ParticleEngine#tick` 每帧推进一次）判断，一帧只读一次相机。心跳缺失时自动退回每 64 次的旧行为，不会冻结。

### ⑤ 邻近查询：缓存坐标访问器 + 阈值短路

`PlayerProximity` 每算一次距离会做 6 次 `getMethod`——方法表扫描加 `Method` 对象克隆。每个生物对每个玩家、每 tick。

1.0.3 把 `getX/getY/getZ` 解析一次复用；并且在**第一个已经足够近的玩家处就停止扫描**。唯一的调用方只关心"有没有玩家在半径内"，早退给的答案完全一样。这让二十人的服务器和单人世界的开销大致相同。

### ⑥ 其余修正

- **`profile` 变成幂等的**：`safe` 分支现在也显式赋值每一项。之前它是空的（依赖字段默认值），而现在配置会被读两次（mixin 插件一次、入口点一次），非幂等会导致第二次读到脏值。
- **`MemoryWatchdog`**：线程字段改 `volatile` 并加同步，注册 shutdown hook 干净退出。
- **`OptimaTargets`**：整体兜底，诊断代码本身永远不会成为启动失败的原因；新增「因配置未注入」清单，避免看到 `PASS` 却以为它在跑。

---

## 安装（重要：别用预览 jar）

**沙箱里的预览 jar 装进游戏必然无效**，它只是结构样本，没经过 Loom 重映射——Mojang 映射的类名解析不了混淆后的游戏。必须本地构建一次：

```bash
cd Project
gradle wrapper --gradle-version 8.12    # 若压缩包里没有 gradlew
./gradlew build
```

产物：`Project/build/libs/optima-1.0-beta4.jar`（需 JDK 21 + 首次联网约 1–2 GB）

放进 `mods/`，**然后删掉 `config/optima.properties`** 让它重新生成。

> 1.0.x 早期版本会把所有键写成生效值，旧文件会覆盖新预设。新生成的配置里具体键都是注释态，由 `profile` 统一决定——取消注释某一项即可覆盖。

---

## 三档预设

改 `config/optima.properties` 第一行：

```properties
profile=safe        # 默认
```

| 档位 | 开启内容 | 注入的 mixin | 适用 |
|---|---|---|---|
| **safe** | 启动期图形设置 + 物品剔除(32) + 2 个 tick 跳过 | 5 | **先从这个开始** |
| **balanced** | 上述 + 粒子限流(400/帧) | 6 | 有粒子风暴场景 |
| **aggressive** | 上述 + 方块实体剔除(64) + 实体剔除(96) + 远处 AI 降频 | 8 | **确认是 GPU 瓶颈时** |

单键覆盖（取消注释即可，活键永远赢过预设）：

```properties
profile=safe
render.block_entity_distance=64    # 其余仍按 safe
```

A/B 对比用 JVM 参数最快，不用反复改文件：

```
-Dprofile=safe -Drender.block_entity_distance=64
```

> JVM 参数（`-Dkey=value`）优先级高于文件，且 mixin 插件和入口点走的是同一套读取逻辑，两边看到的值必然一致。这一点很关键：如果插件看不到某个覆盖值，就会出现「配置说要剔除，但 mixin 没注入」的静默失效。

---

## 1.0.1 为什么会更卡（90→80 fps）

不是"力度不够"，是**代码本身就是负优化**。

90 fps 说明不是 GPU 瓶颈，瓶颈在 CPU。而 1.0.1 做的是：**每帧花 CPU 去省 GPU**。在 CPU-bound 机器上净亏损。

实测（每帧 4000 个方块实体 × 4000 帧）：

```
1.0.1 (ConcurrentHashMap+LongAdder) :  0.0310 ms/帧
1.0.2 统计关闭（默认）              :  0.0033 ms/帧   ← 9.5x
```

**但每帧只省 0.028 ms，而你掉了 1.4 ms。差 50 倍。** 所以统计不是主因，真正的大头是 mixin 注入本身：

- 每个方块实体每次渲染分配一个 `CallbackInfo` → **每秒 24 万个对象**，实打实 GC 压力
- 目标方法变复杂，**JIT 放弃内联**

结论：剔除类优化在 CPU-bound 时是亏本的，所以默认全部关闭——**而 1.0.3 让"关闭"真正意味着零成本**。

---

## 各优化项

### 启动期一次性设置（`OptimaClientBoost`）— 主力

强制快速图形、关云、关实体阴影等。**启动瞬间设置一次，热路径开销为零，GPU 收益持续整个会话**。

这是收益最大的部分，因为它完全避开了"每帧算"的陷阱。反射访问 `Options` 并尝试多个候选字段名，找不到就跳过——名字错了只丢一项，不崩。

### 掉落物剔除（`render.item_distance`，默认 32）

唯一默认开的剔除。掉落物通常**聚集在一处**（刷怪塔、炸开的箱子），一次判断省下大量完整光照旋转模型，性价比远高于分散的实体剔除。

设为 `0` 且 `render.entity_distance` 也为 `0` 时，整个实体剔除 mixin 不会被注入。

### 粒子限流（`render.particle_budget`）

限制每帧新粒子数，**已在场的不受影响**，现有特效不会突然消失。

1.0.1 用 `System.nanoTime()` 定义窗口——那是粒子风暴时每秒几千次系统调用，恰是最糟时刻。现在从 `ParticleEngine#tick()`（客户端每帧必调）重置计数，同样行为、零时钟读取。

### 生物 AI 降频（`tick.mob_ai_throttle`，默认关）

远处生物跳过大部分 AI tick。实体照常移动、受伤、燃烧、消失，只有决策降频。

**这不保真**——被降频的生物反应变慢。默认关，且**有活跃目标的生物永不被降频**，战斗不受影响。

### 内存监控（`MemoryWatchdog`）

周期性堆采样 + 报告 + 高位告警。**本身不省内存**——刻意不调 `System.gc()`、不背着游戏清缓存，那通常弊大于利。

健康堆是锯齿形（涨、GC 落、谷底持平）；**谷底持续抬高就是泄漏**。

真正省内存请装 **FerriteCore**——压缩调色板、缓存 BlockState、池化 BlockPos 这类必须对着反编译源码逐字段对齐，我拿不到源码，不会盲写。

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

### 核显 / 集显用户注意

如果你的显卡是**集成显卡**（如 Radeon 880M、Iris Xe），那 GPU 极可能是瓶颈——这与本文档"默认假设 CPU 瓶颈"相反。这种情况下：

- `aggressive` 档的剔除**大概率有正收益**，值得测
- 但收益远不如直接装 **Sodium**（免费、开源、专治渲染瓶颈）
- 同时把原版 **渲染距离** 调低，比任何模组剔除都直接

### 看日志确认生效

```bash
grep -E "Optima.*(PASS|FAIL|ACTIVE|Graphics boost|Not injected|NOT REMAPPED)" logs/latest.log
```

- `Graphics boost: N setting(s) applied`：启动期改了几项
- `Optimization ACTIVE: xxx`：注入代码真的执行了（需 `collect_stats=true`）
- `Not injected (off in config, so zero runtime cost): ...`：**这一版新增**。列出因配置关闭而从未注入的项，它们的运行时开销严格为零

**注意 `PASS` 不等于"在跑"**：`PASS` 只说明目标方法存在、可以注入。这一项在配置里是否开启，看上面那行 `Not injected`。

**最坏情况是某项静默失效，游戏照常启动。**

---

## 版本锁定

| 组件 | 值 |
|---|---|
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.4` |
| Fabric Loom | `1.14-SNAPSHOT` |
| Java | `21` |

1.21.11 是**最后一个混淆版本**（26.1 起不混淆），必须用带重映射的 Loom。

构建失败先试：`./gradlew build --refresh-dependencies`

---

## 说句实话

**如果你只是想要优化模组，装现成的更好**：Lithium（tick）、FerriteCore（内存）、Sodium（渲染）。

而且——**同一个方法最终只能有一份实现在跑**。如果我覆盖一个 Lithium 已经改过的方法，我的覆盖会把它的改写**整个抹掉**。装了 Lithium 再叠我的 tick 优化，很可能是负优化：我的实现不如它几千小时打磨的版本。

这个项目的价值在于**可控**（每项单独开关）和**可扩展**（框架搭好了，加优化只是加文件）。

1.0.3 把"可控"这件事做到了底：关掉一项，现在是真的关掉了。

---

## 许可证

MIT
