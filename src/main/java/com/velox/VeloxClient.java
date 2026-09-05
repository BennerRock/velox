package com.velox;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client-side entry point.
 *
 * <p>Deliberately does almost nothing. Fabric runs this <em>inside</em> the Minecraft
 * constructor, before any game state exists, so it is the wrong place to touch options or
 * the renderer. In v1.0.1 this class applied the graphics boost here - the settings were
 * silently discarded, and in an unremapped jar the attempt crashed the game.</p>
 *
 * <p>The real work now happens in {@code MinecraftInitMixin}, which fires at the end of the
 * constructor. That is both the earliest point where the options object exists and a place
 * that can only be reached if the jar was remapped correctly.</p>
 */
public class VeloxClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Config is already loaded by the main entry point; re-snapshot so the client-side
        // statics reflect the final values.
        VeloxFast.reload(VeloxConfig.INSTANCE);

        boolean anyBoost = VeloxConfig.INSTANCE.boostGraphicsMode
                || VeloxConfig.INSTANCE.boostDisableClouds
                || VeloxConfig.INSTANCE.boostDisableEntityShadows
                || VeloxConfig.INSTANCE.boostMinimalParticles
                || VeloxConfig.INSTANCE.boostFastAmbientOcclusion;

        Velox.LOGGER.info("[Velox] Client ready. Graphics boost {} - applied after the "
                        + "Minecraft constructor finishes, not here.",
                anyBoost ? "enabled" : "disabled by config");
    }
}
