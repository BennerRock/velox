package com.velox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * JSON 配置 + 五档预设（auto / safe / eco / aggressive / vanilla）。
 *
 * 文件位于 config/velox.json，复用 Minecraft 自带的 Gson。
 *
 * 玩法安全总原则（最高优先级）：所有优化绝对不改变原版玩法。生物 AI、目标选择器
 * 等游戏逻辑相关的 tick 优化已在 v1.1 起永久禁用（五档全关 + mixin 不注入），
 * 见 VeloxMixinPlugin。保留的优化全部是客户端渲染路径（剔除/粒子/帧率/图形设置），
 * 不触碰任何游戏规则。
 *
 * 五档：
 * - vanilla：全部关闭，表现与未安装本模组一致；
 * - safe：只开最稳妥的客户端优化（物品/经验球剔除 + 粒子限流 + 保守距离）；
 * - eco：偏向省内存省电，距离更紧、预算更低；
 * - aggressive：能开的全开、距离拉满，允许牺牲部分画面细节；
 * - auto：动态调配，见 VeloxAutoTuner。
 *
 * 热重载：切档那一刻，setProfile 更新 mode，applyProfile 把该档参数整体铺到
 * VeloxFast 的静态字段（mixin 热路径读的就是它们），启停 Auto 调参器与内存看门狗，
 * vanilla 档时恢复启动期 boost 改过的图形项，save 立即写盘。
 * 全程无需重启、无需重进世界。
 */
public final class VeloxConfig {

    public static final VeloxConfig INSTANCE = new VeloxConfig();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 五档模式名（小写），供指令 Tab 补全与界面循环切换共用。 */
    public static final String[] MODES = {"auto", "safe", "eco", "aggressive", "vanilla"};

    /** 中文名，反馈与界面显示用，下标与 MODES 对齐。 */
    public static final String[] MODE_NAMES_ZH = {"自动", "安全", "节能", "激进", "原版"};

    // =====================================================================
    // 持久化字段（JSON 里出现的就这些）
    // =====================================================================

    /** 当前档位，取值见 MODES。默认 auto。 */
    public String mode = "auto";

    /** FPS 目标，用于 FPS 稳定器与 Auto 档判定。最小 30。 */
    public int targetFps = 60;

    /**
     * 【五】跟随原版设置的自定义状态：null = 跟随档位；
     * 非 null = 玩家手动改过，Velox 只展示、不反向覆盖。
     */
    public VanillaFollowState vanillaFollow = new VanillaFollowState();

    /** 【五】原版设置自定义状态。 */
    public static final class VanillaFollowState {
        public Integer renderDistance;
        public Integer simulationDistance;
        public Double entityDistanceScaling;
        public String particles;
        public String clouds;
        public Integer framerateLimit;
        public Boolean useVsync;
        public String graphicsMode;
        public Boolean entityShadows;

        /** 是否有任何一项处于自定义状态。 */
        public boolean anyCustom() {
            return renderDistance != null || simulationDistance != null
                    || entityDistanceScaling != null || particles != null || clouds != null
                    || framerateLimit != null || useVsync != null || graphicsMode != null
                    || entityShadows != null;
        }

        /** 清空全部自定义标记（恢复跟随档位时调用）。 */
        public void clear() {
            renderDistance = null;
            simulationDistance = null;
            entityDistanceScaling = null;
            particles = null;
            clouds = null;
            framerateLimit = null;
            useVsync = null;
            graphicsMode = null;
            entityShadows = null;
        }
    }

    // =====================================================================
    // 五档预设
    // =====================================================================

    /** 一档的完整参数快照，字段与 VeloxFast 一一对应。 */
    public static final class Profile {
        // tick：v1.1 起永久禁用（玩法安全），字段保留只为兼容，五档恒 false
        public boolean tickGoalSelectorEmptyFastPath;
        public boolean tickGoalSelectorSkipWhenIdle;
        public boolean tickMobAiThrottle;
        public double tickMobAiThrottleDistance;
        public int tickMobAiThrottleInterval;

        // render
        public double renderBlockEntityDistance;
        public double renderEntityDistance;
        public double renderItemDistance;
        public double renderExperienceOrbDistance;
        public int renderParticleBudget;

        /**
         * 实体优化：每帧实体渲染数量上限（0 = 不限制，等同原版）。
         * 距离剔除之后仍存活的实体若已超过本帧预算，则跳过其渲染。
         */
        public int entityRenderBudget;

        // stability
        public boolean stabilityFpsGovernor;
        public boolean stabilityAdaptiveParticles;
        public boolean stabilityAdaptiveCulling;
        public int stabilityAdaptInterval;
        public boolean stabilityLogStutters;
        public int stabilityStutterMs;

        // memory
        public boolean memoryWatchdog;
        public int memoryWatchdogIntervalSeconds;

        // boost（启动时一次性，切 vanilla 时恢复）
        public boolean boostGraphicsMode;
        public boolean boostDisableClouds;
        public boolean boostDisableEntityShadows;
        public boolean boostMinimalParticles;
        public boolean boostFastAmbientOcclusion;

        // 诊断
        public boolean collectStats;
    }

    /** 五档取值，下标与 MODES 对齐：0=auto 1=safe 2=eco 3=aggressive 4=vanilla。 */
    public static final Profile[] PROFILES = buildProfiles();

    private static Profile[] buildProfiles() {
        Profile auto = new Profile();
        Profile safe = new Profile();
        Profile eco = new Profile();
        Profile aggressive = new Profile();
        Profile vanilla = new Profile();

        // ---------------- Vanilla：全部关闭，等同原版 ----------------
        vanilla.renderBlockEntityDistance = 0;
        vanilla.renderEntityDistance = 0;
        vanilla.renderItemDistance = 0;
        vanilla.renderExperienceOrbDistance = 0;
        vanilla.renderParticleBudget = 0;
        vanilla.entityRenderBudget = 0;
        vanilla.stabilityFpsGovernor = false;
        vanilla.stabilityAdaptiveParticles = false;
        vanilla.stabilityAdaptiveCulling = false;
        vanilla.stabilityAdaptInterval = 0;
        vanilla.stabilityLogStutters = false;
        vanilla.stabilityStutterMs = 200;
        vanilla.memoryWatchdog = false;
        vanilla.memoryWatchdogIntervalSeconds = 0;
        vanilla.boostGraphicsMode = false;
        vanilla.boostDisableClouds = false;
        vanilla.boostDisableEntityShadows = false;
        vanilla.boostMinimalParticles = false;
        vanilla.boostFastAmbientOcclusion = false;
        vanilla.collectStats = false;

        // ---------------- Safe ----------------
        // 视觉优先：距离放宽到几乎看不出差别，只剔除真正远到看不清的实体。
        safe.renderBlockEntityDistance = 48;
        safe.renderEntityDistance = 96;
        safe.renderItemDistance = 48;
        safe.renderExperienceOrbDistance = 48;
        safe.renderParticleBudget = 4000;
        safe.entityRenderBudget = 0;
        safe.stabilityFpsGovernor = true;
        safe.stabilityAdaptiveParticles = true;
        safe.stabilityAdaptiveCulling = false;
        safe.stabilityAdaptInterval = 60;
        safe.stabilityLogStutters = true;
        safe.stabilityStutterMs = 200;
        safe.memoryWatchdog = true;
        safe.memoryWatchdogIntervalSeconds = 60;
        // 视觉优先：默认不改玩家的原版画质设置（粒子/云/阴影/平滑光照/画质）。
        // boost 机制保留（可按需要改配置开启），但不再默认改变可见画面。
        safe.boostGraphicsMode = false;
        safe.boostDisableClouds = false;
        safe.boostDisableEntityShadows = false;
        safe.boostMinimalParticles = false;
        safe.boostFastAmbientOcclusion = false;
        safe.collectStats = false;

        // ---------------- Eco：偏向省内存省电 ----------------
        eco.renderBlockEntityDistance = 32;
        eco.renderEntityDistance = 64;
        eco.renderItemDistance = 32;
        eco.renderExperienceOrbDistance = 32;
        eco.renderParticleBudget = 2000;
        eco.entityRenderBudget = 0;
        eco.stabilityFpsGovernor = true;
        eco.stabilityAdaptiveParticles = true;
        eco.stabilityAdaptiveCulling = true;
        eco.stabilityAdaptInterval = 60;
        eco.stabilityLogStutters = true;
        eco.stabilityStutterMs = 200;
        eco.memoryWatchdog = true;
        eco.memoryWatchdogIntervalSeconds = 30;
        eco.boostGraphicsMode = false;
        eco.boostDisableClouds = false;
        eco.boostDisableEntityShadows = false;
        eco.boostMinimalParticles = false;
        eco.boostFastAmbientOcclusion = false;
        eco.collectStats = false;

        // ---------------- Aggressive：能开的全开、距离拉满 ----------------
        aggressive.renderBlockEntityDistance = 64;
        aggressive.renderEntityDistance = 128;
        aggressive.renderItemDistance = 64;
        aggressive.renderExperienceOrbDistance = 64;
        aggressive.renderParticleBudget = 8000;
        aggressive.entityRenderBudget = 0;
        aggressive.stabilityFpsGovernor = true;
        aggressive.stabilityAdaptiveParticles = true;
        aggressive.stabilityAdaptiveCulling = true;
        aggressive.stabilityAdaptInterval = 60;
        aggressive.stabilityLogStutters = true;
        aggressive.stabilityStutterMs = 200;
        aggressive.memoryWatchdog = true;
        aggressive.memoryWatchdogIntervalSeconds = 15;
        aggressive.boostGraphicsMode = false;
        aggressive.boostDisableClouds = false;
        aggressive.boostDisableEntityShadows = false;
        aggressive.boostMinimalParticles = false;
        aggressive.boostFastAmbientOcclusion = false;
        aggressive.collectStats = false;

        // ---------------- Auto：以 Safe 为基准起步，运行期由 VeloxAutoTuner 动态调整 ----------------
        auto.renderBlockEntityDistance = 48;
        auto.renderEntityDistance = 96;
        auto.renderItemDistance = 48;
        auto.renderExperienceOrbDistance = 48;
        auto.renderParticleBudget = 4000;
        auto.entityRenderBudget = 0;
        auto.stabilityFpsGovernor = true;
        auto.stabilityAdaptiveParticles = true;
        auto.stabilityAdaptiveCulling = false; // 动态开关由 AutoTuner 控制
        auto.stabilityAdaptInterval = 60;
        auto.stabilityLogStutters = true;
        auto.stabilityStutterMs = 200;
        auto.memoryWatchdog = true;
        auto.memoryWatchdogIntervalSeconds = 60;
        auto.boostGraphicsMode = false;
        auto.boostDisableClouds = false;
        auto.boostDisableEntityShadows = false;
        auto.boostMinimalParticles = false;
        auto.boostFastAmbientOcclusion = false;
        auto.collectStats = false;

        return new Profile[]{auto, safe, eco, aggressive, vanilla};
    }

    // =====================================================================
    // 读写
    // =====================================================================

    private Path file;

    public Path getFile() {
        if (file == null) {
            file = FabricLoader.getInstance().getConfigDir().resolve("velox.json");
        }
        return file;
    }

    /** 启动时调用一次。文件不存在就按默认（auto）写一份。 */
    public void load() {
        Path p = getFile();
        if (!Files.exists(p)) {
            applyProfile();
            save();
            return;
        }
        try (Reader r = Files.newBufferedReader(p)) {
            VeloxConfig loaded = GSON.fromJson(r, VeloxConfig.class);
            if (loaded != null) {
                this.mode = normalizeMode(loaded.mode);
                this.targetFps = loaded.targetFps > 0 ? loaded.targetFps : 60;
                this.vanillaFollow = loaded.vanillaFollow != null ? loaded.vanillaFollow : new VanillaFollowState();
            }
        } catch (Exception e) {
            Velox.LOGGER.warn("[Velox] 读取配置失败，使用默认 auto 档: {}", p, e);
        }
    }

    /** 启动早期只读加载（VeloxMixinPlugin 用，不写盘、不打日志）。 */
    public void readOnlyLoad() {
        Path p = getFile();
        if (!Files.exists(p)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(p)) {
            VeloxConfig loaded = GSON.fromJson(r, VeloxConfig.class);
            if (loaded != null) {
                this.mode = normalizeMode(loaded.mode);
                this.targetFps = loaded.targetFps > 0 ? loaded.targetFps : 60;
                this.vanillaFollow = loaded.vanillaFollow != null ? loaded.vanillaFollow : new VanillaFollowState();
            }
        } catch (Exception ignored) {
            // 读取失败就用字段默认值（auto），不抛出
        }
    }

    /** 立即写盘。切档、恢复跟随档位后都会调用。 */
    public void save() {
        Path p = getFile();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            Velox.LOGGER.warn("[Velox] 写配置失败: {}", p, e);
        }
    }

    /** 把非法档位名折回 auto。 */
    public static String normalizeMode(String m) {
        if (m == null) {
            return "auto";
        }
        String v = m.trim().toLowerCase(Locale.ROOT);
        for (String s : MODES) {
            if (s.equals(v)) {
                return s;
            }
        }
        return "auto";
    }

    public int modeIndex() {
        String m = normalizeMode(this.mode);
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(m)) {
                return i;
            }
        }
        return 0;
    }

    /** 当前档位中文名。 */
    public String modeNameZh() {
        return MODE_NAMES_ZH[modeIndex()];
    }

    /**
     * 切档并立即生效 + 写盘。指令与界面都走这里，保证两边读同一个状态。
     * 返回是否真的发生了切换（同档重复切返回 false）。
     */
    public boolean setProfile(String newMode) {
        String m = normalizeMode(newMode);
        if (m.equals(normalizeMode(this.mode))) {
            return false;
        }
        this.mode = m;
        applyProfile();
        save();
        return true;
    }

    /**
     * 把当前档位铺到热路径静态快照上（热重载核心）。
     * 任何切档来源（指令/界面）最终都走这里。
     */
    public void applyProfile() {
        Profile p = PROFILES[modeIndex()];
        String m = normalizeMode(this.mode);
        VeloxFast.applyProfile(p, m);
        VeloxRuntime.collectStats = p.collectStats;
        // 内存看门狗：vanilla 档停线程并释放资源，其它档确保在跑。
        if (p.memoryWatchdog) {
            MemoryWatchdog.start();
        } else {
            MemoryWatchdog.stop();
        }
        // FPS 稳定器按新档参数重建（init 可重入）。
        VeloxStabilizer.init();
        // Auto 调参器：仅 auto 档运行，其它档停止并复位到档位基准。
        if ("auto".equals(m)) {
            VeloxAutoTuner.start();
        } else {
            VeloxAutoTuner.stop();
        }
        // vanilla 档：恢复启动期 boost 改过的图形项（见 VeloxClientBoost）。
        // 非 vanilla 档：若已进入游戏（Minecraft 已构造），重新应用 boost，
        // 以便从 vanilla 切回来时把图形加速补上（启动期 boost 由 MinecraftInitMixin 触发）。
        if ("vanilla".equals(m)) {
            VeloxClientBoost.restoreBoostedOptions();
        } else {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                // reapply 而非 apply：清掉一次性闩锁，保证从 vanilla 切回来时图形加速真的补上。
                VeloxClientBoost.reapply();
            }
        }
    }

    /** 当前档的参数快照（只读）。 */
    public Profile current() {
        return PROFILES[modeIndex()];
    }
}
