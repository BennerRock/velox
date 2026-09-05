package com.optima;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Hot-path snapshot of the configuration, plus a per-frame camera cache.
 *
 * <p>v1 read settings straight off {@code OptimaConfig.INSTANCE} inside the render loop.
 * That is two field hops executed thousands of times per frame, and on a CPU-bound machine
 * it cost more than the render work it was trying to skip. This class exists so the hot
 * path never does an instance lookup: every value is a plain static field, read once at
 * startup and reloaded only when the config changes.</p>
 *
 * <p>Squared distances are precomputed here too - the render loop only ever needs the
 * squared form for its comparison, so there is no reason to multiply per entity.</p>
 *
 * <h3>Why every Minecraft lookup is wrapped</h3>
 * <p>Optima was written without the 1.21.11 sources, so the class it asks for by name only
 * exists if the jar was remapped by Fabric Loom. If it was not, the first lookup throws
 * {@code NoClassDefFoundError} - and in v1 that propagated out of the client entry point and
 * crashed the game. A mod that cannot resolve its targets must go quiet, not take the game
 * down with it.</p>
 *
 * <p>So each lookup is attempted once. On failure the feature is switched off permanently and
 * the reason is logged: throwing repeatedly would be far worse than the problem it reports.</p>
 */
public final class OptimaFast {

    // ---- tick ----
    public static boolean tickGoalSelectorEmpty;
    public static boolean tickGoalSelectorIdle;
    public static boolean tickMobAiThrottle;
    public static double tickMobAiThrottleDistSq;
    public static int tickMobAiThrottleInterval;

    // ---- render ----
    public static boolean beCullEnabled;
    public static double beCullDistSq;

    public static boolean entityCullEnabled;
    public static double entityCullDistSq;

    public static boolean itemCullEnabled;
    public static double itemCullDistSq;

    public static boolean particleLimiterEnabled;
    public static int particleBudget;
    /**
     * Runtime particle cap the FPS stabilizer tunes; starts equal to {@link #particleBudget} and
     * is only ever shrunk/grown while {@code stability.adaptive_particles} is on. The limiter
     * reads this, not the static config value, so the stabilizer can react without a reload.
     */
    public static int effectiveParticleBudget;

    // ---- stability (FPS governor) ----
    public static boolean fpsGovernor;
    public static int targetFps;
    public static boolean adaptiveParticles;
    public static boolean logStutters;
    public static int stutterMs;

    /** New particles accepted since the last frame heartbeat. Reset by {@link #onFrame()}. */
    private static int particlesThisFrame;

    /**
     * Safety net for the limiter. If the per-frame heartbeat never fires - which is what a
     * renamed {@code ParticleEngine#tick} looks like - the budget would fill up once and then
     * block every new particle for the rest of the session. This is the fallback window, in
     * calls, for that case.
     */
    private static final int PARTICLE_FALLBACK_WINDOW = 4096;

    private static int particleResetFrame = -1;
    private static int particleCallsSinceReset;

    // ---- diagnostics ----
    public static boolean collectStats;

    private OptimaFast() {
    }

    /** Copy the config into the static fields. Called at startup and after a reload. */
    public static void reload(OptimaConfig c) {
        tickGoalSelectorEmpty = c.tickGoalSelectorEmptyFastPath;
        tickGoalSelectorIdle = c.tickGoalSelectorSkipWhenIdle;
        tickMobAiThrottle = c.tickMobAiThrottle;
        tickMobAiThrottleDistSq = sq(c.tickMobAiThrottleDistance);
        tickMobAiThrottleInterval = Math.max(2, c.tickMobAiThrottleInterval);

        beCullEnabled = c.renderBlockEntityDistance > 0.0D;
        beCullDistSq = sq(c.renderBlockEntityDistance);

        entityCullEnabled = c.renderEntityDistance > 0.0D;
        entityCullDistSq = sq(c.renderEntityDistance);

        itemCullEnabled = c.renderItemDistance > 0.0D;
        itemCullDistSq = sq(c.renderItemDistance);

        particleLimiterEnabled = c.renderParticleBudget > 0;
        particleBudget = c.renderParticleBudget;
        effectiveParticleBudget = particleBudget;

        fpsGovernor = c.stabilityFpsGovernor;
        targetFps = Math.max(10, c.stabilityTargetFps);
        adaptiveParticles = c.stabilityAdaptiveParticles;
        logStutters = c.stabilityLogStutters;
        stutterMs = Math.max(8, c.stabilityStutterMs);

        collectStats = c.collectStats;
    }

    private static double sq(double v) {
        return v * v;
    }

    // ------------------------------------------------------------------
    // Frame clock
    //
    // Everything that only needs to happen once per frame hangs off this counter:
    // the particle budget reset and the camera refresh. It is advanced from
    // ParticleEngine#tick(), which the client calls exactly once per frame before
    // particles are updated and drawn.
    //
    // The counter is a plain int on purpose. Nothing here needs to be exact - if a
    // frame is missed the next one simply carries on from the new number.
    // ------------------------------------------------------------------

    private static int frameId;

    /** Advance the frame clock and reset everything that is budgeted per frame. */
    public static void onFrame() {
        frameId++;
        particlesThisFrame = 0;
    }

    /**
     * Spend one unit of the per-frame particle budget.
     *
     * @return {@code true} if the particle may spawn, {@code false} if the budget is spent
     */
    public static boolean tryConsumeParticleBudget() {
        if (frameId != particleResetFrame) {
            // Normal case: a new frame has started since the last call.
            particleResetFrame = frameId;
            particlesThisFrame = 0;
            particleCallsSinceReset = 0;
        } else if (++particleCallsSinceReset >= PARTICLE_FALLBACK_WINDOW) {
            // No heartbeat has arrived for a long time. The budget must never latch shut, so
            // fall back to counting calls. The limiter becomes looser, but it keeps working.
            particlesThisFrame = 0;
            particleCallsSinceReset = 0;
        }

        if (effectiveParticleBudget <= 0) {
            return true; // limiter off
        }
        if (particlesThisFrame >= effectiveParticleBudget) {
            return false;
        }
        particlesThisFrame++;
        return true;
    }

    // ------------------------------------------------------------------
    // Camera cache
    //
    // The camera does not move between two consecutive culling checks inside the same
    // frame, so re-reading it for every block entity is pure waste. In 1.0.2 the cache
    // was refreshed every 64 calls, which in a base with a few thousand block entities
    // still meant dozens of lookups per frame. It is now refreshed once per frame.
    // ------------------------------------------------------------------

    /**
     * Fallback interval, used only if the per-frame heartbeat never fires (which would mean
     * the ParticleEngine mixin did not apply). It restores the 1.0.2 behaviour rather than
     * leaving the camera frozen for the whole session.
     */
    private static final int CAM_FALLBACK_INTERVAL = 64;

    private static double camX;
    private static double camY;
    private static double camZ;
    private static boolean camValid;

    private static int camFrame = -1;
    private static int camFallbackCountdown;

    /** Latched once the camera lookup fails, so we never throw a second time. */
    private static volatile boolean camBroken;

    public static double camX() {
        return camX;
    }

    public static double camY() {
        return camY;
    }

    public static double camZ() {
        return camZ;
    }

    public static boolean isCamValid() {
        return camValid;
    }

    /**
     * Refresh the cached camera position when a new frame has started.
     *
     * <p>Cheap enough to call on every culling check: it is a single int comparison on every
     * call after the first one in a frame.</p>
     */
    public static void tickCamera() {
        if (camBroken) {
            camValid = false;
            return;
        }

        boolean newFrame = camFrame != frameId;
        if (!newFrame && --camFallbackCountdown > 0) {
            return;
        }

        if (newFrame) {
            camFrame = frameId;
        }
        // Always re-armed, so a session without the frame heartbeat degrades to the old
        // every-N-calls schedule instead of never refreshing at all.
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
            // Almost always an unremapped jar: the class name we compiled against does
            // not exist at runtime. Latch it off rather than throwing every frame.
            camBroken = true;
            camValid = false;
            Optima.LOGGER.error("[Optima] Camera lookup failed - disabling distance culling. "
                    + "If the log also shows '@Mixin target ... was not found', your jar was never "
                    + "remapped: build it with ./gradlew build instead of using the preview jar.", t);
        }
    }

    /**
     * Force a refresh on the next call. Used by the once-per-frame hook so the camera is
     * current before a render pass starts.
     */
    public static void invalidateCamera() {
        camFrame = -1;
        camFallbackCountdown = 0;
    }
}
