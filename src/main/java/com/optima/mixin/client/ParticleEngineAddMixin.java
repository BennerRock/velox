package com.optima.mixin.client;

import com.optima.OptimaFast;
import com.optima.OptimaRuntime;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rate limiter for particle spawning.
 *
 * <p>Particle storms are one of the most common causes of a sudden frame-rate collapse: a wall
 * of campfires, a beacon pyramid, a mob farm. Each particle is allocated, ticked, sorted for
 * transparency and drawn, and the cost lands on the render thread.</p>
 *
 * <p>This caps how many <em>new</em> particles the engine accepts per frame. Particles already
 * alive keep living, so existing effects do not visibly pop - the limiter only stops the flood
 * from growing.</p>
 *
 * <h3>Only applied when the limiter is on</h3>
 * <p>{@code OptimaMixinPlugin} skips this mixin completely when
 * {@code render.particle_budget} is {@code 0}. That matters more here than anywhere else in the
 * mod: {@code add} is the hottest hook Optima has, and being cancellable it costs a
 * {@code CallbackInfo} per call. With the limiter off there is nothing to cancel, so in 1.0.3
 * there is no code there at all.</p>
 *
 * <p>The counter is a plain static {@code int} inside {@link OptimaFast}. Two threads racing
 * can lose an increment; for a beam of particles nobody can count, that is a fine trade for
 * having no synchronisation here. It also heals itself: if the per-frame heartbeat that resets
 * the budget never arrives, it falls back to counting calls, so a failed injection can make the
 * limiter looser but can never make it block particles permanently.</p>
 *
 * <p>No {@code @Shadow} is used, so no internal particle-map field names are depended on.</p>
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineAddMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private void optima$rateLimit(Particle particle, CallbackInfo ci) {
        if (!OptimaFast.tryConsumeParticleBudget()) {
            OptimaRuntime.onParticleSuppressed();
            ci.cancel();
        }
    }
}
