package com.optima;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Simple properties based configuration.
 *
 * <p>The file lives at {@code <game>/config/optima.properties} and is created with
 * commented defaults on first launch. Every key can also be overridden with a JVM
 * system property of the same name, which is handy for A/B benchmarking without
 * editing files.</p>
 *
 * <p>Config changes require a game restart to take effect, because the mixins that
 * read these flags are baked into Minecraft classes at load time.</p>
 */
public final class OptimaConfig {

    public static final OptimaConfig INSTANCE = new OptimaConfig();

    // ---------------- profile ----------------

    /**
     * Starting preset, applied before the individual keys below are read, so any key you
     * set explicitly still wins. One of {@code safe}, {@code balanced}, {@code aggressive}.
     *
     * <ul>
     *   <li>{@code safe} - only the optimizations that cost nothing per frame: the one-time
     *       startup graphics boost and the two provably-free tick skips. Start here.</li>
     *   <li>{@code balanced} - adds the culling with the best cost/benefit ratio (dropped
     *       items) and the particle limiter.</li>
     *   <li>{@code aggressive} - turns on every cull plus distant-mob AI throttling. Highest
     *       ceiling, but it changes how distant mobs behave and only pays off if your GPU,
     *       not your CPU, is the bottleneck.</li>
     * </ul>
     */
    /**
     * Starting preset. The 1.0-beta4 default is {@code aggressive}: you asked for the very best
     * frame rate, and aggressive turns on every cull plus distant-mob AI throttling. Switch back
     * to {@code balanced} or {@code safe} in the config if you ever feel a gameplay change.
     */
    public String profile = "aggressive";

    // ---------------- tick ----------------

    /** Skip {@code GoalSelector#tick()} entirely when the selector has no goals at all. */
    public boolean tickGoalSelectorEmptyFastPath = true;

    /** Skip {@code GoalSelector#tickRunningGoals(boolean)} when no goal is currently running. */
    public boolean tickGoalSelectorSkipWhenIdle = true;

    /**
     * Run mob AI less often for mobs that are far away from every player.
     * Off by default because it changes how quickly distant mobs react - see
     * {@code MobAiThrottleMixin} for the full trade-off.
     */
    public boolean tickMobAiThrottle = false;

    /** Mobs further than this (blocks) from every player become eligible for AI throttling. */
    public double tickMobAiThrottleDistance = 48.0D;

    /** A throttled mob runs its AI step once every N ticks. */
    public int tickMobAiThrottleInterval = 4;

    // ---------------- render ----------------

    /**
     * Distance in blocks beyond which block entities stop being rendered.
     * {@code 0} disables the cull.
     *
     * <p><strong>Default is 0 (off).</strong> The cull only pays off when the GPU is the
     * bottleneck. If the CPU is the bottleneck - which is the common case at 60+ fps - the
     * per-entity check costs more than the draw call it saves. v1 shipped this on by default
     * and measurably lost frames. Raise it only if you have evidence your GPU is the limit.</p>
     */
    public double renderBlockEntityDistance = 0.0D;

    /**
     * Distance in blocks beyond which entities stop being rendered.
     * {@code 0} disables the cull. Off by default for the same CPU-vs-GPU reason as
     * {@link #renderBlockEntityDistance}.
     */
    public double renderEntityDistance = 0.0D;

    /**
     * Separate, usually tighter, radius for dropped items only. Item entities are a
     * classic frame-rate sink because a farm or an exploded chest can leave thousands
     * on the ground, each a fully lit and rotated model. {@code 0} uses
     * {@link #renderEntityDistance} instead.
     */
    public double renderItemDistance = 32.0D;

    /**
     * Maximum number of NEW particles the engine may accept per 20 ms window
     * (roughly one frame at 50 FPS). Particles already alive are untouched, so
     * existing effects do not visibly pop - this only stops a storm from growing.
     * {@code 0} disables the limiter. Client side only, so purely visual.
     */
    public int renderParticleBudget = 0;  // 0 = limiter off

    // ---------------- diagnostics ----------------

    /**
     * Enable per-optimization counters. OFF by default: the counters are cheap but they are
     * not free, and in v1 the instrumentation measurably cost more than the optimizations
     * saved. Turn this on only while you are checking that something is actually working,
     * then turn it back off.
     */
    public boolean collectStats = false;

    // ---------------- client boost (one-time, zero per-frame cost) ----------------

    /** Force fast graphics (disables fancy leaves, transparent textures). Applied once at startup. */
    public boolean boostGraphicsMode = true;

    /** Force clouds off. Applied once at startup. */
    public boolean boostDisableClouds = true;

    /** Force entity shadows off. Applied once at startup. */
    public boolean boostDisableEntityShadows = true;

    /** Force minimal particles in the vanilla option, on top of the per-frame limiter. */
    public boolean boostMinimalParticles = false;

    /** Force the cheapest ambient occlusion level. Applied once at startup. */
    public boolean boostFastAmbientOcclusion = false;

    // ---------------- stability (frame-rate smoothing) ----------------

    /** Master switch for the FPS stabilizer: watch the real frame cadence and keep it steady. */
    public boolean stabilityFpsGovernor = true;

    /** Desired average FPS; the stabilizer intervenes when the smoothed rate sits below this. */
    public int stabilityTargetFps = 60;

    /** Shrink the particle budget while FPS is low, restore it once the frame rate recovers. */
    public boolean stabilityAdaptiveParticles = true;

    /** Log a warning the first time a frame exceeds the stutter threshold. */
    public boolean stabilityLogStutters = true;

    /** A frame longer than this (milliseconds) counts as a stutter. */
    public int stabilityStutterMs = 100;

    /**
     * Shrink entity / block-entity / item cull distances while FPS is low, and relax them back
     * once the frame rate recovers. Lives entirely in the render fast-path and never touches
     * game logic, so it cannot change behaviour.
     */
    public boolean stabilityAdaptiveCulling = true;

    /** Lower bound (blocks) the cull distance may be shrunk to while under load. */
    public double stabilityMinCullDistance = 48.0D;

    // ---------------- memory ----------------

    /** Sample heap usage on a daemon thread and log a periodic report. */
    public boolean memoryWatchdog = true;

    /** How often (seconds) the watchdog logs a heap report. */
    public int memoryWatchdogIntervalSeconds = 300;

    private OptimaConfig() {
    }

    /**
     * Read the config file, creating it with commented defaults if it does not exist yet.
     * Called from the main entry point.
     */
    public void load() {
        Path file = configFile();
        if (file == null) {
            Optima.LOGGER.warn("[Optima] No usable config directory - using built-in defaults.");
            return;
        }

        if (!Files.exists(file)) {
            writeDefault(file);
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            Optima.LOGGER.warn("[Optima] Could not read {}, falling back to defaults", file, e);
            return;
        }

        apply(props);
        Optima.LOGGER.info("[Optima] Config loaded from {}", file);
    }

    /**
     * Read the config file without ever creating it.
     *
     * <p>Used by {@code OptimaMixinPlugin}, which runs while game classes are still being
     * transformed. That is far too early to create files in the config directory, and far
     * too early to assume the logger exists. A missing file therefore simply leaves the
     * field defaults standing - which are exactly the {@code safe} preset.</p>
     *
     * <p>Must stay idempotent: the entry point loads the config again afterwards, and a
     * second pass must produce the same values as the first.</p>
     */
    public void readOnlyLoad() {
        Path file = configFile();
        if (file == null || !Files.exists(file)) {
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return;
        }

        apply(props);
    }

    /**
     * Apply a parsed properties table. Every value the preset owns is rewritten here, so
     * calling this twice with a different profile in between cannot leave stale values behind.
     */
    public void apply(Properties props) {
        this.profile = readString(props, "profile", this.profile);
        applyProfile(this.profile.trim().toLowerCase(java.util.Locale.ROOT));

        this.tickGoalSelectorEmptyFastPath =
                read(props, "tick.goal_selector_empty_fast_path", this.tickGoalSelectorEmptyFastPath);
        this.tickGoalSelectorSkipWhenIdle =
                read(props, "tick.goal_selector_skip_when_idle", this.tickGoalSelectorSkipWhenIdle);
        this.tickMobAiThrottle =
                read(props, "tick.mob_ai_throttle", this.tickMobAiThrottle);
        this.tickMobAiThrottleDistance =
                read(props, "tick.mob_ai_throttle_distance", this.tickMobAiThrottleDistance);
        this.tickMobAiThrottleInterval =
                read(props, "tick.mob_ai_throttle_interval", this.tickMobAiThrottleInterval);
        this.renderBlockEntityDistance =
                read(props, "render.block_entity_distance", this.renderBlockEntityDistance);
        this.renderEntityDistance =
                read(props, "render.entity_distance", this.renderEntityDistance);
        this.renderItemDistance =
                read(props, "render.item_distance", this.renderItemDistance);
        this.renderParticleBudget =
                read(props, "render.particle_budget", this.renderParticleBudget);
        this.collectStats =
                read(props, "collect_stats", this.collectStats);
        this.boostGraphicsMode =
                read(props, "boost.graphics_mode", this.boostGraphicsMode);
        this.boostDisableClouds =
                read(props, "boost.disable_clouds", this.boostDisableClouds);
        this.boostDisableEntityShadows =
                read(props, "boost.disable_entity_shadows", this.boostDisableEntityShadows);
        this.boostMinimalParticles =
                read(props, "boost.minimal_particles", this.boostMinimalParticles);
        this.boostFastAmbientOcclusion =
                read(props, "boost.fast_ambient_occlusion", this.boostFastAmbientOcclusion);
        this.stabilityFpsGovernor =
                read(props, "stability.fps_governor", this.stabilityFpsGovernor);
        this.stabilityTargetFps =
                read(props, "stability.target_fps", this.stabilityTargetFps);
        this.stabilityAdaptiveParticles =
                read(props, "stability.adaptive_particles", this.stabilityAdaptiveParticles);
        this.stabilityLogStutters =
                read(props, "stability.log_stutters", this.stabilityLogStutters);
        this.stabilityStutterMs =
                read(props, "stability.stutter_ms", this.stabilityStutterMs);
        this.stabilityAdaptiveCulling =
                read(props, "stability.adaptive_culling", this.stabilityAdaptiveCulling);
        this.stabilityMinCullDistance =
                read(props, "stability.min_cull_distance", this.stabilityMinCullDistance);
        this.memoryWatchdog =
                read(props, "memory.watchdog", this.memoryWatchdog);
        this.memoryWatchdogIntervalSeconds =
                read(props, "memory.watchdog_interval_seconds", this.memoryWatchdogIntervalSeconds);
    }

    /**
     * Apply a preset. Values here are the <em>base</em>; anything written explicitly in the
     * properties file is read afterwards and overwrites them, so the file always wins.
     *
     * <p>Every branch assigns <em>every</em> key the preset owns, including {@code safe}.
     * Leaving one branch empty would make the preset non-idempotent: switching to another
     * profile and back would keep the other profile's values, which is exactly what happens
     * now that the mixin plugin reads the config before the entry point does.</p>
     */
    private void applyProfile(String name) {
        switch (name) {
            case "balanced":
                renderItemDistance = 32.0D;
                renderBlockEntityDistance = 0.0D;
                renderEntityDistance = 0.0D;
                renderParticleBudget = 400;
                tickMobAiThrottle = false;
                tickMobAiThrottleDistance = 48.0D;
                tickMobAiThrottleInterval = 4;
                boostMinimalParticles = false;
                boostFastAmbientOcclusion = false;
                break;
            case "aggressive":
                renderItemDistance = 32.0D;
                renderBlockEntityDistance = 64.0D;
                renderEntityDistance = 96.0D;
                renderParticleBudget = 300;
                tickMobAiThrottle = true;
                tickMobAiThrottleDistance = 48.0D;
                tickMobAiThrottleInterval = 4;
                boostMinimalParticles = true;
                boostFastAmbientOcclusion = true;
                break;
            case "safe":
            default:
                renderItemDistance = 32.0D;
                renderBlockEntityDistance = 0.0D;
                renderEntityDistance = 0.0D;
                renderParticleBudget = 0;
                tickMobAiThrottle = false;
                tickMobAiThrottleDistance = 48.0D;
                tickMobAiThrottleInterval = 4;
                boostMinimalParticles = false;
                boostFastAmbientOcclusion = false;
                break;
        }
    }

    /**
     * Where the config file lives.
     *
     * <p>The mixin plugin asks for this while the loader may still be initialising, so a
     * failure falls back to a relative path rather than propagating. It may resolve to the
     * wrong directory in a rare launch layout, and the consequence is only that the plugin
     * sees defaults - never a crash.</p>
     */
    public static Path configFile() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("optima.properties");
        } catch (Throwable t) {
            try {
                return Path.of("config", "optima.properties");
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private void writeDefault(Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Optima configuration\n");
        sb.append("# Changes require a game restart.\n");
        sb.append("# Any key can also be passed as a JVM system property, e.g. -Drender.block_entity_distance=48\n");
        sb.append('\n');

        sb.append("# Pick a starting preset: safe | balanced | aggressive\n");
        sb.append("# It decides every value below. To override one, uncomment it - a live\n");
        sb.append("# key always beats the preset (that is why the defaults are commented out).\n");
        sb.append("profile=safe\n");
        sb.append('\n');

        sb.append("# --- TICK ---\n");
        comment(sb, "tick.goal_selector_empty_fast_path", tickGoalSelectorEmptyFastPath,
                "Skip ticking a GoalSelector that has no goals registered at all.");
        comment(sb, "tick.goal_selector_skip_when_idle", tickGoalSelectorSkipWhenIdle,
                "Skip ticking running goals when nothing is running.");
        sb.append("# CHANGES GAMEPLAY: distant mobs react slower. Measure before keeping it on.\n");
        comment(sb, "tick.mob_ai_throttle", tickMobAiThrottle,
                "Run mob AI less often for mobs far from every player.");
        comment(sb, "tick.mob_ai_throttle_distance", tickMobAiThrottleDistance,
                "Distance (blocks) past which mob AI may be throttled.");
        comment(sb, "tick.mob_ai_throttle_interval", tickMobAiThrottleInterval,
                "A throttled mob runs its AI once every N ticks.");

        sb.append("# --- RENDER (client only) ---\n");
        sb.append("# CPU-vs-GPU trade: off by default. Turn ON only if your GPU is the bottleneck.\n");
        comment(sb, "render.block_entity_distance", renderBlockEntityDistance,
                "Block entity render distance in blocks. 0 = vanilla (no extra culling).");
        comment(sb, "render.entity_distance", renderEntityDistance,
                "Entity render distance in blocks. 0 = vanilla (no extra culling).");
        comment(sb, "render.item_distance", renderItemDistance,
                "Dropped item render distance. Tighter than entity_distance is usually right.");
        comment(sb, "render.particle_budget", renderParticleBudget,
                "Max NEW particles accepted per 20ms window. 0 = unlimited.");

        sb.append("# --- CLIENT BOOST (applied once at startup, zero per-frame cost) ---\n");
        sb.append("# These change vanilla video settings. Change them back in the game menu if you dislike them.\n");
        comment(sb, "boost.graphics_mode", boostGraphicsMode,
                "Force fast graphics: no fancy leaves, no transparent textures.");
        comment(sb, "boost.disable_clouds", boostDisableClouds,
                "Force clouds off.");
        comment(sb, "boost.disable_entity_shadows", boostDisableEntityShadows,
                "Force entity shadows off.");
        comment(sb, "boost.minimal_particles", boostMinimalParticles,
                "Force the vanilla particle setting to Minimal.");
        comment(sb, "boost.fast_ambient_occlusion", boostFastAmbientOcclusion,
                "Force the cheapest ambient occlusion level.");
        sb.append('\n');

        sb.append("# --- DIAGNOSTICS ---\n");
        sb.append("# Leave this off during normal play. Counters are cheap, not free:\n");
        sb.append("# in v1 the instrumentation cost more than the optimizations saved.\n");
        comment(sb, "collect_stats", collectStats,
                "Count how often each optimization fires (logs on first hit).");

        sb.append("# --- STABILITY (frame-rate smoothing) ---\n");
        sb.append("# Keeps the frame rate steady under load by watching the real frame cadence\n");
        sb.append("# and - when the particle limiter is on - shrinking the particle budget while\n");
        sb.append("# FPS is low, then restoring it once the frame rate recovers.\n");
        comment(sb, "stability.fps_governor", stabilityFpsGovernor,
                "Master switch for the FPS stabilizer.");
        comment(sb, "stability.target_fps", stabilityTargetFps,
                "Desired average FPS; below this the stabilizer intervenes.");
        comment(sb, "stability.adaptive_particles", stabilityAdaptiveParticles,
                "Shrink the particle budget when FPS drops, restore when it recovers.");
        comment(sb, "stability.log_stutters", stabilityLogStutters,
                "Log a warning when a frame exceeds the stutter threshold.");
        comment(sb, "stability.stutter_ms", stabilityStutterMs,
                "A frame longer than this (ms) counts as a stutter.");
        comment(sb, "stability.adaptive_culling", stabilityAdaptiveCulling,
                "Shrink entity/block-entity cull distances when FPS is low, restore when it recovers.");
        comment(sb, "stability.min_cull_distance", stabilityMinCullDistance,
                "Cull distance (blocks) the stabilizer will not go below under load.");
        sb.append('\n');

        sb.append("# --- MEMORY ---\n");
        comment(sb, "memory.watchdog", memoryWatchdog,
                "Log periodic heap usage reports (diagnostics only).");
        comment(sb, "memory.watchdog_interval_seconds", memoryWatchdogIntervalSeconds,
                "Seconds between heap reports.");

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            Optima.LOGGER.warn("[Optima] Could not write default config to {}", file, e);
        }
    }

    /**
     * Writes a key in commented-out form: {@code #key=value}.
     *
     * <p>This is deliberate. If every key were written as a live value, the values in the
     * file would always override whatever {@code profile} selects, and the presets would
     * silently do nothing. Commented out, the profile wins until you uncomment a key - which
     * is the behaviour you want: pick a preset, then override only what you care about.</p>
     */
    private static void comment(StringBuilder sb, String key, Object def, String doc) {
        sb.append('#').append(' ').append(doc).append('\n');
        sb.append('#').append(key).append('=').append(def).append('\n');
        sb.append('\n');
    }

    // ---------------- generic readers ----------------

    private static boolean read(Properties props, String key, boolean def) {
        String sys = System.getProperty(key);
        if (sys != null) {
            return Boolean.parseBoolean(sys.trim());
        }
        String v = props.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int read(Properties props, String key, int def) {
        String raw = System.getProperty(key, props.getProperty(key));
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            Optima.LOGGER.warn("[Optima] Invalid int for {}: '{}', using {}", key, raw, def);
            return def;
        }
    }

    private static String readString(Properties props, String key, String def) {
        String sys = System.getProperty(key);
        if (sys != null) {
            return sys.trim();
        }
        return props.getProperty(key, def);
    }

    private static double read(Properties props, String key, double def) {
        String raw = System.getProperty(key, props.getProperty(key));
        if (raw == null) {
            return def;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            Optima.LOGGER.warn("[Optima] Invalid double for {}: '{}', using {}", key, raw, def);
            return def;
        }
    }
}
