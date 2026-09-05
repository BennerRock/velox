package com.optima;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optima - a small, defensive optimization mod for Minecraft 1.21.11 (Fabric).
 *
 * <p>Design rule for this project: an optimization that cannot be verified must never
 * be able to break the game. Concretely, that means</p>
 * <ul>
 *   <li>every mixin config is registered with {@code required: false}, and every
 *       injector with {@code require = 0}, so a target that moved in a Minecraft
 *       update degrades to a log warning instead of a crash;</li>
 *   <li>a switched-off optimization is not injected at all
 *       ({@code OptimaMixinPlugin}), so "off" means zero cost rather than
 *       "still running, but returning early";</li>
 *   <li>every entry point is re-checked at startup by {@link OptimaTargets};</li>
 *   <li>every optimization can be switched off from {@code config/optima.properties}
 *       without rebuilding; and</li>
 *   <li>no optimization changes observable gameplay - only how much work the same
 *       behaviour costs.</li>
 * </ul>
 */
public class Optima implements ModInitializer {

    public static final String MOD_ID = "optima";
    public static final String VERSION = "1.0-beta4";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        OptimaConfig.INSTANCE.load();
        OptimaFast.reload(OptimaConfig.INSTANCE);
        OptimaStabilizer.init();
        OptimaRuntime.collectStats = OptimaConfig.INSTANCE.collectStats;

        LOGGER.info("[Optima] v{} starting up (mod id '{}')", VERSION, MOD_ID);
        logEnabledOptions();

        OptimaTargets.runStartupCheck();
        // Shown separately from the PASS/FAIL block: a target can resolve perfectly and still
        // be doing nothing, because the option was switched off before the mixin was applied.
        OptimaTargets.logNotApplied(OptimaConfig.INSTANCE);

        // Only meaningful when a distance-based optimization is actually switched on.
        if (OptimaConfig.INSTANCE.tickMobAiThrottle) {
            PlayerProximity.logResolution();
        }

        MemoryWatchdog.start();

        LOGGER.info("[Optima] Ready. Options that are off were never injected, so they cost nothing; "
                + "watch for 'Optimization ACTIVE' lines to confirm the ones that are on.");
    }

    private static void logEnabledOptions() {
        OptimaConfig c = OptimaConfig.INSTANCE;
        LOGGER.info("[Optima] tick.goal_selector_empty_fast_path={}", c.tickGoalSelectorEmptyFastPath);
        LOGGER.info("[Optima] tick.goal_selector_skip_when_idle  ={}", c.tickGoalSelectorSkipWhenIdle);
        LOGGER.info("[Optima] tick.mob_ai_throttle               ={} (dist={}, every {} ticks)",
                c.tickMobAiThrottle, c.tickMobAiThrottleDistance, c.tickMobAiThrottleInterval);
        LOGGER.info("[Optima] render.block_entity_distance       ={}", c.renderBlockEntityDistance);
        LOGGER.info("[Optima] render.entity_distance             ={}", c.renderEntityDistance);
        LOGGER.info("[Optima] render.item_distance               ={}", c.renderItemDistance);
        LOGGER.info("[Optima] render.particle_budget             ={}", c.renderParticleBudget);
        LOGGER.info("[Optima] memory.watchdog                    ={}", c.memoryWatchdog);
        LOGGER.info("[Optima] stability.fps_governor             ={} (target {} fps, adaptive particles: {})",
                c.stabilityFpsGovernor, c.stabilityTargetFps, c.stabilityAdaptiveParticles);
        LOGGER.info("[Optima] collect_stats                      ={} (keep off while playing)", c.collectStats);
        LOGGER.info("[Optima] client boost                       =graphics:{} clouds:{} shadows:{} particles:{}",
                c.boostGraphicsMode, c.boostDisableClouds,
                c.boostDisableEntityShadows, c.boostMinimalParticles);
    }
}
