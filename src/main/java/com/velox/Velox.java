package com.velox;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Velox - a small, defensive optimization mod for Minecraft 1.21.11 (Fabric).
 *
 * <p>Design rule for this project: an optimization that cannot be verified must never
 * be able to break the game. Concretely, that means</p>
 * <ul>
 *   <li>every mixin config is registered with {@code required: false}, and every
 *       injector with {@code require = 0}, so a target that moved in a Minecraft
 *       update degrades to a log warning instead of a crash;</li>
 *   <li>a switched-off optimization is not injected at all
 *       ({@code VeloxMixinPlugin}), so "off" means zero cost rather than
 *       "still running, but returning early";</li>
 *   <li>every entry point is re-checked at startup by {@link VeloxTargets};</li>
 *   <li>every optimization can be switched off from {@code config/velox.properties}
 *       without rebuilding; and</li>
 *   <li>no optimization changes observable gameplay - only how much work the same
 *       behaviour costs.</li>
 * </ul>
 */
public class Velox implements ModInitializer {

    public static final String MOD_ID = "velox";
    public static final String VERSION = "1.0-release";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        VeloxConfig.INSTANCE.load();
        VeloxFast.reload(VeloxConfig.INSTANCE);
        VeloxStabilizer.init();
        VeloxRuntime.collectStats = VeloxConfig.INSTANCE.collectStats;

        LOGGER.info("[Velox] v{} starting up (mod id '{}')", VERSION, MOD_ID);
        logEnabledOptions();

        VeloxTargets.runStartupCheck();
        // Shown separately from the PASS/FAIL block: a target can resolve perfectly and still
        // be doing nothing, because the option was switched off before the mixin was applied.
        VeloxTargets.logNotApplied(VeloxConfig.INSTANCE);

        // Only meaningful when a distance-based optimization is actually switched on.
        if (VeloxConfig.INSTANCE.tickMobAiThrottle) {
            PlayerProximity.logResolution();
        }

        MemoryWatchdog.start();

        LOGGER.info("[Velox] Ready. Options that are off were never injected, so they cost nothing; "
                + "watch for 'Optimization ACTIVE' lines to confirm the ones that are on.");
    }

    private static void logEnabledOptions() {
        VeloxConfig c = VeloxConfig.INSTANCE;
        LOGGER.info("[Velox] tick.goal_selector_empty_fast_path={}", c.tickGoalSelectorEmptyFastPath);
        LOGGER.info("[Velox] tick.goal_selector_skip_when_idle  ={}", c.tickGoalSelectorSkipWhenIdle);
        LOGGER.info("[Velox] tick.mob_ai_throttle               ={} (dist={}, every {} ticks)",
                c.tickMobAiThrottle, c.tickMobAiThrottleDistance, c.tickMobAiThrottleInterval);
        LOGGER.info("[Velox] render.block_entity_distance       ={}", c.renderBlockEntityDistance);
        LOGGER.info("[Velox] render.entity_distance             ={}", c.renderEntityDistance);
        LOGGER.info("[Velox] render.item_distance               ={}", c.renderItemDistance);
        LOGGER.info("[Velox] render.experience_orb_distance     ={}", c.renderExperienceOrbDistance);
        LOGGER.info("[Velox] render.particle_budget             ={}", c.renderParticleBudget);
        LOGGER.info("[Velox] memory.watchdog                    ={}", c.memoryWatchdog);
        LOGGER.info("[Velox] stability.fps_governor             ={} (target {} fps, adaptive particles: {}, adaptive culling: {})",
                c.stabilityFpsGovernor, c.stabilityTargetFps, c.stabilityAdaptiveParticles, c.stabilityAdaptiveCulling);
        LOGGER.info("[Velox] stability.min_cull_distance        ={} (blocks, lower bound under load)", c.stabilityMinCullDistance);
        LOGGER.info("[Velox] stability.adapt_interval           ={} frames (adaptive culling throttle)", c.stabilityAdaptInterval);
        LOGGER.info("[Velox] collect_stats                      ={} (keep off while playing)", c.collectStats);
        LOGGER.info("[Velox] client boost                       =graphics:{} clouds:{} shadows:{} particles:{}",
                c.boostGraphicsMode, c.boostDisableClouds,
                c.boostDisableEntityShadows, c.boostMinimalParticles);
    }
}
