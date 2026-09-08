package com.velox;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * Auto 档的动态调参器。
 *
 * <p>监控客户端可观测指标（FPS、堆内存、CPU 负载），在「当前档位基准」与「下限」之间
 * 逐步收紧/放宽剔除距离与粒子预算。采用滞回 + 小步长 + 最短持续时长，避免抖动。
 * GPU 负载在纯 Java/MC 下没有跨平台可靠获取方式（需 OpenGL timer query 或 NVML 本地库，
 * 引入开销与兼容性风险），故不纳入，改用帧时间与 CPU 负载近似。</p>
 *
 * <p>仅影响客户端渲染预算（剔除距离、粒子数），绝不触碰游戏逻辑，所以永远不改变玩法。</p>
 */
public final class VeloxAutoTuner {

    private static Thread thread;
    private static volatile boolean running;

    // 滞回：连续 N 个采样窗口满足条件才切换方向，避免反复横跳。
    private static int lowStreak;
    private static int highStreak;
    private static final int STREAK_LOW = 10;  // 约 5 秒持续卡顿才收紧（避免抖动与视觉突变）
    private static final int STREAK_HIGH = 12; // 约 6 秒持续流畅才放宽

    private static final double FACTOR_STEP = 0.9;     // 每步收紧/放宽比例
    private static final double FACTOR_MIN = 0.7;       // 下限（不低于档位基准的 70%，视觉几乎无感）
    private static final double FACTOR_MAX = 1.0;       // 不超过档位基准
    private static double factor = 1.0;

    private static OperatingSystemMXBean osBean;
    private static long lastSample;

    private VeloxAutoTuner() {
    }

    public static synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        factor = 1.0;
        lowStreak = 0;
        highStreak = 0;
        try {
            osBean = ManagementFactory.getOperatingSystemMXBean();
        } catch (Throwable ignored) {
            osBean = null;
        }
        thread = new Thread(VeloxAutoTuner::loop, "velox-auto-tuner");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
        // 复位到档位基准，避免残留缩放影响其它档位。
        VeloxFast.resetCullDistances();
        VeloxFast.resetParticleBudget();
        factor = 1.0;
    }

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            sample();
        }
    }

    private static void sample() {
        double fps = VeloxStabilizer.emaFps;
        if (fps <= 0.0) {
            return;
        }
        double target = Math.max(30.0D, VeloxFast.targetFps);

        boolean under = fps < target * 0.9D;
        boolean over = fps > target * 1.05D;

        if (under) {
            lowStreak++;
            highStreak = 0;
        } else if (over) {
            highStreak++;
            lowStreak = 0;
        } else {
            lowStreak = 0;
            highStreak = 0;
        }

        if (lowStreak >= STREAK_LOW && factor > FACTOR_MIN) {
            factor = Math.max(FACTOR_MIN, factor * FACTOR_STEP);
            VeloxFast.scaleCullDistances(factor);
            shrinkParticleBudget();
            lowStreak = 0;
            Velox.LOGGER.info("[Velox] Auto：FPS {} 偏低，剔除/粒子收紧到系数 {}", Math.round(fps), factor);
        } else if (highStreak >= STREAK_HIGH && factor < FACTOR_MAX) {
            factor = Math.min(FACTOR_MAX, factor / FACTOR_STEP);
            VeloxFast.scaleCullDistances(factor);
            growParticleBudget();
            highStreak = 0;
            Velox.LOGGER.info("[Velox] Auto：FPS {} 流畅，剔除/粒子放宽到系数 {}", Math.round(fps), factor);
        }

        // 内存/CPU 低频采样（每 5 秒）：高负载额外收紧粒子预算。
        long now = System.currentTimeMillis();
        if (now - lastSample > 5000L) {
            lastSample = now;
            sampleResources();
        }
    }

    private static void sampleResources() {
        try {
            double cpu = osBean != null ? osBean.getSystemLoadAverage() : -1.0D;
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            double memPct = used * 100.0D / rt.maxMemory();
            if (memPct > 90.0D || (cpu >= 0.0D && cpu > 0.9D * osBean.getAvailableProcessors())) {
                shrinkParticleBudget();
                Velox.LOGGER.info("[Velox] Auto：资源紧张（内存 {}%，CPU {}），额外收紧粒子预算",
                        Math.round(memPct), Math.round(cpu * 100.0D));
            }
        } catch (Throwable ignored) {
            // 采集失败不影响主流程
        }
    }

    private static void shrinkParticleBudget() {
        int next = (int) (VeloxFast.effectiveParticleBudget * FACTOR_STEP);
        VeloxFast.setEffectiveParticleBudget(next);
    }

    private static void growParticleBudget() {
        int next = VeloxFast.effectiveParticleBudget + Math.max(1, VeloxFast.particleBudget / 20);
        VeloxFast.setEffectiveParticleBudget(next);
    }
}
