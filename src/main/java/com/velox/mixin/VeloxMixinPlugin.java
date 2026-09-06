package com.velox.mixin;

import com.velox.VeloxConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides, at class-load time, which mixins are allowed to exist at all.
 *
 * <h3>The problem this solves</h3>
 * <p>Until now a switched-off optimization was still <em>injected</em>: it simply returned on
 * its first line. That sounds free and it is not. A {@code cancellable} injector makes Mixin
 * allocate a {@code CallbackInfo} for <strong>every single call</strong>, and it makes the
 * target method bigger and harder to inline. So a cull that is disabled in the config still
 * cost an allocation per block entity per frame - several hundred thousand objects a second,
 * for a check that then did nothing.</p>
 *
 * <p>That is the same mistake v1 made with its statistics counters, one layer down: paying a
 * real, recurring cost for a feature that is turned off.</p>
 *
 * <h3>What this plugin does instead</h3>
 * <p>{@code shouldApplyMixin} is consulted before a mixin is merged into its target class.
 * Returning {@code false} means the mixin never exists: no injected code, no
 * {@code CallbackInfo}, no method growth, nothing for the JIT to trip over. The turned-off
 * optimization costs exactly zero, because there is no code left to run.</p>
 *
 * <h3>Failure policy: apply everything</h3>
 * <p>If anything at all goes wrong here - the config cannot be read, a mixin name is unknown -
 * the plugin returns {@code true}. That is the safe direction: the mod behaves exactly like
 * 1.0.2, where every mixin is applied and each one checks its own flag at runtime. A mistake
 * in this class can therefore only ever cost performance, never correctness.</p>
 *
 * <h3>Why the config is read again</h3>
 * <p>This runs while game classes are being transformed, long before {@code ModInitializer}
 * entry points fire, so it reads the file itself through
 * {@link VeloxConfig#readOnlyLoad()} - which never creates the file and never logs. The entry
 * point loads the config again later; both paths converge on the same values because
 * {@code apply} is idempotent.</p>
 */
public final class VeloxMixinPlugin implements IMixinConfigPlugin {

    private static final String PKG_TICK = "com.velox.mixin.tick.";
    private static final String PKG_CLIENT = "com.velox.mixin.client.";

    private volatile boolean configLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        // Intentionally empty: the config is read lazily on the first decision, when the
        // game directory and class loader are both definitely available.
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            return decide(mixinClassName);
        } catch (Throwable t) {
            // Never let a decision throw. Applying the mixin is the 1.0.2 behaviour, which
            // is always correct - just not always fast.
            return true;
        }
    }

    private boolean decide(String mixinClassName) {
        if (mixinClassName.startsWith(PKG_TICK)) {
            return decideTick(mixinClassName.substring(PKG_TICK.length()));
        }
        if (mixinClassName.startsWith(PKG_CLIENT)) {
            return decideClient(mixinClassName.substring(PKG_CLIENT.length()));
        }
        return true;
    }

    private boolean decideTick(String simpleName) {
        // v1.1 起：所有 tick 类游戏逻辑优化（含生物 AI 节流、目标选择器）永久禁用，
        // 以确保绝不改变原版玩法。相关 mixin 不再注入，零成本、零风险。
        return false;
    }

    private boolean decideClient(String simpleName) {
        // 客户端渲染/测量 mixin 始终注入：这样「运行时切档」才能即时生效
        // （mixin 无法卸载，靠注入点内部的运行时开关短路）。vanilla 档下这些
        // mixin 会在第一行判断后直接 return，开销仅为每帧一次非 cancellable 方法调用。
        return true;
    }

    private VeloxConfig config() {
        if (!configLoaded) {
            synchronized (this) {
                if (!configLoaded) {
                    try {
                        VeloxConfig.INSTANCE.readOnlyLoad();
                    } catch (Throwable ignored) {
                        // Field defaults (the safe preset) stand in.
                    }
                    configLoaded = true;
                }
            }
        }
        return VeloxConfig.INSTANCE;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }
}
