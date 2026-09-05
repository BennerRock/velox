package com.optima.mixin.client;

import com.optima.OptimaClientBoost;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Triggers the one-time graphics boost at the end of the Minecraft constructor.
 *
 * <h3>Why this exists</h3>
 * <p>Fabric's {@code client} entry point runs <em>during</em> the Minecraft constructor, at a
 * point where {@code Minecraft#options} has not been created yet - so v1.0.1 applied its
 * graphics settings to nothing at all. The constructor tail is the earliest moment the
 * options object is usable, and it still fires before the first frame, so the boost is in
 * place for the whole session.</p>
 *
 * <h3>This hook is also a safety mechanism</h3>
 * <p>It is a mixin, so it can only run when {@code Minecraft} was actually resolved. In a jar
 * that was never remapped - the preview jar, for instance - this injection is skipped, the
 * boost never touches a missing class, and the game starts normally. Putting the same call in
 * the entry point instead is what crashed v1.0.1.</p>
 *
 * <p>Cost is one method call, once per launch.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInitMixin {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void optima$afterConstruction(CallbackInfo ci) {
        OptimaClientBoost.apply();
    }
}
