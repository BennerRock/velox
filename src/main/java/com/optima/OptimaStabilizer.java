package com.optima;

/**
 * Frame-rate stabilizer - the "stable FPS" feature at the heart of this release.
 *
 * <p>It is client-side and driven exactly once per frame by {@code GameLoopMixin}. Each call
 * records the frame time, keeps a short rolling window of them, and derives a smoothed average
 * FPS. When the particle limiter is enabled it can <em>shrink</em> the per-frame particle budget
 * while FPS is below target and <em>grow</em> it back once the frame rate recovers. The net
 * effect is a steadier frame rate under load: a particle storm is throttled first, so the rest
 * of the scene keeps its frames instead of every frame being dragged down together.</p>
 *
 * <p>This subsystem is pure measurement plus budget tuning - it never touches game logic, so it
 * can never change behaviour. And if the mixin never injects (a renamed method, an unremapped
 * jar) {@link #onFrame()} is simply never called, the whole thing stays dormant, and the game is
 * exactly as vanilla. That is the failure mode we want.</p>
 */
public final class OptimaStabilizer {

    /** Rolling window of recent frame times, in milliseconds. */
    private static final int WINDOW = 60;
    private static final double[] times = new double[WINDOW];
    private static int head;
    private static int filled;

    private static long lastNanos;
    /** Set true on the first real frame; lets us report whether the hook actually fired. */
    private static boolean injected;
    /** Smoothed FPS (exponential moving average of the instantaneous rate). */
    private static double emaFps;

    // stutter accounting
    private static int stutterCount;
    private static double worstFrameMs;

    // report cadence (~10s at 60fps)
    private static int framesSinceReport;

    private static boolean enabled;
    private static int targetFps;
    private static boolean adaptiveParticles;
    private static boolean logStutters;
    private static double stutterMs;

    private OptimaStabilizer() {
    }

    /** Called once at startup, after the config snapshot is reloaded. */
    public static void init() {
        enabled = OptimaFast.fpsGovernor;
        targetFps = OptimaFast.targetFps;
        adaptiveParticles = OptimaFast.adaptiveParticles;
        logStutters = OptimaFast.logStutters;
        stutterMs = OptimaFast.stutterMs;
        Optima.LOGGER.info("[Optima] FPS stabilizer: {}",
                enabled ? "enabled (target " + targetFps + " fps)" : "disabled");
    }

    /** Whether the per-frame hook has fired at least once this session. */
    public static boolean isActive() {
        return injected;
    }

    /** Called once per rendered frame by {@code GameLoopMixin}. */
    public static void onFrame() {
        injected = true;
        if (!enabled) {
            return;
        }

        long now = System.nanoTime();
        if (lastNanos > 0L) {
            double frameMs = (now - lastNanos) / 1_000_000.0D;
            // Ignore absurd gaps (tab-out, a debugger breakpoint) so they do not poison the average.
            if (frameMs > 0.0D && frameMs < 10_000.0D) {
                push(frameMs);

                if (frameMs > worstFrameMs) {
                    worstFrameMs = frameMs;
                }
                if (logStutters && frameMs >= stutterMs) {
                    if (stutterCount == 0) {
                        Optima.LOGGER.warn("[Optima] Frame-rate stutter detected ({} ms). "
                                + "The stabilizer will trim the particle budget to recover.",
                                Math.round(frameMs));
                    }
                    stutterCount++;
                }

                double instFps = 1000.0D / frameMs;
                emaFps = emaFps == 0.0D ? instFps : (emaFps * 0.9D + instFps * 0.1D);

                if (adaptiveParticles && OptimaFast.particleLimiterEnabled) {
                    adaptParticles();
                }
            }
        }
        lastNanos = now;

        if (++framesSinceReport >= 600) {
            framesSinceReport = 0;
            report();
        }
    }

    private static void push(double ms) {
        times[head] = ms;
        head = (head + 1) % WINDOW;
        if (filled < WINDOW) {
            filled++;
        }
    }

    private static double avgFrameMs() {
        if (filled == 0) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int i = 0; i < filled; i++) {
            sum += times[i];
        }
        return sum / filled;
    }

    /**
     * Shrink the effective particle budget when the smoothed FPS sits well under target, and grow
     * it back as soon as the frame rate recovers. The cap is held inside
     * {@code [floor, configured]} where {@code floor} is a quarter of the configured budget, so a
     * storm is tamed without ever starving a healthy scene.
     */
    private static void adaptParticles() {
        double cap = OptimaFast.particleBudget;
        if (cap <= 0) {
            return;
        }
        double floor = Math.max(1.0D, cap * 0.25D);

        double avgMs = avgFrameMs();
        if (avgMs <= 0.0D) {
            return;
        }
        double avgFps = 1000.0D / avgMs;

        int current = OptimaFast.effectiveParticleBudget;
        if (avgFps < targetFps * 0.9D) {
            OptimaFast.effectiveParticleBudget = (int) Math.max(floor, current * 0.75D);
        } else if (avgFps > targetFps * 1.05D) {
            OptimaFast.effectiveParticleBudget =
                    (int) Math.min(cap, current + Math.max(1, cap / 20.0D));
        }
    }

    private static void report() {
        double avgMs = avgFrameMs();
        double avgFps = avgMs > 0 ? 1000.0D / avgMs : 0.0D;
        Object cap = OptimaFast.particleLimiterEnabled ? OptimaFast.effectiveParticleBudget : "off";
        Optima.LOGGER.info("[Optima] FPS stabilizer: avg {}/{} fps, worst-frame {} ms, "
                + "stutters>{}ms: {}, particle cap: {}",
                Math.round(avgFps), targetFps, Math.round(worstFrameMs * 10.0D) / 10.0D,
                (int) stutterMs, stutterCount, cap);
        worstFrameMs = 0.0D;
    }
}
