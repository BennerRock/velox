package com.velox;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Velox 设置界面。
 *
 * <p>只有一个核心选项：性能模式五选一循环切换。与 /velox 指令共用同一份状态
 * （VeloxConfig.INSTANCE），两边永远同步。</p>
 *
 * <p>打开方式有两种：
 * <ul>
 *   <li>Mod Menu：选中 Velox → 配置按钮；</li>
 *   <li>{@code /velox gui}：未安装 Mod Menu 时也能直接打开。</li>
 * </ul>
 * 两条路都保留，避免玩家因为 Mod Menu 缺失或入口未被识别而「点不到按钮」。</p>
 *
 * <p>界面同时展示实体优化状态、可用资源报告，以及「原版设置跟随」状态：
 * 哪些原版设置被玩家手动改过（显示「自定义（值）」），并提供「恢复跟随档位」入口。
 * Velox 从不反向覆盖玩家的手动设置，只在界面上如实展示。</p>
 */
public class VeloxConfigScreen extends Screen {

    private final Screen parent;
    private Button modeButton;
    /** 最近一次操作结果（或失败原因），显示在界面上，避免「点了没反应」。 */
    private String notice;

    public VeloxConfigScreen(Screen parent) {
        super(Component.literal("Velox 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // 重开界面或窗口缩放时先清掉旧控件，避免控件叠加后点击落到旧实例上。
        this.clearWidgets();

        int cx = this.width / 2;

        this.modeButton = Button.builder(Component.literal(modeLabel()), btn -> cycleModeSafe())
                .bounds(cx - 100, 48, 200, 20)
                .build();
        this.addRenderableWidget(this.modeButton);

        this.addRenderableWidget(Button.builder(Component.literal("恢复跟随档位"), btn -> resetFollowSafe())
                .bounds(cx - 100, 74, 200, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("完成"), btn -> this.onClose())
                .bounds(cx - 100, this.height - 30, 200, 20)
                .build());
    }

    private String modeLabel() {
        return "性能模式：" + VeloxConfig.INSTANCE.mode + "（" + VeloxConfig.INSTANCE.modeNameZh() + "）";
    }

    /** 循环切到下一档。异常一律兜住并显示在界面上，绝不会出现「点了没反应」。 */
    private void cycleModeSafe() {
        try {
            int idx = VeloxConfig.INSTANCE.modeIndex();
            String next = VeloxConfig.MODES[(idx + 1) % VeloxConfig.MODES.length];
            boolean changed = VeloxConfig.INSTANCE.setProfile(next);
            notice = changed
                    ? "已切换到 " + next + "（" + VeloxConfig.INSTANCE.modeNameZh() + "），立即生效。"
                    : "当前已是 " + next + "，无需切换。";
            Velox.LOGGER.info("[Velox] 界面切换档位 -> {}（{}）", next, VeloxConfig.INSTANCE.modeNameZh());
        } catch (Throwable t) {
            notice = "切换失败：" + t;
            Velox.LOGGER.error("[Velox] 界面切换档位失败", t);
        } finally {
            // 无论切档链路是否异常，按钮文字都按 INSTANCE 的真实档位刷新。
            // setProfile 是「先改 mode，再 applyProfile」，异常时档位其实已经变了；
            // 少了这一步就会出现「要退出界面再进来才显示新档位」。
            if (this.modeButton != null) {
                this.modeButton.setMessage(Component.literal(modeLabel()));
            }
        }
    }

    private void resetFollowSafe() {
        try {
            VeloxFollow.restoreFollowToProfile();
            notice = "已恢复跟随当前档位。";
        } catch (Throwable t) {
            notice = "恢复失败：" + t;
            Velox.LOGGER.error("[Velox] 恢复跟随档位失败", t);
        }
    }

    /**
     * 背景走 1.21.11 的官方钩子：{@code Screen} 会先调 renderBackground 再绘制控件，
     * 这样按钮一定绘制在背景之上，且事件分发不受影响。
     */
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, this.width, this.height, 0xF0202020);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        // 实时显示当前档位：每次渲染都重读一次，指令切档后界面同样立即同步。
        gui.drawCenteredString(this.font, Component.literal(modeLabel()), this.width / 2, 30, 0xAAAAAA);

        int y = 104;

        // 实体优化状态
        VeloxConfig.Profile p = VeloxConfig.INSTANCE.current();
        String entityDesc = p.renderEntityDistance > 0 ? ((int) p.renderEntityDistance) + " 格" : "关（原版）";
        String budgetDesc = p.entityRenderBudget > 0 ? String.valueOf(p.entityRenderBudget) : "不限";
        gui.drawString(this.font,
                Component.literal("实体优化：剔除距离 " + entityDesc + "，每帧渲染预算 " + budgetDesc),
                40, y, 0x99CCFF);
        y += 14;

        // 可用资源（进入世界后由 VeloxResources 生成报告）
        gui.drawString(this.font, Component.literal("可用资源：" + VeloxResources.lastReport()),
                40, y, 0x99CCFF);
        y += 14;

        if ("auto".equals(VeloxConfig.INSTANCE.mode)) {
            gui.drawString(this.font,
                    Component.literal("Auto 档：按实时 FPS / 内存 / CPU 动态调整剔除距离、粒子与实体预算。"),
                    40, y, 0x66CCFF);
            y += 14;
        }

        if (notice != null) {
            gui.drawString(this.font, Component.literal(notice), 40, y, 0xFFE066);
            y += 14;
        }

        // 原版设置跟随状态
        y += 6;
        gui.drawString(this.font, Component.literal("原版设置跟随："), 40, y, 0xCCCCCC);
        y += 14;
        List<String> lines = followLines();
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
