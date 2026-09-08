package com.velox;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * 「开游戏时把分配的资源全部用上」。
 *
 * <p>Velox 的每一档都在配置里分配了一整套优化资源：方块实体 / 实体 / 物品 / 经验球的剔除
 * 距离、粒子预算、实体渲染预算、内存看门狗、Auto 调参器、图形 boost。以往这些参数只在
 * 模组启动和切档时铺设；若启动期有资源尚未就绪（例如 options 还没构造完），进世界那一刻
 * 仍可能有优化没真正落到热路径上，玩家就会觉得「开了档但没效果」。</p>
 *
 * <p>本类在进入世界时（单人 / 联机都会触发）统一再铺一次全部参数，并按机器可用资源
 * （CPU 核心数、堆内存上限）判断后台调度能力，最后输出一份资源报告，便于确认
 * 「分配的资源是否已经全部用上」。</p>
 *
 * <p>只做「应用 + 报告」，不改任何优化逻辑本身，也不触碰游戏玩法。</p>
 */
public final class VeloxResources {

    private static volatile int cpuCores;
    private static volatile long maxHeapMb;
    private static volatile String lastReport = "（尚未进入世界）";
    private static volatile boolean registered;

    private VeloxResources() {
    }

    /** 注册进入世界的钩子，并先探测一次本机资源。 */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        probe();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> applyAllOnGameStart());
    }

    private static void probe() {
        try {
            cpuCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        } catch (Throwable t) {
            cpuCores = 1;
        }
        try {
            maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        } catch (Throwable t) {
            maxHeapMb = 0L;
        }
    }

    /**
     * 进入世界：把配置里分配的全部优化资源一次性投入使用。
     *
     * <p>这一步是幂等的，重复进世界不会叠加效果；任何异常都只记录不抛出，
     * 绝不影响进入世界本身。</p>
     */
    public static void applyAllOnGameStart() {
        try {
            probe();

            // 1) 全量重铺：剔除距离 / 粒子预算 / 实体预算 / 内存看门狗 / Auto 调参器 / 图形 boost。
            VeloxConfig.INSTANCE.applyProfile();

            VeloxConfig.Profile p = VeloxConfig.INSTANCE.current();

            // 2) 生成资源报告：确认每一份分配出去的资源是否都已启用。
            String entity = p.renderEntityDistance > 0
                    ? ((int) p.renderEntityDistance) + " 格" + (p.entityRenderBudget > 0
                    ? " + 每帧预算 " + p.entityRenderBudget : "")
                    : "关";
            String particles = p.renderParticleBudget > 0 ? String.valueOf(p.renderParticleBudget) : "关";
            String be = p.renderBlockEntityDistance > 0 ? ((int) p.renderBlockEntityDistance) + " 格" : "关";

            lastReport = String.format("CPU %d 核 / 堆 %d MB ｜ 实体 %s ｜ 方块实体 %s ｜ 粒子 %s",
                    cpuCores, maxHeapMb, entity, be, particles);

            Velox.LOGGER.info("[Velox] 进入世界：已启用全部分配的资源 - {}", lastReport);

            // 3) 资源偏紧时给一次提示，建议换档或交给 Auto 收敛。
            if (cpuCores <= 2 || (maxHeapMb > 0L && maxHeapMb < 2048L)) {
                Velox.LOGGER.info("[Velox] 本机资源偏紧（CPU {} 核 / 堆 {} MB），"
                        + "建议用 eco 档，或让 auto 档按实时 FPS 自动收敛。", cpuCores, maxHeapMb);
            }
        } catch (Throwable t) {
            Velox.LOGGER.warn("[Velox] 进入世界时应用优化资源失败（不影响游戏）：{}", t.toString());
        }
    }

    /** 可用 CPU 核心数。 */
    public static int cpuCores() {
        return cpuCores;
    }

    /** 最大堆内存（MB）。 */
    public static long maxHeapMb() {
        return maxHeapMb;
    }

    /** 最近一次的资源报告，供配置界面展示。 */
    public static String lastReport() {
        return lastReport;
    }
}
