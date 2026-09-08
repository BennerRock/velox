package com.velox.mixin.client;

import com.velox.VeloxFast;
import com.velox.VeloxRuntime;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块实体渲染的距离剔除。
 *
 * <p>原版只对方块实体做视锥剔除，视锥内的方块实体无论多远都会走一遍渲染派发（解析
 * renderer、挑选 render type、遍历状态）。在满是箱子、告示牌、漏斗的基地里，这部分开销
 * 换来的是根本看不清的细节。</p>
 *
 * <p><strong>1.21.11 的渲染管线已重构</strong>：{@code BlockEntityRenderDispatcher} 不再有
 * {@code render(...)} 方法，改为 {@code tryExtractRenderState(...)} 提取渲染状态。旧写法会
 * 被 {@code require = 0} 静默跳过，导致「配了距离却没效果」。本类因此改注入
 * {@code tryExtractRenderState} 并返回 {@code null} 表示本帧不渲染——原版在没有对应
 * renderer 时同样返回 {@code null}，调用方必然处理，因此这是安全的剔除点。</p>
 *
 * <p>纯视觉优化：方块实体照常 tick、照常保存数据，只是超出距离不绘制。
 * 把 {@code renderBlockEntityDistance} 设为 0 即关闭，等同原版。</p>
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(method = "tryExtractRenderState", at = @At("HEAD"), cancellable = true, require = 0)
    private void velox$cullByDistance(
            BlockEntity blockEntity,
            float partialTick,
            ModelFeatureRenderer.CrumblingOverlay overlay,
            CallbackInfoReturnable<BlockEntityRenderState> cir
    ) {
        if (!VeloxFast.beCullEnabled) {
            return;
        }

        // 每次调用只做一次 int 比较：摄像机坐标一帧只在首次读取。
        VeloxFast.tickCamera();
        if (!VeloxFast.isCamValid()) {
            return;
        }

        // BlockPos 是整数坐标，加 0.5 让比较落在方块中心。
        BlockPos pos = blockEntity.getBlockPos();
        double limitSq = VeloxFast.beCullDistSq;

        // 逐轴早退：一轴超预算即可判定，省掉其余两轴的运算。
        double dx = VeloxFast.camX() - (pos.getX() + 0.5D);
        double distSq = dx * dx;
        boolean culled = distSq > limitSq;
        if (!culled) {
            double dy = VeloxFast.camY() - (pos.getY() + 0.5D);
            distSq += dy * dy;
            culled = distSq > limitSq;
            if (!culled) {
                double dz = VeloxFast.camZ() - (pos.getZ() + 0.5D);
                distSq += dz * dz;
                culled = distSq > limitSq;
            }
        }

        if (culled) {
            VeloxRuntime.onBlockEntityCulled();
            cir.setReturnValue(null);
        }
    }
}
