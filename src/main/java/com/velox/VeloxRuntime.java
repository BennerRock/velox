package com.velox;

/**
 * Runtime counters for verifying that each optimization actually executed.
 *
 * <p><strong>This class was the single biggest performance bug in v1.</strong> The counters
 * used to live in a {@code ConcurrentHashMap} of {@code LongAdder}, which meant every culled
 * block entity - thousands per frame - paid for a hash lookup and a contended atomic add.
 * The instrumentation needed to prove an optimization worked was costing more than the
 * optimization saved, which is exactly how v1 managed to make the game slower.</p>
 *
 * <p>The rewrite removes that entirely:</p>
 * <ul>
 *   <li>counters are plain {@code long} fields - no atomics, no map;</li>
 *   <li>the first-time "is it active" flags are plain booleans;</li>
 *   <li>every call is guarded by {@link #collectStats}, which is <strong>off by
 *       default</strong>, so the JIT eliminates the whole block as dead code.</li>
 * </ul>
 *
 * <p>Counters may lose the occasional increment when several threads race; for diagnostics
 * that is irrelevant, and it is the price of having no synchronisation on the hot path.</p>
 */
public final class VeloxRuntime {

    /** Mirrored into {@link VeloxFast#collectStats}; off unless diagnostics are enabled. */
    public static volatile boolean collectStats = false;

    // ---- raw counters (plain longs on purpose) ----
    public static long beCulled;
    public static long entityCulled;
    public static long itemCulled;
    public static long particlesSuppressed;
    public static long goalSelectorSkipped;
    public static long mobAiThrottled;

    // ---- first-fire latches, so ACTIVE is logged once each ----
    private static boolean beCullSeen;
    private static boolean entityCullSeen;
    private static boolean itemCullSeen;
    private static boolean particleSeen;
    private static boolean goalSeen;
    private static boolean throttleSeen;

    private VeloxRuntime() {
    }

    // ---------------- block entities ----------------

    public static void onBlockEntityCulled() {
        if (!collectStats) {
            return;
        }
        beCulled++;
        if (!beCullSeen) {
            beCullSeen = true;
            Velox.LOGGER.info("[Velox] Optimization ACTIVE: render.block_entity_distance");
        }
    }

    // ---------------- entities ----------------

    public static void onEntityCulled() {
        if (!collectStats) {
            return;
        }
        entityCulled++;
        if (!entityCullSeen) {
            entityCullSeen = true;
            Velox.LOGGER.info("[Velox] Optimization ACTIVE: render.entity_distance");
        }
    }

    public static void onItemCulled() {
        if (!collectStats) {
            return;
        }
        itemCulled++;
        if (!itemCullSeen) {
            itemCullSeen = true;
            Velox.LOGGER.info("[Velox] Optimization ACTIVE: render.item_distance");
        }
    }

    // ---------------- particles ----------------

    public static void onParticleSuppressed() {
        if (!collectStats) {
            return;
        }
        particlesSuppressed++;
        if (!particleSeen) {
            particleSeen = true;
            Velox.LOGGER.info("[Velox] Optimization ACTIVE: render.particle_budget");
        }
    }

    // ---------------- ticking ----------------

    public static void onGoalSelectorSkipped() {
        if (!collectStats) {
            return;
        }
        goalSelectorSkipped++;
        if (!goalSeen) {
            goalSeen = true;
            Velox.LOGGER.info("[Velox] Optimization ACTIVE: tick.goal_selector");
        }
    }

    public static void onMobAiThrottled() {
        if (!collectStats) {
            return;
        }
        mobAiThrottled++;
        if (!throttleSeen) {
            throttleSeen = true;
            Velox.LOGGER.info("[Velox] Optimization ACTIVE: tick.mob_ai_throttle");
        }
    }

    /**
     * Snapshot of every counter as a human-readable block.
     * Only meaningful when {@code collect_stats=true}.
     */
    public static String report() {
        if (!collectStats) {
            return "diagnostics disabled (set collect_stats=true to enable counters)";
        }
        return "block entities culled=" + beCulled
                + ", entities culled=" + entityCulled
                + ", items culled=" + itemCulled
                + ", particles suppressed=" + particlesSuppressed
                + ", goal selectors skipped=" + goalSelectorSkipped
                + ", mob AI ticks throttled=" + mobAiThrottled;
    }
}
