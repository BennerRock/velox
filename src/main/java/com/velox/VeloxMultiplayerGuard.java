package com.velox;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * 联机检测。
 *
 * <p>进入远程服务器时把会改变玩法的优化强制关掉。v1.1 起所有 tick 类游戏逻辑优化已永久
 * 禁用（保证原版玩法一致），这里的守卫是防御性接口：万一将来重新启用某项服务端相关优化，
 * 联机态下仍会被强制关。同时联机态切档时重铺一次参数，保证运行时开关正确。</p>
 */
public final class VeloxMultiplayerGuard {

    private static volatile boolean remoteServer;

    private VeloxMultiplayerGuard() {
    }

    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            remoteServer = !client.isSingleplayer();
            // 联机态下重新铺一次档位，确保任何服务端相关开关被强制关。
            VeloxConfig.INSTANCE.applyProfile();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            remoteServer = false;
            VeloxConfig.INSTANCE.applyProfile();
        });
    }

    /** 是否连着远程服务器（多人联机）。 */
    public static boolean isRemoteServer() {
        return remoteServer;
    }
}
