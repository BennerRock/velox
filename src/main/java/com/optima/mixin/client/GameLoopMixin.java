package com.optima.mixin.client;

import com.optima.OptimaStabilizer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-frame hook that feeds the FPS stabilizer.
 *
 * <p>{@code Minecraft#tick()} runs once per rendered frame on the client, so the gap between two
 * calls is the frame time. Driving {@link OptimaStabilizer} from here means it measures the real
 * frame cadence with a single {@code nanoTime()} read per frame - cheap, and far better than
 * sampling on a hot path that runs thousands of times a second.</p>
 *
 * <p>If the method name ever moves, {@code require = 0} degrades to a warning and the stabilizer
 * simply stays dormant: the game is unchanged.</p>
 */
@Mixin(Minecraft.class)
public abstract class GameLoopMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void optima$measureFrame(CallbackInfo ci) {
        OptimaStabilizer.onFrame();
    }
}
