package com.optima;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

import java.util.ArrayList;
import java.util.List;

/**
 * The client-side half of the startup check, held in its own class so it can be left unloaded.
 *
 * <p>A dedicated server has no {@code BlockEntityRenderDispatcher} or {@code ParticleEngine} to
 * link against, so a class that mentions them cannot be touched there - not even to ask how many
 * targets it holds. Keeping them here means {@code OptimaTargets} can stay free of any client
 * reference: it loads this class reflectively, and only on the client.</p>
 *
 * <p>Note that this class is reached by its own name, {@code com.optima.OptimaClientTargets}.
 * Only {@code net.minecraft.*} names are rewritten when the jar is remapped, so a mod's own
 * class name is one of the few strings that is safe to look up by name.</p>
 */
public final class OptimaClientTargets {

    private OptimaClientTargets() {
    }

    public static List<OptimaTargets.Target> get() {
        List<OptimaTargets.Target> out = new ArrayList<>();
        out.add(new OptimaTargets.Target(
                "client.BlockEntityRenderDispatcherMixin",
                BlockEntityRenderDispatcher.class, "render"));
        out.add(new OptimaTargets.Target(
                "client.EntityRenderDistanceMixin",
                EntityRenderDispatcher.class, "render"));
        out.add(new OptimaTargets.Target(
                "client.ParticleEngineTickMixin + ParticleEngineAddMixin",
                ParticleEngine.class, "add", "tick"));
        out.add(new OptimaTargets.Target(
                "client.GameLoopMixin",
                Minecraft.class, "tick"));
        return out;
    }
}
