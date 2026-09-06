package com.velox;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

/**
 * /velox 指令：在五档之间切换，立即生效、立即写盘。
 * 与 Mod Menu 界面共用同一份状态（VeloxConfig.INSTANCE）。
 */
public final class VeloxCommand {

    private VeloxCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(VeloxCommand::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                         CommandBuildContext context) {
        var builder = ClientCommandManager.literal("velox");
        for (String mode : VeloxConfig.MODES) {
            builder = builder.then(ClientCommandManager.literal(mode)
                    .executes(ctx -> run(ctx, mode)));
        }
        dispatcher.register(builder.executes(VeloxCommand::listModes));
    }

    private static int run(CommandContext<FabricClientCommandSource> ctx, String mode) {
        if (VeloxConfig.INSTANCE.setProfile(mode)) {
            ctx.getSource().sendFeedback(
                    Component.literal("§a[Velox] 已切换到 " + mode + "（" + VeloxConfig.INSTANCE.modeNameZh() + "），立即生效。"));
        } else {
            ctx.getSource().sendFeedback(
                    Component.literal("§e[Velox] 当前已是 " + mode + "，无需切换。"));
        }
        return 1;
    }

    private static int listModes(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(
                Component.literal("§b[Velox] 可用档位：" + String.join(" / ", VeloxConfig.MODES)
                        + "（当前：" + VeloxConfig.INSTANCE.mode + "）"));
        return 1;
    }
}
