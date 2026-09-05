package com.optima.mixin.tick;

import com.optima.OptimaFast;
import com.optima.OptimaRuntime;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips {@code GoalSelector#tick()} when the selector has no goals registered at all.
 *
 * <p>Every {@code Mob} owns two selectors and both are ticked on alternating ticks even when
 * one is completely empty - the normal case for the target selector of passive mobs, and for
 * any mob whose goals were never populated. Each of those ticks walks an empty set, allocates
 * an iterator and rebuilds control-flag bookkeeping for nothing.</p>
 *
 * <p>Zero goals means there is nothing to start, stop or tick, so the method is a provable
 * no-op and skipping it cannot change behaviour.</p>
 *
 * <p><strong>v2 hot-path change:</strong> the config is read from a static snapshot
 * ({@link OptimaFast}) instead of through {@code OptimaConfig.INSTANCE}, and the counter call
 * is guarded by a static boolean so that with diagnostics off the JIT removes it. v1 paid for
 * a map lookup on every call, which is part of why it lost frames.</p>
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void optima$skipEmptySelector(CallbackInfo ci) {
        if (!OptimaFast.tickGoalSelectorEmpty) {
            return;
        }

        GoalSelector self = (GoalSelector) (Object) this;
        if (self.getAvailableGoals().isEmpty()) {
            OptimaRuntime.onGoalSelectorSkipped(); // collapses to nothing when stats are off
            ci.cancel();
        }
    }
}
