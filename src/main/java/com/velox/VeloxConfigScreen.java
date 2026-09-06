package com.velox;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mod Menu 配置界面。只有【一个】选项：性能模式，五选一循环切换。
 * 与 /velox 指令共用同一份状态（VeloxConfig.INSTANCE），所以两边永远同步。
 *
 * <p>界面同时展示「原版设置跟随」状态：哪些原版设置被玩家手动改过（显示「自定义（值）」），
 * 并提供「恢复跟随档位」入口。Velox 从不反向覆盖玩家的手动设置，只在界面上如实展示。</p>
 */
public class VeloxConfigScreen extends Screen {

    private final Screen parent;
    private Button modeButton;
    private int statusTop = 110;

    public VeloxConfigScreen(Screen parent) {
        super(Component.literal("Velox 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        this.modeButton = Button.builder(Component.literal(modeLabel()),
                        btn -> cycleMode())
                .bounds(centerX - 100, 44, 200, 20)
                .build();
        this.addRenderableWidget(this.modeButton);

        this.addRenderableWidget(Button.builder(Component.literal("恢复跟随档位"),
                        btn -> resetFollow())
                .bounds(centerX - 100, 74, 200, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("完成"),
                        btn -> this.onClose())
                .bounds(centerX - 100, this.height - 30, 200, 20)
                .build());
    }

    private String modeLabel() {
        return "性能模式：" + VeloxConfig.INSTANCE.mode + "（" + VeloxConfig.INSTANCE.modeNameZh() + "）";
    }

    private void cycleMode() {
        int idx = VeloxConfig.INSTANCE.modeIndex();
        String next = VeloxConfig.MODES[(idx + 1) % VeloxConfig.MODES.length];
        boolean changed = VeloxConfig.INSTANCE.setProfile(next);
        this.modeButton.setMessage(Component.literal(modeLabel()));
        if (changed) {
            Velox.LOGGER.info("[Velox] 界面切换到 {}（{}），立即生效。", next, VeloxConfig.INSTANCE.modeNameZh());
        }
    }

    private void resetFollow() {
        VeloxFollow.restoreFollowToProfile();
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, this.width, this.height, 0xFF202020);
        gui.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        gui.drawCenteredString(this.font, Component.literal(modeLabel()), this.width / 2, 34, 0xAAAAAA);

        if ("auto".equals(VeloxConfig.INSTANCE.mode)) {
            gui.drawCenteredString(this.font,
                    Component.literal("（Auto 档：根据 FPS / 内存 / CPU 动态调整剔除距离与粒子预算）"),
                    this.width / 2, 62, 0x66CCFF);
        }

        // 原版设置跟随状态
        List<String> lines = followLines();
        int y = statusTop;
        gui.drawString(this.font, Component.literal("原版设置跟随："), 40, y, 0xCCCCCC);
        y += 14;
        if (lines.isEmpty()) {
            gui.drawString(this.font, Component.literal("  （完全跟随档位）"), 40, y, 0x88FF88);
        } else {
            for (String line : lines) {
                gui.drawString(this.font, Component.literal("  " + line), 40, y, 0xFFCC66);
                y += 14;
            }
        }

        super.render(gui, mouseX, mouseY, partialTick);
    }

    private List<String> followLines() {
        List<String> out = new ArrayList<>();
        VeloxConfig.VanillaFollowState f = VeloxConfig.INSTANCE.vanillaFollow;
        if (f.renderDistance != null) out.add("视距：自定义（" + f.renderDistance + "）");
        if (f.simulationDistance != null) out.add("模拟距离：自定义（" + f.simulationDistance + "）");
        if (f.entityDistanceScaling != null) out.add("实体渲染距离缩放：自定义（" + f.entityDistanceScaling + "）");
        if (f.particles != null) out.add("粒子：自定义（" + f.particles + "）");
        if (f.clouds != null) out.add("云：自定义（" + f.clouds + "）");
        if (f.framerateLimit != null) out.add("帧率上限：自定义（" + f.framerateLimit + "）");
        if (f.useVsync != null) out.add("垂直同步：自定义（" + f.useVsync + "）");
        if (f.graphicsMode != null) out.add("图形画质：自定义（" + f.graphicsMode + "）");
        if (f.entityShadows != null) out.add("实体阴影：自定义（" + f.entityShadows + "）");
        return out;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
