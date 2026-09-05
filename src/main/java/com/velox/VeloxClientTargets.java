package com.velox;

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
 * targets it holds. Keeping them here means {@code VeloxTargets} can stay free of any client
 * reference: it loads this class reflectively, and only on the client.</p>
 *
 * <p>Note that this class is reached by its own name, {@code com.velox.VeloxClientTargets}.
 * Only {@code net.minecraft.*} names are rewritten when the jar is remapped, so a mod's own
 * class name is one of the few strings that is safe to look up by name.</p>
 */
public final class VeloxClientTargets {

    private VeloxClientTargets() {
    }

    public static List<VeloxTargets.Target> get() {
        List<VeloxTargets.Target> out = new ArrayList<>();
        out.add(new VeloxTargets.Target(
                "client.BlockEntityRenderDispatcherMixin",
                BlockEntityRenderDispatcher.class, "render"));
        out.add(new VeloxTargets.Target(
                "client.EntityRenderDistanceMixin",
                EntityRenderDispatcher.class, "render"));
        out.add(new VeloxTargets.Target(
                "client.ParticleEngineTickMixin + ParticleEngineAddMixin",
                ParticleEngine.class, "add", "tick"));
        out.add(new VeloxTargets.Target(
                "client.GameLoopMixin",
                Minecraft.class, "tick"));
        return out;
    }
}
