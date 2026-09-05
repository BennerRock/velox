package com.velox;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Startup verification of every class and method the mixins target.
 *
 * <p>Velox is compiled against Mojang mappings and then remapped to intermediary by Loom, so
 * a target name that changed in a Minecraft update would not fail the build - it would fail at
 * runtime, silently. This check runs at mod initialisation and prints a per-target PASS/FAIL
 * line, so you can tell at a glance whether the mappings on your machine line up with the ones
 * this mod was written against.</p>
 *
 * <p>A FAIL here is not fatal: every mixin is registered as non-required, so the mod still
 * loads with that single optimization disabled.</p>
 *
 * <h3>Why these are class literals, not class names</h3>
 * <p>Until 1.0.3 this class held the targets as strings and looked them up with
 * {@code Class.forName}. That cannot work in a finished jar: Loom rewrites type references when
 * it remaps, but a string constant is left untouched, so every lookup asked for a Mojang name in
 * a game that only has {@code class_1542}. The check passed in the dev environment and reported
 * total failure - complete with the "THIS JAR WAS NOT REMAPPED" banner - in exactly the jar that
 * had been remapped correctly.</p>
 *
 * <p>Compiled references are rewritten with everything else, so they resolve under any mapping.
 * <strong>Nothing this mod needs at runtime may be looked up by name in a string.</strong></p>
 *
 * <h3>Method names are only checkable in a dev environment</h3>
 * <p>Type references survive remapping; method <em>names</em> do not - they become
 * {@code method_1234}. Checking for {@code "tick"} in a finished jar can therefore only produce
 * false alarms, so when the target class turns out to carry intermediary names the method check
 * is skipped and the line is reported on class linkage alone. Class names are read from the
 * classes themselves rather than from any pattern hardcoded here, so this adapts instead of
 * guessing.</p>
 *
 * <h3>This check is about mappings, not about configuration</h3>
 * <p>PASS means "the target exists and could be injected into". It says nothing about whether
 * the optimization is currently enabled - that decision is made earlier and separately by
 * {@code VeloxMixinPlugin}, and is reported by {@link #logNotApplied(VeloxConfig)}.</p>
 */
public final class VeloxTargets {

    /** What an intermediary class name looks like: {@code net.minecraft.class_1542}. */
    private static final Pattern INTERMEDIARY = Pattern.compile("\\bclass_\\d+\\b");

    private VeloxTargets() {
    }

    /** One mixin target: a class plus the members the mixin needs. */
    static final class Target {
        final String mixin;
        final Class<?> target;
        final String[] methodNames;

        Target(String mixin, Class<?> target, String... methodNames) {
            this.mixin = mixin;
            this.target = target;
            this.methodNames = methodNames;
        }
    }

    private static final List<Target> SERVER_TARGETS = new ArrayList<>();

    static {
        SERVER_TARGETS.add(new Target(
                "tick.GoalSelectorMixin",
                GoalSelector.class, "tick", "getAvailableGoals"));
        SERVER_TARGETS.add(new Target(
                "tick.GoalSelectorTickRunningMixin",
                GoalSelector.class, "tickRunningGoals"));
        SERVER_TARGETS.add(new Target(
                "tick (helper)",
                WrappedGoal.class, "isRunning"));
        SERVER_TARGETS.add(new Target(
                "tick.MobAiThrottleMixin",
                Mob.class, "serverAiStep", "getTarget"));
    }

    /**
     * The client targets live in {@link VeloxClientTargets}, a separate class that is loaded
     * reflectively and only on the client, so a dedicated server never links the client classes.
     *
     * <p>The count is duplicated here because asking {@code VeloxClientTargets} for its size
     * would load it - which is exactly what this arrangement exists to avoid. Keep it in step
     * with {@code VeloxClientTargets#get()}.</p>
     */
    private static final int CLIENT_TARGET_COUNT = 4;

    /** Name of our own class, so it is unaffected by remapping. */
    private static final String CLIENT_HOLDER = "com.velox.VeloxClientTargets";

    @SuppressWarnings("unchecked")
    private static List<Target> clientTargets() {
        try {
            Class<?> holder = Class.forName(CLIENT_HOLDER);
            return (List<Target>) holder.getMethod("get").invoke(null);
        } catch (Throwable t) {
            Velox.LOGGER.warn("[Velox] Could not load the client target list: {}", t);
            return List.of();
        }
    }

    /** Run the check and log the result. Never throws. */
    public static void runStartupCheck() {
        try {
            check();
        } catch (Throwable t) {
            // A target class failed to link. In a correctly built jar these are compiled
            // references that the remapper rewrites along with everything else, so it
            // cannot happen - which makes it the strongest signal available that this jar
            // was never remapped.
            Velox.LOGGER.error("[Velox] Target verification aborted: {}", t);
            logNotRemapped();
        }
    }

    /**
     * List the optimizations that are switched off and therefore were never injected.
     *
     * <p>Reading "PASS" next to a mixin that is doing nothing is confusing, so the two facts
     * are reported separately. This list is derived from the config rather than from what the
     * plugin happened to skip, so it is complete even for targets that have not been loaded
     * yet.</p>
     */
    public static void logNotApplied(VeloxConfig c) {
        try {
            List<String> off = new ArrayList<>();

            if (!c.tickGoalSelectorEmptyFastPath) {
                off.add("tick.goal_selector_empty_fast_path");
            }
            if (!c.tickGoalSelectorSkipWhenIdle) {
                off.add("tick.goal_selector_skip_when_idle");
            }
            if (!c.tickMobAiThrottle) {
                off.add("tick.mob_ai_throttle");
            }
            if (!(c.renderBlockEntityDistance > 0.0D)) {
                off.add("render.block_entity_distance");
            }
            if (!(c.renderEntityDistance > 0.0D || c.renderItemDistance > 0.0D)) {
                off.add("render.entity_distance");
            }
            if (!(c.renderParticleBudget > 0)) {
                off.add("render.particle_budget");
            }
            if (!anyBoost(c)) {
                off.add("boost.*");
            }

            if (off.isEmpty()) {
                Velox.LOGGER.info("[Velox] Every optimization is enabled - all mixins injected.");
            } else {
                Velox.LOGGER.info("[Velox] Not injected (off in config, so zero runtime cost): {}",
                        String.join(", ", off));
            }
        } catch (Throwable t) {
            Velox.LOGGER.debug("[Velox] Could not list disabled optimizations: {}", t);
        }
    }

    private static boolean anyBoost(VeloxConfig c) {
        return c.boostGraphicsMode
                || c.boostDisableClouds
                || c.boostDisableEntityShadows
                || c.boostMinimalParticles
                || c.boostFastAmbientOcclusion;
    }

    private static void check() {
        boolean client = FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

        Velox.LOGGER.info("[Velox] ---- target verification (mappings check) ----");

        List<Target> targets = new ArrayList<>(SERVER_TARGETS);
        int skip = 0;
        if (client) {
            targets.addAll(clientTargets());
        } else {
            skip = CLIENT_TARGET_COUNT;
        }

        int pass = 0;
        int fail = 0;

        for (Target t : targets) {
            Class<?> cls = t.target;
            String name = cls.getName();

            if (INTERMEDIARY.matcher(name).find()) {
                // Remapped jar: method names are intermediary too, so checking for "tick"
                // could only ever report a failure that is not real. Linkage is the result.
                pass++;
                Velox.LOGGER.info("[Velox] PASS  {} -> {} (remapped names, method check skipped)",
                        t.mixin, name);
                continue;
            }

            List<String> missing = new ArrayList<>();
            for (String methodName : t.methodNames) {
                if (!hasAnyMethodNamed(cls, methodName)) {
                    missing.add(methodName + "()");
                }
            }

            if (missing.isEmpty()) {
                pass++;
                Velox.LOGGER.info("[Velox] PASS  {} -> {}", t.mixin, name);
            } else {
                fail++;
                Velox.LOGGER.warn("[Velox] FAIL  {} -> {} is missing {}",
                        t.mixin, name, String.join(", ", missing));
            }
        }

        Velox.LOGGER.info("[Velox] ---- {} passed, {} failed, {} skipped (side) ----", pass, fail, skip);

        if (fail > 0) {
            Velox.LOGGER.warn(
                    "[Velox] Some targets did not resolve. Those optimizations are OFF; everything else still works. "
                            + "This usually means a method was renamed - search the log for 'FAIL' and update the mixin.");
        }
    }

    /**
     * Only reachable when a target class could not be linked at all, which for compiled
     * references means the remapper never ran.
     */
    private static void logNotRemapped() {
        Velox.LOGGER.error("[Velox] ==========================================================");
        Velox.LOGGER.error("[Velox] NO MIXIN TARGET RESOLVED - THIS JAR WAS NOT REMAPPED.");
        Velox.LOGGER.error("[Velox]");
        Velox.LOGGER.error("[Velox] Minecraft ships obfuscated. A mod compiled against Mojang");
        Velox.LOGGER.error("[Velox] names only works after Fabric Loom rewrites those names to");
        Velox.LOGGER.error("[Velox] intermediary. That rewrite happens at BUILD time.");
        Velox.LOGGER.error("[Velox]");
        Velox.LOGGER.error("[Velox] You are almost certainly running the sandbox preview jar.");
        Velox.LOGGER.error("[Velox] It is a structure sample and can never work in-game.");
        Velox.LOGGER.error("[Velox]");
        Velox.LOGGER.error("[Velox] Build the real jar instead:");
        Velox.LOGGER.error("[Velox]     cd Project");
        Velox.LOGGER.error("[Velox]     ./gradlew build");
        Velox.LOGGER.error("[Velox] -> Project/build/libs/velox-1.0.3.jar");
        Velox.LOGGER.error("[Velox] ==========================================================");
    }

    /** Whether the class (or any of its superclasses) declares a method with this name. */
    private static boolean hasAnyMethodNamed(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
