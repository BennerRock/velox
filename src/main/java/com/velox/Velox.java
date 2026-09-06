package com.velox;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Velox - 一个小型、防御性的 Fabric 优化模组，面向 Minecraft 1.21.11。
 *
 * 设计原则：一条优化若无法验证与原版等价，就绝不能改变玩法。具体表现为：
 * <ul>
 *   <li>每个 mixin 配置都登记为 {@code required: false}，每个注入点 {@code require = 0}，
 *       目标方法若在某次 MC 更新中改名，只会降级为一条日志警告，而非崩溃；</li>
 *   <li>v1.1 起所有会改变游戏逻辑的 tick 类优化（生物 AI 节流、目标选择器）永久禁用，
 *       mixin 不再注入，零成本、零风险；保留的优化全部是客户端渲染路径；</li>
 *   <li>每条优化都能在 config/velox.json 里切换档位即时生效，无需重进世界；</li>
 *   <li>没有任何优化改变可观测的玩法——只改变同一行为花多少代价。</li>
 * </ul>
 */
public class Velox implements ModInitializer {

    public static final String MOD_ID = "velox";
    public static final String VERSION = "1.1-beta1";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        VeloxConfig.INSTANCE.load();
        // 把当前档位铺到热路径、初始化稳定器、启停 Auto 调参器与内存看门狗，并立即写盘。
        VeloxConfig.INSTANCE.applyProfile();

        LOGGER.info("[Velox] v{} 启动 (mod id '{}')", VERSION, MOD_ID);
        logEnabledOptions();

        VeloxTargets.runStartupCheck();
        // 与 PASS/FAIL 块分开显示：一个目标解析得再完美也可能因为被关掉而什么都不做。
        VeloxTargets.logNotApplied(VeloxConfig.INSTANCE);

        LOGGER.info("[Velox] 就绪。未启用的优化不会被注入，零运行开销；"
                + "开启的优化请留意日志里的启用提示。");
    }

    private static void logEnabledOptions() {
        VeloxConfig c = VeloxConfig.INSTANCE;
        LOGGER.info("[Velox] 当前档位 = {}（{}）", c.mode, c.modeNameZh());
        LOGGER.info("[Velox] tick 类游戏逻辑优化：v1.1 起永久禁用（保证原版玩法一致）");
        LOGGER.info("[Velox] render.block_entity_distance        ={}", c.current().renderBlockEntityDistance);
        LOGGER.info("[Velox] render.entity_distance              ={}", c.current().renderEntityDistance);
        LOGGER.info("[Velox] render.item_distance                ={}", c.current().renderItemDistance);
        LOGGER.info("[Velox] render.experience_orb_distance      ={}", c.current().renderExperienceOrbDistance);
        LOGGER.info("[Velox] render.particle_budget             ={}", c.current().renderParticleBudget);
        LOGGER.info("[Velox] stability.fps_governor            ={} (目标 {} fps, 自适应粒子:{}, 自适应剔除:{})",
                c.current().stabilityFpsGovernor, c.targetFps,
                c.current().stabilityAdaptiveParticles, c.current().stabilityAdaptiveCulling);
        LOGGER.info("[Velox] memory.watchdog                   ={}", c.current().memoryWatchdog);
        LOGGER.info("[Velox] boost.*                           =graphics:{} clouds:{} shadows:{} particles:{} ao:{}",
                c.current().boostGraphicsMode, c.current().boostDisableClouds,
                c.current().boostDisableEntityShadows, c.current().boostMinimalParticles,
                c.current().boostFastAmbientOcclusion);
    }
}
