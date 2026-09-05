package com.optima.mixin.tick;

import com.optima.OptimaFast;
import com.optima.OptimaRuntime;
import com.optima.PlayerProximity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs mob AI less often for mobs that are far away from every player.
 *
 * <p>Mob AI is the most expensive thing a server does per tick, and most of it is spent on
 * mobs nobody can see: a zombie in a far corner still runs full goal selection, target
 * scanning and pathfinding every tick, producing a result nobody observes.</p>
 *
 * <p>This skips the AI step for distant mobs on all but every Nth tick. The entity is still
 * ticked - it keeps moving, taking damage, burning and despawning - only the decision-making
 * is throttled.</p>
 *
 * <h3>Trade-off</h3>
 * <p>Unlike the GoalSelector mixins this is <em>not</em> behaviour-preserving. A throttled mob
 * reacts slower: it notices the player later and re-paths later. Past the configured radius
 * that is normally invisible, but it is a real gameplay change, so it ships
 * <strong>disabled by default</strong>. Enable it, measure, and turn it back off if you can
 * feel it.</p>
 *
 * <p>Mobs with an active target are always ticked at full rate, so combat is unaffected.</p>
 *
 * <p><strong>Mixin only exists when it is on:</strong> {@code OptimaMixinPlugin} drops this
 * class entirely when {@code tick.mob_ai_throttle} is false, so the default configuration has
 * no injection into {@code Mob#serverAiStep} at all. When it is on, {@link PlayerProximity}
 * caches its resolved accessors per entity class and stops scanning players at the first one
 * inside the radius, so the reflection cost is paid once per class, not once per mob per
 * tick.</p>
 */
@Mixin(Mob.class)
public abstract class MobAiThrottleMixin {

    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true, require = 0)
    private void optima$throttleDistantMobAi(CallbackInfo ci) {
        if (!OptimaFast.tickMobAiThrottle) {
            return;
        }

        Mob self = (Mob) (Object) this;

        // Never throttle a mob that is actively targeting something.
        if (self.getTarget() != null) {
            return;
        }

        // Passes the radius so the scan can stop at the first player who is already close
        // enough - the answer "someone is near" is all this needs.
        double nearestSq = PlayerProximity.nearestPlayerDistanceSqr(self, OptimaFast.tickMobAiThrottleDistSq);
        if (nearestSq == PlayerProximity.UNKNOWN || nearestSq <= OptimaFast.tickMobAiThrottleDistSq) {
            return;
        }

        // Offset by entity id so throttled mobs do not all wake up on the same tick and
        // recreate the spike this is meant to remove.
        if (((self.tickCount + self.getId()) % OptimaFast.tickMobAiThrottleInterval) != 0) {
            OptimaRuntime.onMobAiThrottled();
            ci.cancel();
        }
    }
}
