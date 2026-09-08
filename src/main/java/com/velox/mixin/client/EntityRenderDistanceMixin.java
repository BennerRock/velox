package com.velox.mixin.client;

import com.velox.VeloxFast;
import com.velox.VeloxRuntime;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 实体渲染优化：距离剔除 + 每帧渲染数量预算。
 *
 * <p><strong>1.21.11 的渲染管线已重构</strong>：{@code EntityRenderDispatcher} 不再有
 * {@code render(...)} 方法，实体是否参与渲染改由
 * {@code shouldRender(Entity, Frustum, camX, camY, camZ)} 决定。旧版注入 render 的写法在这一
 * 版本上会被 Mixin 以 {@code require = 0} 静默跳过，表现为「配了剔除距离却完全没有效果」。
 * 本类因此改为注入 {@code shouldRender}，返回 {@code false} 即剔除，这是 1.21.11 上真正
 * 生效的实体剔除点。</p>
 *
 * <p>三档独立半径：</p>
 * <ul>
 *   <li>实体总距离 {@code renderEntityDistance} - 所有实体；</li>
 *   <li>掉落物距离 {@code renderItemDistance} - 只管掉落物。农场或炸开的箱子会留下上千个
 *       物品实体，每一个都是完整光照与旋转的模型，是帧率收益最稳的一项；</li>
 *   <li>经验球距离 {@code renderExperienceOrbDistance} - 刷怪场与末影龙会留下几百个经验球。</li>
 * </ul>
 *
 * <p>数量预算：距离合格的实体再按「每帧实体渲染预算」先到先得，预算用尽后跳过其余实体的
 * 渲染。计数由 {@code VeloxFast.beginFrame()} 每帧重置。</p>
 *
 * <p>纯视觉优化，不改变玩法：实体照常被拾取、照常参与游戏逻辑，只是不绘制。
 * 全部距离设为 0 即等同原版行为。</p>
 *
 * <p>物品/经验球的判定用<strong>类字面量</strong>而非字符串类名：Loom 重映射 jar 时会改写
 * 类型引用，但不会碰字符串常量，按字符串查找在开发环境的 Mojang 名下能work、在正式游戏
 * 的混淆名下会静默失效。</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDistanceMixin {

    /**
     * 类字面量，不是类名字符串：重映射器会连同字节码一起改写这个引用。
     * 只有在类无法链接时才为 {@code null}。
     */
    private static final Class<?> ITEM_CLASS = resolveItemClass();
    private static final Class<?> XP_CLASS = resolveXpClass();

    private static Class<?> resolveItemClass() {
        try {
            return ItemEntity.class;
        } catch (Throwable t) {
            // 类字面量无法链接：退化为「永远不是物品」，而不是拖垮渲染循环。
            return null;
        }
    }

    private static Class<?> resolveXpClass() {
        try {
            return ExperienceOrb.class;
        } catch (Throwable t) {
            // 同理：经验球退化为普通实体，只是少了更紧的半径。
            return null;
        }
    }

    /**
     * 决定是否渲染该实体。HEAD 注入：需要剔除时直接返回 false，连原版的视锥计算都省掉。
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void velox$cullEntityByDistance(
            Entity entity,
            Frustum frustum,
            double camX,
            double camY,
            double camZ,
            CallbackInfoReturnable<Boolean> cir
    ) {
        // 经验球优先：它是最窄的一类，刷怪场正是最需要它的场景。
        boolean isXp = VeloxFast.xpCullEnabled && isXpEntity(entity);
        boolean isItem = !isXp && VeloxFast.itemCullEnabled && isItemEntity(entity);

        double limitSq = isXp ? VeloxFast.xpCullDistSq
                : isItem ? VeloxFast.itemCullDistSq
                : VeloxFast.entityCullDistSq;
        if (limitSq <= 0.0D) {
            return;
        }

        // 逐轴早退：某一轴已超预算即可判定，不必算完三轴。对绝大多数在半径外的实体，
        // 这就是主要开销所在。结果与完整求和逐位一致，只是运算更少。
        double dx = entity.getX() - camX;
        double distSq = dx * dx;
        boolean culled = distSq > limitSq;
        if (!culled) {
            double dy = entity.getY() - camY;
            distSq += dy * dy;
            culled = distSq > limitSq;
            if (!culled) {
                double dz = entity.getZ() - camZ;
                distSq += dz * dz;
                culled = distSq > limitSq;
            }
        }

        // 实体优化：距离合格的实体，若本帧渲染预算已满则跳过（先到先得，近处先渲染）。
        if (!culled && !VeloxFast.tryConsumeEntitySlot()) {
            culled = true;
        }

        if (culled) {
            if (isXp) {
                VeloxRuntime.onXpCulled();
            } else if (isItem) {
                VeloxRuntime.onItemCulled();
            } else {
                VeloxRuntime.onEntityCulled();
            }
            cir.setReturnValue(false);
        }
    }

    /**
     * 是否为掉落物。对加载期解析好的类字面量做一次 {@code isInstance}。
     * 若类无法链接，所有实体都按普通实体处理：仍受总距离约束，只是少了更紧的物品半径。
     */
    private static boolean isItemEntity(Entity entity) {
        Class<?> c = ITEM_CLASS;
        return c != null && c.isInstance(entity);
    }

    /** 是否为经验球，契约同 {@link #isItemEntity}。 */
    private static boolean isXpEntity(Entity entity) {
        Class<?> c = XP_CLASS;
        return c != null && c.isInstance(entity);
    }
}
