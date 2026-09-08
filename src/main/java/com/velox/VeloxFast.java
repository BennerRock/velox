package com.velox;


import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * 热路径静态快照 + 摄像机缓存。
 *
 * 热路径只读这里的静态字段，绝不回查 VeloxConfig.INSTANCE——热重载就是靠
 * applyProfile(profile, mode) 整体覆盖这些字段实现的：任意时刻切档（指令/界面/Auto
 * 调参）都立即写这里，mixin 下一次调用就是新模式。这就是「已缓存、已调度、已注册的
 * 部分同步切过去」的具体含义：
 * - 已缓存：粒子预算计数器、剔除距离平方、自适应值被重置为档位值；
 * - 已调度：Auto 调参器启停（VeloxAutoTuner）；
 * - 已注册：看门狗线程启停（MemoryWatchdog）。
 *
 * 字段全部 volatile：切档发生在客户端线程（指令/界面），而部分计数器可能被渲染线程
 * 之外触碰；volatile 保证新模式对下一次读取立即可见，代价可以忽略（非热路径写）。
 *
 * tick 组字段保留但 v1.1 起恒 false：相关 mixin 已在 VeloxMixinPlugin 里停用，
 * 字段只为保持旧代码可编译，不再有写入路径。
 *
 * 平方距离在这里预乘，渲染循环只比平方值，不再逐实体乘法。
 *
 * 为什么每个 Minecraft 查询都包 try：未 remap 的 jar 里类名不存在，首次查询会抛
 * NoClassDefFoundError。每个查询只试一次，失败就永久关掉对应功能并记日志，
 * 绝不让渲染循环崩掉。
 */
public final class VeloxFast {

    private VeloxFast() {
    }

    // ---- 模式状态（热重载可见）----
    /** 当前是否 vanilla 档（全部优化短路）。 */
    public static volatile boolean vanillaMode;
    /**
     * 帧心跳是否推进（ParticleEngineTickMixin 读取）。vanilla 档为 false：
     * 心跳停止，帧时钟与粒子计数冻结，开销归零；切回其它档立即恢复。
     */
    public static volatile boolean tickHeartbeat;

    // ---- tick（v1.1 永久禁用，恒 false，保留只为兼容旧 mixin 代码）----
    public static volatile boolean tickGoalSelectorEmpty = false;
    public static volatile boolean tickGoalSelectorIdle = false;
    public static volatile boolean tickMobAiThrottle = false;
    public static volatile double tickMobAiThrottleDistSq;
    public static volatile int tickMobAiThrottleInterval;

    // ---- render ----
    public static volatile boolean beCullEnabled;
    public static volatile double beCullDistSq;
    public static volatile double beCullBaseSq;

    public static volatile boolean entityCullEnabled;
    public static volatile double entityCullDistSq;
    public static volatile double entityCullBaseSq;

    public static volatile boolean itemCullEnabled;
    public static volatile double itemCullDistSq;
    public static volatile double itemCullBaseSq;

    /** 经验球单独一个半径：刷怪场和龙战会留下几百个。 */
    public static volatile boolean xpCullEnabled;
    public static volatile double xpCullDistSq;
    public static volatile double xpCullBaseSq;

    public static volatile boolean particleLimiterEnabled;
    public static volatile int particleBudget;

    /** 实体优化：每帧实体渲染数量上限（0 = 不限制，当前五档默认关闭）。 */
    public static volatile int entityRenderBudget;
    /**
     * 本帧已渲染的实体数，每帧由 beginFrame() 重置。
     *
     * <p>刻意<strong>不用 volatile</strong>：计数只在渲染线程内读写，而 volatile 的自增会
     * 带来内存屏障与缓存一致性流量。它位于「每个实体每帧」的热路径上，加 volatile 的代价
     * 会超过剔除省下的开销。可见性由单线程渲染保证。</p>
     */
    private static int entityRendered;

    /**
     * FPS 稳定器/Auto 档在运行期调的实时粒子上限，初始等于 particleBudget。
     * 限制器读的是它而不是静态配置值，这样运行期调整不需要 reload。
     */
    public static volatile int effectiveParticleBudget;

    // ---- stability ----
    public static volatile boolean fpsGovernor;
    public static volatile int targetFps;
    public static volatile boolean adaptiveParticles;
    public static volatile boolean adaptiveCulling;
    public static volatile boolean logStutters;
    public static volatile double stutterMs;

    /** 自适应剔除的下限（平方），见 adaptCulling。 */
    public static volatile double minCullDistSq;
    /** 两次自适应剔除调整之间的帧数。 */
    public static volatile int adaptInterval;
    private static int adaptCountdown;

    // ---- 诊断 ----
    /** 从 VeloxRuntime#collectStats 镜像过来；默认关，开着才会计数。 */
    public static volatile boolean collectStats;

    // =====================================================================
    // 档位热切换（【一】核心）
    // =====================================================================

    /**
     * 把一档参数铺到热路径上。任何切档来源（指令/界面）最终都走这里。
     * 同时重置运行期动态值，避免上一个档的残留影响新模式。
     */
    public static void applyProfile(VeloxConfig.Profile p, String mode) {
        boolean isVanilla = "vanilla".equals(mode);
        vanillaMode = isVanilla;
        tickHeartbeat = !isVanilla;

        // tick：v1.1 起永久禁用（玩法安全），无论档位一律 false。
        tickGoalSelectorEmpty = false;
        tickGoalSelectorIdle = false;
        tickMobAiThrottle = false;
        tickMobAiThrottleDistSq = 0;
        tickMobAiThrottleInterval = 0;

        // render
        beCullEnabled = p.renderBlockEntityDistance > 0;
        beCullDistSq = sq(p.renderBlockEntityDistance);
        beCullBaseSq = beCullDistSq;
        entityCullEnabled = p.renderEntityDistance > 0;
        entityCullDistSq = sq(p.renderEntityDistance);
        entityCullBaseSq = entityCullDistSq;
        itemCullEnabled = p.renderItemDistance > 0;
        itemCullDistSq = sq(p.renderItemDistance);
        itemCullBaseSq = itemCullDistSq;
        xpCullEnabled = p.renderExperienceOrbDistance > 0;
        xpCullDistSq = sq(p.renderExperienceOrbDistance);
        xpCullBaseSq = xpCullDistSq;
        particleLimiterEnabled = p.renderParticleBudget > 0;
        particleBudget = p.renderParticleBudget;
        effectiveParticleBudget = p.renderParticleBudget;
        // 实体优化：每帧渲染数量预算（0 = 不限）。
        entityRenderBudget = p.entityRenderBudget;

        // stability
        fpsGovernor = p.stabilityFpsGovernor;
        targetFps = Math.max(30, VeloxConfig.INSTANCE.targetFps);
        adaptiveParticles = p.stabilityAdaptiveParticles;
        adaptiveCulling = p.stabilityAdaptiveCulling;
        minCullDistSq = sq(8.0);
        adaptInterval = Math.max(1, p.stabilityAdaptInterval);
        adaptCountdown = 0;

        // 诊断
        collectStats = p.collectStats;
    }

    private static double sq(double v) {
        return v * v;
    }

    // =====================================================================
    // 帧时钟 + 粒子预算（每帧由 ParticleEngineTickMixin 调 onFrame）
    // =====================================================================

    public static volatile int frameId;

    /** 推进一帧：递增帧号、重置粒子预算计数、驱动自适应剔除倒计时。 */
    public static void onFrame() {
        if (!tickHeartbeat) {
            return;
        }
        frameId++;
        particleCounter = 0;
    }

    /**
     * 每帧开始：重置实体渲染计数（实体优化）。
     * 由 GameLoopMixin 在每帧 tick 前调用，保证预算按帧统计。
     */
    public static void beginFrame() {
        entityRendered = 0;
    }

    /**
     * 尝试占用一个本帧实体渲染名额（实体优化）。
     * 返回 false 表示本帧预算已满，调用方应跳过该实体的渲染。
     * 预算为 0（不限制）时恒返回 true，等同原版行为。
     */
    public static boolean tryConsumeEntitySlot() {
        int cap = entityRenderBudget;
        if (cap <= 0) {
            return true;
        }
        return entityRendered++ < cap;
    }

    private static int particleCounter;

    /** 尝试消费一个粒子名额。返回 false 表示这一帧预算已满，调用方应丢弃该粒子。 */
    public static boolean tryConsumeParticleBudget() {
        int cap = effectiveParticleBudget;
        if (cap <= 0) {
            return true;
        }
        return particleCounter++ < cap;
    }

    // =====================================================================
    // 自适应剔除（FPS 稳定器用，仅非 auto 档；auto 档交给 VeloxAutoTuner）
    // =====================================================================

    /**
     * 根据当前 FPS 在基准与下限之间插值收紧/放宽剔除距离。仅 FPS 稳定器（非 auto 档）
     * 调用；auto 档交给 VeloxAutoTuner。
     */
    public static void adaptCulling(double fps, double target) {
        if (!adaptiveCulling || !fpsGovernor) {
            return;
        }
        if (fps >= target * 1.05) {
            // 流畅：从当前值往基准放宽 10%
            beCullDistSq = Math.min(beCullBaseSq, beCullDistSq * 1.1);
            entityCullDistSq = Math.min(entityCullBaseSq, entityCullDistSq * 1.1);
            itemCullDistSq = Math.min(itemCullBaseSq, itemCullDistSq * 1.1);
            xpCullDistSq = Math.min(xpCullBaseSq, xpCullDistSq * 1.1);
        } else if (fps <= target * 0.9) {
            // 卡顿：向下限收缩 10%
            beCullDistSq = Math.max(minCullDistSq, beCullDistSq * 0.9);
            entityCullDistSq = Math.max(minCullDistSq, entityCullDistSq * 0.9);
            itemCullDistSq = Math.max(minCullDistSq, itemCullDistSq * 0.9);
            xpCullDistSq = Math.max(minCullDistSq, xpCullDistSq * 0.9);
        }
    }

    /** 给稳定器报告用的剔除状态快照。 */
    public static String cullingSnapshot() {
        return String.format("%.0f/%.0f/%.0f/%.0f (sq)",
                Math.sqrt(beCullDistSq), Math.sqrt(entityCullDistSq),
                Math.sqrt(itemCullDistSq), Math.sqrt(xpCullDistSq));
    }

    // =====================================================================
    // Auto 档运行期缩放（VeloxAutoTuner 调用；按比例围绕档位基准，不跨档跳变）
    // =====================================================================

    /** 按倍率缩放当前剔除距离（围绕档位基准，不跨档跳变）。 */
    public static void scaleCullDistances(double factor) {
        if (factor <= 0) {
            return;
        }
        double be = Math.max(4.0, Math.sqrt(beCullBaseSq) * factor);
        double en = Math.max(8.0, Math.sqrt(entityCullBaseSq) * factor);
        double it = Math.max(4.0, Math.sqrt(itemCullBaseSq) * factor);
        double xp = Math.max(4.0, Math.sqrt(xpCullBaseSq) * factor);
        beCullDistSq = be * be;
        entityCullDistSq = en * en;
        itemCullDistSq = it * it;
        xpCullDistSq = xp * xp;
        beCullEnabled = beCullBaseSq > 0;
        entityCullEnabled = entityCullBaseSq > 0;
        itemCullEnabled = itemCullBaseSq > 0;
        xpCullEnabled = xpCullBaseSq > 0;
    }

    /** 恢复当前档位的原始距离（Auto 收紧后放宽用）。 */
    public static void resetCullDistances() {
        beCullDistSq = beCullBaseSq;
        entityCullDistSq = entityCullBaseSq;
        itemCullDistSq = itemCullBaseSq;
        xpCullDistSq = xpCullBaseSq;
    }

    /** Auto 档运行期调整实时粒子上限（围绕档位预算，不跨档）。 */
    public static void setEffectiveParticleBudget(int budget) {
        if (budget <= 0) {
            return;
        }
        effectiveParticleBudget = Math.min(particleBudget, Math.max((int) (particleBudget * 0.25), budget));
    }

    /** 恢复档位原始粒子预算。 */
    public static void resetParticleBudget() {
        effectiveParticleBudget = particleBudget;
    }

    // =====================================================================
    // 摄像机缓存（剔除用）：新帧开始时刷新一次，剔除路径只读
    // =====================================================================

    private static double camX, camY, camZ;
    private static boolean camValid;

    private static int camFrame = -1;
    private static int camFallbackCountdown;
    private static final int CAM_FALLBACK_INTERVAL = 128;

    /** 一旦摄像机查询失败就闩上，绝不抛第二次。 */
    private static volatile boolean camBroken;

    public static double camX() { return camX; }
    public static double camY() { return camY; }
    public static double camZ() { return camZ; }
    public static boolean isCamValid() { return camValid; }

    /**
     * 新帧开始时刷新缓存的摄像机坐标。
     * 对剔除检查来说足够便宜：一帧内只有第一次调用会真的去读，其余全是 int 比较。
     */
    public static void tickCamera() {
        if (camBroken) {
            camValid = false;
            return;
        }
        if (camFrame == frameId && --camFallbackCountdown > 0) {
            return;
        }
        if (camFrame != frameId) {
            camFrame = frameId;
        }
        camFallbackCountdown = CAM_FALLBACK_INTERVAL;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                camValid = false;
                return;
            }
            Entity camera = mc.getCameraEntity();
            if (camera == null) {
                camValid = false;
                return;
            }
            camX = camera.getX();
            camY = camera.getY();
            camZ = camera.getZ();
            camValid = true;
        } catch (Throwable t) {
            // 几乎必然是未 remap 的 jar：编译期用的类名运行期不存在。闩掉而不是每帧抛。
            camBroken = true;
            camValid = false;
            Velox.LOGGER.error("[Velox] 摄像机查询失败 - 关闭距离剔除。请把 mcdev 映射切到 mojmap 后重编译。", t);
        }
    }
}
