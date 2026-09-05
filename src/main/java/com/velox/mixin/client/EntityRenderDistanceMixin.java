package com.velox.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.velox.VeloxFast;
import com.velox.VeloxRuntime;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distance culling for entity rendering.
 *
 * <p>Like block entities, an entity inside the view frustum is drawn at any distance, and each
 * one costs a model setup and at least one draw call. At 200 blocks an entity covers a couple
 * of pixels, so nearly all of that work is invisible.</p>
 *
 * <p>Two independent radii:</p>
 * <ul>
 *   <li>{@code render.entity_distance} - everything</li>
 *   <li>{@code render.item_distance} - dropped items only, which usually deserves a much
 *       tighter radius. A farm or a blown-up chest can leave thousands of item entities on the
 *       ground, each a fully lit and rotated model. This is one of the most reliable frame-rate
 *       wins available, because the ratio of draw calls saved to pixels lost is very good.</li>
 * </ul>
 *
 * <p>Purely visual. Set either value to {@code 0} for vanilla behaviour - and note that when
 * both are {@code 0} this mixin is not applied at all (see {@code VeloxMixinPlugin}), so the
 * cost is not merely skipped, it does not exist.</p>
 *
 * <p>The three camera coordinates are already passed into this method, so they are used
 * directly rather than re-read from the camera entity - that removes three field accesses per
 * entity from the hot path.</p>
 *
 * <h3>What 1.0.3 changed</h3>
 * <p>Deciding whether an entity is an item used to go through
 * {@code entity.getClass().getName()} and a {@code String.equals} against a 40-character class
 * name - every entity, every frame. That is the exact category of per-frame string work the
 * mod exists to remove, and it was inside the mod's own hottest loop. The class is now resolved
 * once and the check is a single {@code isInstance}.</p>
 *
 * <p>That first attempt still had a bug worth recording: the class was looked up by <em>name
 * in a string</em>, and Loom rewrites type references when it remaps the jar but leaves string
 * constants alone. So the check worked in the dev environment, where classes carry Mojang
 * names, and failed in every real game, where they carry {@code class_1542} - which silently
 * disabled the item cull entirely. A class literal is remapped with everything else, so that is
 * what this uses now. <strong>Any class or member name this mod needs at runtime has to come
 * from a compiled reference, never from a string.</strong></p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDistanceMixin {

    /**
     * A class literal, not a class name: the remapper rewrites this reference along with the
     * rest of the bytecode, whereas a string would survive unchanged and then fail to resolve
     * against an obfuscated game. {@code null} only if the class somehow cannot be linked.
     */
    private static final Class<?> ITEM_CLASS = resolveItemClass();

    private static Class<?> resolveItemClass() {
        try {
            return ItemEntity.class;
        } catch (Throwable t) {
            // Class literal could not be linked. Degrade to "never an item" rather than
            // taking the render loop down.
            return null;
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void velox$cullEntityByDistance(
            Entity entity,
            double camX,
            double camY,
            double camZ,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            CallbackInfo ci
    ) {
        boolean isItem = VeloxFast.itemCullEnabled && isItemEntity(entity);
        double limitSq = isItem ? VeloxFast.itemCullDistSq : VeloxFast.entityCullDistSq;
        if (limitSq <= 0.0D) {
            return;
        }

        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;

        if (dx * dx + dy * dy + dz * dz > limitSq) {
            if (isItem) {
                VeloxRuntime.onItemCulled();
            } else {
                VeloxRuntime.onEntityCulled();
            }
            ci.cancel();
        }
    }

    /**
     * Whether the entity is a dropped item.
     *
     * <p>A single {@code isInstance} against a class literal resolved once at load time. If the
     * class could not be linked, everything is treated as a normal entity: the general radius
     * applies, and the only thing lost is the tighter item cull.</p>
     */
    private static boolean isItemEntity(Entity entity) {
        Class<?> c = ITEM_CLASS;
        return c != null && c.isInstance(entity);
    }
}
