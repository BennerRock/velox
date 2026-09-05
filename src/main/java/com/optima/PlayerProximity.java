package com.optima;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "how far is the nearest player" for a given entity.
 *
 * <h3>Why any of this is reflective</h3>
 * <p>The {@code Entity#level()} and {@code Level#players()} names could not be verified against
 * the 1.21.11 sources, so this class does not bet the game on them. It tries a list of candidate
 * names, and if none of them works it falls back to scanning for a method with the right
 * <em>shape</em>. Every caller treats {@link #UNKNOWN} as "do not restrict", so the worst case
 * is one disabled optimization, never a crash.</p>
 *
 * <h3>The shape scan is not paranoia, it is required</h3>
 * <p>Loom rewrites type references when it remaps the jar, but it leaves string constants
 * alone. In a real game the classes and methods carry {@code class_1542} / {@code method_1234}
 * names, so a lookup by {@code "players"} cannot ever succeed there - it would work only in the
 * dev environment and fail in production. Scanning by return type has no such dependency: it
 * looks for "a no-arg method returning a {@code Level}" and "a no-arg method returning a
 * {@code List} of {@code Player}", which is true under any mapping.</p>
 *
 * <p>Coordinates, by contrast, are called directly. {@code entity.getX()} is a compiled
 * reference, so it is remapped like everything else and needs no reflection at all - which is
 * both faster and correct under every mapping.</p>
 *
 * <h3>What 1.0.3 changed</h3>
 * <p>Coordinate reads used to go through six {@code getMethod} lookups per distance calculation
 * - a method-table scan plus a {@code Method} clone, for every mob against every player, every
 * tick. They are direct calls now. The scan also stops at the first player already inside the
 * threshold: the only caller compares against that same threshold, so an early exit gives it the
 * same answer, and a busy server stops costing more than a single-player world.</p>
 */
public final class PlayerProximity {

    /** Returned when the distance cannot be determined. Callers must not restrict anything. */
    public static final double UNKNOWN = -1.0D;

    private static final String[] LEVEL_ACCESSORS = {
            "level",        // mojmap, 1.18+
            "getLevel",
            "getWorld",     // yarn / older mojmap
            "world"
    };

    private static final String[] PLAYER_LIST_ACCESSORS = {
            "players",      // mojmap, 1.18+
            "getPlayers"
    };

    /** Resolved accessors, cached per entity class so unrelated hierarchies never collide. */
    private static final Map<Class<?>, Accessors> CACHE = new ConcurrentHashMap<>();
    /** Classes already proven unresolvable - do not retry, every attempt is wasted work. */
    private static final Map<Class<?>, Boolean> DEAD = new ConcurrentHashMap<>();

    private static volatile String lastResolvedDescription;

    private PlayerProximity() {
    }

    private static final class Accessors {
        final Method level;
        final Method playerList;

        Accessors(Method level, Method playerList) {
            this.level = level;
            this.playerList = playerList;
        }
    }

    /**
     * Squared distance from the given entity to the nearest player.
     *
     * @return distance squared, or {@link #UNKNOWN} if it could not be determined
     */
    public static double nearestPlayerDistanceSqr(Entity entity) {
        return nearestPlayerDistanceSqr(entity, -1.0D);
    }

    /**
     * Squared distance to the nearest player, stopping as soon as one is close enough.
     *
     * <p>The result is exact only up to the threshold: if any player is within
     * {@code thresholdSq}, some value {@code <= thresholdSq} is returned immediately, without
     * looking at the remaining players. Otherwise the true minimum is returned - every player
     * was further away than the threshold, so all of them had to be examined anyway.</p>
     *
     * @param thresholdSq squared distance considered "close", or a negative value to disable
     *                    the early exit and always compute the exact minimum
     * @return distance squared, or {@link #UNKNOWN} if it could not be determined
     */
    public static double nearestPlayerDistanceSqr(Entity entity, double thresholdSq) {
        if (entity == null) {
            return UNKNOWN;
        }

        Class<?> cls = entity.getClass();
        if (DEAD.containsKey(cls)) {
            return UNKNOWN;
        }

        Accessors acc = CACHE.get(cls);
        if (acc == null) {
            acc = resolve(cls, entity);
            if (acc == null) {
                DEAD.put(cls, Boolean.TRUE);
                Optima.LOGGER.warn("[Optima] No way to reach a player list from {}; "
                        + "distance-based optimizations stay OFF for that entity type.", cls.getName());
                return UNKNOWN;
            }
            CACHE.put(cls, acc);
        }

        try {
            Object rawLevel = acc.level.invoke(entity);
            if (!(rawLevel instanceof Level)) {
                return UNKNOWN;
            }
            // The list accessor was resolved against one particular Level class; if a
            // different implementation turns up, bail out instead of throwing.
            if (!acc.playerList.getDeclaringClass().isInstance(rawLevel)) {
                return UNKNOWN;
            }

            Object rawPlayers = acc.playerList.invoke(rawLevel);
            if (!(rawPlayers instanceof List)) {
                return UNKNOWN;
            }

            double ex = entity.getX();
            double ey = entity.getY();
            double ez = entity.getZ();

            double best = UNKNOWN;
            for (Object entry : (List<?>) rawPlayers) {
                if (!(entry instanceof Player)) {
                    // Not a player list after all - the shape scan guessed wrong.
                    return UNKNOWN;
                }
                Player p = (Player) entry;
                double dx = ex - p.getX();
                double dy = ey - p.getY();
                double dz = ez - p.getZ();
                double sq = dx * dx + dy * dy + dz * dz;

                // Early exit: "someone is close" is all the caller asked about.
                if (thresholdSq >= 0.0D && sq <= thresholdSq) {
                    return sq;
                }
                if (best < 0 || sq < best) {
                    best = sq;
                }
            }
            return best;
        } catch (Throwable t) {
            // Never let a reflection surprise escape onto the tick loop.
            DEAD.put(cls, Boolean.TRUE);
            Optima.LOGGER.warn("[Optima] Disabling distance optimizations for {}: {}", cls.getName(), t);
            return UNKNOWN;
        }
    }

    /** Try the known names first; scan by shape when they do not resolve. */
    private static Accessors resolve(Class<?> entityClass, Object sample) {
        Accessors byName = resolveByName(entityClass, sample);
        return byName != null ? byName : resolveByShape(entityClass, sample);
    }

    private static Accessors resolveByName(Class<?> entityClass, Object sample) {
        for (String levelName : LEVEL_ACCESSORS) {
            Method levelMethod = findPublicNoArg(entityClass, levelName);
            if (levelMethod == null) {
                continue;
            }

            Object level;
            try {
                level = levelMethod.invoke(sample);
            } catch (Throwable t) {
                continue;
            }
            if (level == null) {
                continue;
            }

            for (String listName : PLAYER_LIST_ACCESSORS) {
                Method listMethod = findPublicNoArg(level.getClass(), listName);
                if (listMethod == null) {
                    continue;
                }
                try {
                    Object res = listMethod.invoke(level);
                    if (res instanceof List && looksLikePlayerList((List<?>) res)) {
                        describe(entityClass, levelMethod, listMethod);
                        return new Accessors(levelMethod, listMethod);
                    }
                } catch (Throwable ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    /**
     * Last resort, and the normal path in a remapped jar: ignore names entirely and look for
     * "a no-arg method returning a Level" plus "a no-arg method returning a List of Players".
     */
    private static Accessors resolveByShape(Class<?> entityClass, Object sample) {
        for (Method levelMethod : publicNoArgMethods(entityClass)) {
            if (!Level.class.isAssignableFrom(levelMethod.getReturnType())) {
                continue;
            }

            Object level;
            try {
                level = levelMethod.invoke(sample);
            } catch (Throwable t) {
                continue;
            }
            if (!(level instanceof Level)) {
                continue;
            }

            for (Method listMethod : publicNoArgMethods(level.getClass())) {
                if (!List.class.isAssignableFrom(listMethod.getReturnType())) {
                    continue;
                }
                try {
                    Object res = listMethod.invoke(level);
                    if (res instanceof List && looksLikePlayerList((List<?>) res)) {
                        describe(entityClass, levelMethod, listMethod);
                        return new Accessors(levelMethod, listMethod);
                    }
                } catch (Throwable ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    private static void describe(Class<?> entityClass, Method levelMethod, Method listMethod) {
        lastResolvedDescription = entityClass.getSimpleName() + "#" + levelMethod.getName()
                + "() -> " + levelMethod.getReturnType().getSimpleName() + "#" + listMethod.getName() + "()";
        Optima.LOGGER.info("[Optima] Player proximity resolved for {}: {}",
                entityClass.getSimpleName(), lastResolvedDescription);
    }

    /**
     * An empty server has nothing to check against, so accept it - the loop will simply find
     * no players and return {@link #UNKNOWN} anyway. A non-empty list must contain players,
     * otherwise the shape scan has picked the wrong accessor.
     */
    private static boolean looksLikePlayerList(List<?> list) {
        return list.isEmpty() || list.get(0) instanceof Player;
    }

    private static Method findPublicNoArg(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // walk up the hierarchy
            }
        }
        return null;
    }

    /** Every public non-static no-arg method on the class and its superclasses. */
    private static List<Method> publicNoArgMethods(Class<?> cls) {
        List<Method> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() == 0
                        && !Modifier.isStatic(m.getModifiers())
                        && Modifier.isPublic(m.getModifiers())) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Called at startup so the resolution outcome is always visible in the log. */
    public static void logResolution() {
        if (lastResolvedDescription != null) {
            Optima.LOGGER.info("[Optima] Player proximity: RESOLVED via {}", lastResolvedDescription);
        } else if (!DEAD.isEmpty()) {
            Optima.LOGGER.warn("[Optima] Player proximity: UNRESOLVED - distance optimizations disabled.");
        } else {
            Optima.LOGGER.info("[Optima] Player proximity: not exercised yet (it resolves on first use).");
        }
    }
}
