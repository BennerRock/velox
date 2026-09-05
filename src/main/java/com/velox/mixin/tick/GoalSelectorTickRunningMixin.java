package com.velox.mixin.tick;

import com.velox.VeloxFast;
import com.velox.VeloxRuntime;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips {@code GoalSelector#tickRunningGoals(boolean)} while nothing is running.
 *
 * <p>Covers the case the companion mixin does not: a selector that has goals, but where none
 * is currently active - the steady state for most idle mobs. Vanilla rebuilds and iterates
 * its running-goal bookkeeping on every call regardless.</p>
 *
 * <p>Detecting "nothing is running" costs one pass over the goal set and allocates nothing,
 * which is strictly cheaper than the work being skipped.</p>
 *
 * <p>This is the one optimization in the tick group whose check is not free, so it is worth
 * knowing when to switch it off: if every mob in your world is permanently busy (a large
 * farm), the scan runs and never finds anything to skip. Turning
 * {@code tick.goal_selector_skip_when_idle} off removes that scan entirely.</p>
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorTickRunningMixin {

    @Inject(method = "tickRunningGoals", at = @At("HEAD"), cancellable = true, require = 0)
    private void velox$skipWhenNothingRunning(boolean tickAll, CallbackInfo ci) {
        if (!VeloxFast.tickGoalSelectorIdle) {
            return;
        }

        GoalSelector self = (GoalSelector) (Object) this;

        for (WrappedGoal goal : self.getAvailableGoals()) {
            if (goal.isRunning()) {
                // something is active, let vanilla do its job
                return;
            }
        }

        VeloxRuntime.onGoalSelectorSkipped();
        ci.cancel();
    }
}
