package com.velox;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * 客户端入口点。
 *
 * <p>Velox 的指令、联机守卫、原版设置跟随都注册在这里（均为 Fabric API 的客户端事件）。
 * 配置已在主入口加载并铺到热路径；图形 boost 由 MinecraftInitMixin 在构造尾部触发。</p>
 */
public class VeloxClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        VeloxCommand.register();
        VeloxMultiplayerGuard.register();

        // 客户端首次 tick 后建立原版设置跟随的映射，之后玩家改设置才会被记录。
        ClientTickEvents.END_CLIENT_TICK.register(client -> VeloxFollow.arm());

        boolean anyBoost = VeloxConfig.INSTANCE.current().boostGraphicsMode
                || VeloxConfig.INSTANCE.current().boostDisableClouds
                || VeloxConfig.INSTANCE.current().boostDisableEntityShadows
                || VeloxConfig.INSTANCE.current().boostMinimalParticles
                || VeloxConfig.INSTANCE.current().boostFastAmbientOcclusion;

        Velox.LOGGER.info("[Velox] 客户端就绪。图形 boost {} - 在 Minecraft 构造完成后应用，而非此处。",
                anyBoost ? "已启用" : "按配置禁用");
    }
}
