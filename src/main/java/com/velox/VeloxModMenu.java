package com.velox;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 入口。点击 Velox → 配置按钮 → 打开 VeloxConfigScreen。
 * 若玩家未安装 Mod Menu，此入口点会被 Fabric Loader 静默忽略，不影响模组运行。
 */
public class VeloxModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return VeloxConfigScreen::new;
    }
}
