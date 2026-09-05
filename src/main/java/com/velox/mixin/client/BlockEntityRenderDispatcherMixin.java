package com.velox.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.velox.VeloxFast;
import com.velox.VeloxRuntime;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distance culling for block entity rendering.
 *
 * <p>Vanilla frustum-culls block entities, but one inside the frustum is dispatched at any
 * distance, and the dispatch itself is not free - it resolves the renderer, picks the render
 * type and walks the block entity's state. In a base full of chests, signs and hoppers that
 * is a real slice of the frame, spent on detail you cannot make out.</p>
 *
 * <p>Purely visual: block entities keep ticking and keep their data, they just stop drawing
 * past the cutoff.</p>
 *
 * <h3>When this helps and when it hurts</h3>
 * <p>This trades CPU for GPU. If your frame rate is limited by the GPU it is a clear win; if
 * it is limited by the CPU the check itself can cost more than the drawing it avoids. v1 did
 * not account for that and lost frames on CPU-bound machines. The check is now a handful of
 * field reads and a squared-distance compare, which keeps it worthwhile - but
 * <strong>if you are CPU-bound, test with this off before assuming it helps</strong>. Set
 * {@code render.block_entity_distance=0} to disable.</p>
 *
 * <p>With the cull off, this mixin is not applied at all: {@code VeloxMixinPlugin} drops it at
 * class-load time, so the default configuration pays nothing here - no injected code, no
 * {@code CallbackInfo}, no enlarged target method.</p>
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(
            method = "render",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void velox$cullByDistance(
            BlockEntity blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            CallbackInfo ci
    ) {
        if (!VeloxFast.beCullEnabled) {
            return;
        }

        // One int comparison per call; the camera is re-read only on the first call of a frame.
        VeloxFast.tickCamera();
        if (!VeloxFast.isCamValid()) {
            return;
        }

        // BlockPos coordinates are ints; adding 0.5 centres the comparison on the block.
        net.minecraft.core.BlockPos pos = blockEntity.getBlockPos();
        double dx = VeloxFast.camX() - (pos.getX() + 0.5D);
        double dy = VeloxFast.camY() - (pos.getY() + 0.5D);
        double dz = VeloxFast.camZ() - (pos.getZ() + 0.5D);

        if (dx * dx + dy * dy + dz * dz > VeloxFast.beCullDistSq) {
            VeloxRuntime.onBlockEntityCulled();
            ci.cancel();
        }
    }
}
