package com.velox.mixin.client;

import com.velox.VeloxFast;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame heartbeat.
 *
 * <p>The client calls {@code ParticleEngine#tick()} once per frame, right before particles are
 * updated and drawn, which makes it the natural place to close out anything that is budgeted
 * per frame. Two things hang off it:</p>
 * <ul>
 *   <li>the particle budget counter - resetting it here is what ties the limiter to the frame
 *       instead of to the wall clock (v1 used {@code System.nanoTime()}, a clock read on the
 *       one path that is hot exactly when it hurts most);</li>
 *   <li>the frame clock that lets the block-entity cull refresh its cached camera once per
 *       frame instead of once per {@code N} entities.</li>
 * </ul>
 *
 * <h3>Why this is a separate mixin</h3>
 * <p>In 1.0.2 the reset and the limiter lived in the same class. That was fine functionally,
 * but it meant the limiter's {@code cancellable} injector had to exist even when the limiter
 * was off - and a cancellable injector allocates a {@code CallbackInfo} on every call, on a
 * path that runs thousands of times a second during a particle storm. Splitting them lets
 * {@code VeloxMixinPlugin} drop the expensive half entirely while keeping this one, which is
 * non-cancellable and costs a single method call per frame.</p>
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void velox$onFrame(CallbackInfo ci) {
        VeloxFast.onFrame();
    }
}
