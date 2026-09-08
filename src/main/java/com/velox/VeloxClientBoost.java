package com.velox;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies aggressive graphics settings once, as soon as the client is ready.
 *
 * <p>This is deliberately the opposite approach to v1's per-frame culling. Every change here
 * happens a single time, so the hot path pays <strong>nothing</strong> - while the GPU saving
 * lasts for the whole session. No per-frame bookkeeping, no injection into a render loop, no
 * measurable CPU cost.</p>
 *
 * <h3>Timing (this is what v1.0.1 got wrong)</h3>
 * <p>Fabric's {@code client} entry point runs <em>inside</em> the Minecraft constructor, long
 * before the options object exists - so applying settings there silently did nothing. The
 * boost is now triggered from {@code MinecraftInitMixin}, which fires at the end of the
 * constructor, the first point where {@code Minecraft#options} is usable. It also means the
 * boost can only ever run in a correctly remapped jar, because the hook is a mixin: no
 * remap, no hook, no crash. That is the failure mode we want.</p>
 *
 * <h3>Why reflection</h3>
 * <p>The {@code Options} field names were not verified against 1.21.11, so this class never
 * assumes one. It walks a list of candidate names per setting, uses whichever it finds, and
 * silently skips anything it cannot resolve. A wrong name costs one disabled tweak - never a
 * crash, never a broken options screen.</p>
 *
 * <p>Once you have confirmed which names resolved (they are logged), the reflection can be
 * replaced with direct calls for a cleaner build.</p>
 */
public final class VeloxClientBoost {

    private static boolean applied;
    private static boolean failed;
    /** 记录 boost 改过的原版项及其改前值，用于切 vanilla 时恢复。 */
    private static final Map<String, Object> recorded = new HashMap<>();

    private VeloxClientBoost() {
    }

    /**
     * Apply every enabled boost. Idempotent, and safe to call from anywhere: the Minecraft
     * classes this touches only exist in a correctly remapped jar, so an unremapped one
     * simply logs an error and moves on instead of taking the game down.
     */
    public static void apply() {
        if (applied || failed) {
            return;
        }

        try {
            VeloxFollow.setSuppressed(true);
            applyInner();
            applied = true;
        } catch (Throwable t) {
            failed = true;
            Velox.LOGGER.error("[Velox] Graphics boost skipped - Minecraft classes unavailable. "
                    + "This normally means the jar was not remapped (see the 'not found' warnings "
                    + "from Mixin above). Build with ./gradlew build.", t);
        } finally {
            VeloxFollow.setSuppressed(false);
        }
    }

    /**
     * 切档后重新应用图形 boost（修复「切到 vanilla 再切回来，图形加速不回来」）。
     *
     * <p>apply() 用 applied/failed 做一次性闩锁，首次应用后便永久短路。切档时若直接调
     * apply()，非 vanilla 档的图形加速不会补回来，玩家会以为按钮点了没效果。这里先清闩
     * 再重跑，保证每次切档都真正落到原版选项上。</p>
     */
    public static void reapply() {
        applied = false;
        failed = false;
        apply();
    }

    private static void applyInner() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            Velox.LOGGER.warn("[Velox] Options not ready yet, graphics boost skipped this session.");
            failed = true;
            return;
        }

        Object options = mc.options;
        int count = 0;

        if (VeloxConfig.INSTANCE.current().boostGraphicsMode) {
            count += setEnum(options, new String[]{"graphicsMode", "graphics"},
                    new String[]{"FAST", "GraphicsStatus.FAST"});
        }
        if (VeloxConfig.INSTANCE.current().boostDisableClouds) {
            count += setEnum(options, new String[]{"cloudStatus", "clouds"},
                    new String[]{"OFF", "CloudStatus.OFF"});
        }
        if (VeloxConfig.INSTANCE.current().boostDisableEntityShadows) {
            count += setBoolean(options, new String[]{"entityShadows", "entityShadow"}, false);
        }
        if (VeloxConfig.INSTANCE.current().boostMinimalParticles) {
            count += setEnum(options, new String[]{"particles", "particlesStatus"},
                    new String[]{"MINIMAL", "ParticlesStatus.MINIMAL"});
        }
        if (VeloxConfig.INSTANCE.current().boostFastAmbientOcclusion) {
            count += setEnum(options, new String[]{"ambientOcclusion", "ao"},
                    new String[]{"MIN", "OFF"});
        }

        Velox.LOGGER.info("[Velox] Graphics boost: {} setting(s) applied "
                + "(one-time cost, zero per-frame overhead).", count);
    }

    /**
     * Set an option whose value is an enum constant.
     *
     * @return 1 if the value was changed, 0 otherwise
     */
    private static int setEnum(Object options, String[] fieldNames, String[] constantNames) {
        for (String fieldName : fieldNames) {
            Field f = findField(options.getClass(), fieldName);
            if (f == null) {
                continue;
            }
            try {
                Object holder = f.get(options);
                if (holder == null) {
                    continue;
                }

                // Minecraft wraps options in OptionInstance<T> with value()/set() accessors.
                Object current = tryInvoke(holder, "value");
                if (current == null) {
                    continue;
                }
                Class<?> enumType = current.getClass();
                if (!enumType.isEnum()) {
                    continue;
                }

                for (String wanted : constantNames) {
                    Object target = enumConstant(enumType, wanted);
                    if (target == null) {
                        continue;
                    }
                    if (target == current) {
                        return 0; // already set
                    }
                    if (tryInvoke(holder, "set", target) != null) {
                        recorded.putIfAbsent(fieldName, current);
                        Velox.LOGGER.info("[Velox]   {} -> {}", fieldName, wanted);
                        return 1;
                    }
                }
            } catch (Throwable t) {
                Velox.LOGGER.debug("[Velox] Could not set {}: {}", fieldName, t);
            }
        }
        Velox.LOGGER.warn("[Velox] Could not resolve option {} - skipped. "
                + "It can still be changed in the vanilla video settings.", fieldNames[0]);
        return 0;
    }

    /** Set a boolean-backed option. */
    private static int setBoolean(Object options, String[] fieldNames, boolean value) {
        for (String fieldName : fieldNames) {
            Field f = findField(options.getClass(), fieldName);
            if (f == null) {
                continue;
            }
            try {
                Object holder = f.get(options);
                if (holder == null) {
                    continue;
                }
                Object current = tryInvoke(holder, "value");
                if (current instanceof Boolean) {
                    if ((Boolean) current == value) {
                        return 0;
                    }
                    if (tryInvoke(holder, "set", value) != null) {
                        recorded.putIfAbsent(fieldName, current);
                        Velox.LOGGER.info("[Velox]   {} -> {}", fieldName, value);
                        return 1;
                    }
                }
            } catch (Throwable t) {
                Velox.LOGGER.debug("[Velox] Could not set {}: {}", fieldName, t);
            }
        }
        Velox.LOGGER.warn("[Velox] Could not resolve option {} - skipped.", fieldNames[0]);
        return 0;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Object tryInvoke(Object target, String name, Object... args) {
        try {
            if (args.length == 0) {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            }
            for (Method m : target.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                    m.invoke(target, args);
                    return Boolean.TRUE; // signal success
                }
            }
        } catch (Throwable ignored) {
            // handled by the caller
        }
        return null;
    }

    /** 切到 vanilla 档时调用：把 boost 改过的图形项恢复为改前值，并允许日后重新 boost。 */
    public static void restoreBoostedOptions() {
        if (recorded.isEmpty()) {
            applied = false;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        VeloxFollow.setSuppressed(true);
        try {
            Object options = mc.options;
            for (Map.Entry<String, Object> e : recorded.entrySet()) {
                setOptionValue(options, e.getKey(), e.getValue());
            }
        } finally {
            VeloxFollow.setSuppressed(false);
        }
        recorded.clear();
        applied = false;
    }

    private static void setOptionValue(Object options, String fieldName, Object value) {
        Field f = findField(options.getClass(), fieldName);
        if (f == null) {
            return;
        }
        try {
            Object holder = f.get(options);
            if (holder == null) {
                return;
            }
            if (tryInvoke(holder, "set", value) != null) {
                Velox.LOGGER.info("[Velox]   恢复 {} -> {}", fieldName, value);
            }
        } catch (Throwable t) {
            Velox.LOGGER.debug("[Velox] 恢复失败 {}: {}", fieldName, t);
        }
    }

    /** Resolve an enum constant by simple name or by "Type.NAME" form. */
    private static Object enumConstant(Class<?> enumType, String spec) {
        String simple = spec.contains(".") ? spec.substring(spec.lastIndexOf('.') + 1) : spec;
        for (Object c : enumType.getEnumConstants()) {
            if (((Enum<?>) c).name().equalsIgnoreCase(simple)) {
                return c;
            }
        }
        return null;
    }
}
