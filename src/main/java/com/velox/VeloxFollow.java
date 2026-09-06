package com.velox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 【五】原版设置跟随。
 *
 * <p>通过 OptionInstanceMixin 在玩家改动任意原版设置时回调 onOptionChanged，
 * 把被改过的项记到 VeloxConfig.vanillaFollow（仅展示、不反向覆盖玩家设置）。
 * 界面上的「恢复跟随档位」会调用 restoreFollowToProfile：把每个自定义项改回该档位的
 * 预设值（boost 项回到 boost 目标，其余回到原版默认），并清除自定义标记。</p>
 *
 * <p>Velox 自身改设置时（启动期 boost、恢复跟随）会临时关闭记录（setSuppressed），
 * 避免把 Velox 自己的改动误记为「玩家自定义」。</p>
 */
public final class VeloxFollow {

    private static final Map<OptionInstance<?>, String> REGISTRY = new IdentityHashMap<>();
    private static boolean armed;
    private static boolean suppress;

    private VeloxFollow() {
    }

    /** 客户端首次 tick 后调用一次：建立 OptionInstance → 设置名 的映射，进入记录状态。 */
    public static void arm() {
        if (armed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        buildRegistry(mc.options);
        armed = true;
        Velox.LOGGER.info("[Velox] 原版设置跟随已就绪。");
    }

    private static void buildRegistry(Options options) {
        REGISTRY.clear();
        for (Class<?> c = options.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (OptionInstance.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try {
                        Object inst = f.get(options);
                        if (inst instanceof OptionInstance) {
                            REGISTRY.put((OptionInstance<?>) inst, f.getName());
                        }
                    } catch (Throwable ignored) {
                        // 跳过不可读字段
                    }
                }
            }
        }
    }

    /** OptionInstanceMixin 在 set 时回调。 */
    public static void onOptionChanged(OptionInstance<?> instance, Object newValue) {
        if (!armed || suppress || instance == null) {
            return;
        }
        String name = REGISTRY.get(instance);
        if (name == null) {
            return; // 不是我们关心的设置（或映射还没建立）
        }
        VeloxConfig.VanillaFollowState f = VeloxConfig.INSTANCE.vanillaFollow;
        switch (name) {
            case "renderDistance":
                f.renderDistance = asInt(newValue); break;
            case "simulationDistance":
                f.simulationDistance = asInt(newValue); break;
            case "entityDistanceScaling":
                f.entityDistanceScaling = asDouble(newValue); break;
            case "particles":
                f.particles = asString(newValue); break;
            case "cloudStatus":
            case "clouds":
                f.clouds = asString(newValue); break;
            case "framerateLimit":
                f.framerateLimit = asInt(newValue); break;
            case "enableVsync":
            case "useVsync":
                f.useVsync = asBoolean(newValue); break;
            case "graphicsMode":
                f.graphicsMode = asString(newValue); break;
            case "entityShadows":
                f.entityShadows = asBoolean(newValue); break;
            default:
                return;
        }
        VeloxConfig.INSTANCE.save();
    }

    /** 点「恢复跟随档位」时调用：把自定义项改回档位预设，并清除标记。 */
    public static void restoreFollowToProfile() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            VeloxConfig.INSTANCE.vanillaFollow.clear();
            VeloxConfig.INSTANCE.save();
            return;
        }
        Options options = mc.options;
        VeloxConfig.Profile p = VeloxConfig.INSTANCE.current();

        suppress = true;
        try {
            VeloxConfig.VanillaFollowState f = VeloxConfig.INSTANCE.vanillaFollow;

            setPreset(options, "renderDistance", 12);
            setPreset(options, "simulationDistance", 10);
            setPreset(options, "entityDistanceScaling", 1.0);
            setPreset(options, "particles", p.boostMinimalParticles ? "MINIMAL" : "ALL");
            setPreset(options, "cloudStatus", p.boostDisableClouds ? "OFF" : "FANCY");
            setPreset(options, "clouds", p.boostDisableClouds ? "OFF" : "FANCY");
            setPreset(options, "framerateLimit", 120);
            setPreset(options, "enableVsync", true);
            setPreset(options, "useVsync", true);
            setPreset(options, "graphicsMode", p.boostGraphicsMode ? "FAST" : "FANCY");
            setPreset(options, "entityShadows", !p.boostDisableEntityShadows);

            f.clear();
            VeloxConfig.INSTANCE.save();
            Velox.LOGGER.info("[Velox] 已恢复原版设置跟随档位（{}）。", VeloxConfig.INSTANCE.mode);
        } finally {
            suppress = false;
        }
    }

    private static void setPreset(Options options, String fieldName, Object preset) {
        Field f = findField(options.getClass(), fieldName);
        if (f == null) {
            return;
        }
        try {
            Object holder = f.get(options);
            if (holder == null) {
                return;
            }
            Object current = tryInvoke(holder, "value");
            if (current == null) {
                return;
            }
            Object target;
            if (preset instanceof String s) {
                target = enumConstant(current.getClass(), s);
            } else {
                target = preset;
            }
            if (target == null) {
                return;
            }
            if (tryInvoke(holder, "set", target) != null) {
                Velox.LOGGER.info("[Velox]   跟随恢复 {} -> {}", fieldName, preset);
            }
        } catch (Throwable t) {
            Velox.LOGGER.debug("[Velox] 恢复跟随失败 {}: {}", fieldName, t);
        }
    }

    public static void setSuppressed(boolean v) {
        suppress = v;
    }

    // ---- 值转换 ----
    private static Integer asInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Boolean asBoolean(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    private static String asString(Object v) {
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        return v != null ? v.toString() : null;
    }

    // ---- 反射小工具 ----
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
                    return Boolean.TRUE;
                }
            }
        } catch (Throwable ignored) {
            // handled by caller
        }
        return null;
    }

    private static Object enumConstant(Class<?> enumType, String spec) {
        String simple = spec.contains(".") ? spec.substring(spec.lastIndexOf('.') + 1) : spec;
        if (!enumType.isEnum()) {
            return null;
        }
        for (Object c : enumType.getEnumConstants()) {
            if (((Enum<?>) c).name().equalsIgnoreCase(simple)) {
                return c;
            }
        }
        return null;
    }
}
