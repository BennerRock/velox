package com.velox.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.velox.VeloxFast;
import com.velox.VeloxRuntime;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
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
 * <p>Three independent radii:</p>
 * <ul>
 *   <li>{@code render.entity_distance} - everything</li>
 *   <li>{@code render.item_distance} - dropped items only, which usually deserves a much
 *       tighter radius. A farm or a blown-up chest can leave thousands of item entities on the
 *       ground, each a fully lit and rotated model. This is one of the most reliable frame-rate
 *       wins available, because the ratio of draw calls saved to pixels lost is very good.</li>
 *   <li>{@code render.experience_orb_distance} - experience orbs only. Mob farms and the ender
 *       dragon leave hundreds of them behind; they are small, but there are so many that they
 *       are worth their own radius. Orbs keep being collected - only their drawing is skipped.</li>
 * </ul>
 *
 * <p>Purely visual. Set every value to {@code 0} for vanilla behaviour - and note that when all
 * of them are {@code 0} this mixin is not applied at all (see {@code VeloxMixinPlugin}), so the
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
 *
 * <h3>What 1.0-release changed</h3>
 * <p>The distance test now bails out after the first axis that already exceeds the budget.
 * If {@code dx*dx} is greater than the squared limit then the sum of three non-negative terms
 * must be greater too, so the remaining two multiplies and additions are pure waste - and for
 * the overwhelming majority of entities, which are outside the radius, that is most of the
 * work. The result is bit-for-bit identical to computing the full sum; only the number of
 * operations changes.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDistanceMixin {

    /**
     * A class literal, not a class name: the remapper rewrites this reference along with the
     * rest of the bytecode, whereas a string would survive unchanged and then fail to resolve
     * against an obfuscated game. {@code null} only if the class somehow cannot be linked.
     */
    private static final Class<?> ITEM_CLASS = resolveItemClass();
    private static final Class<?> XP_CLASS = resolveXpClass();

    private static Class<?> resolveItemClass() {
        try {
            return ItemEntity.class;
        } catch (Throwable t) {
            // Class literal could not be linked. Degrade to "never an item" rather than
            // taking the render loop down.
            return null;
        }
    }

    private static Class<?> resolveXpClass() {
        try {
            return ExperienceOrb.class;
        } catch (Throwable t) {
            // Same reasoning: an orb is then treated as an ordinary entity, so the only
            // thing lost is the tighter orb-specific radius.
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
        // Orb first: it is the narrowest category, and a mob farm is the case that matters.
        boolean isXp = VeloxFast.xpCullEnabled && isXpEntity(entity);
        boolean isItem = !isXp && VeloxFast.itemCullEnabled && isItemEntity(entity);

        double limitSq = isXp ? VeloxFast.xpCullDistSq
                : isItem ? VeloxFast.itemCullDistSq
                : VeloxFast.entityCullDistSq;
        if (limitSq <= 0.0D) {
            return;
        }

        // Per-axis early-out. A single axis beyond the budget settles the question, so the
        // remaining axes are only computed when the first one is already inside it.
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

        if (culled) {
            if (isXp) {
                VeloxRuntime.onXpCulled();
            } else if (isItem) {
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

    /** Whether the entity is an experience orb. Same contract as {@link #isItemEntity}. */
    private static boolean isXpEntity(Entity entity) {
        Class<?> c = XP_CLASS;
        return c != null && c.isInstance(entity);
    }
}
