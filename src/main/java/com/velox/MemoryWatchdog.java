package com.velox;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Periodic heap sampling.
 *
 * <p>This is deliberately a diagnostic, not a "memory cleaner": nothing here calls
 * {@code System.gc()} or evicts game state behind Minecraft's back, because forcing
 * collection or dropping caches the game expects to be warm tends to hurt far more
 * than it helps.</p>
 *
 * <p>What it does do is give you the numbers you need to size {@code -Xmx} correctly
 * and to spot a leak early: a healthy heap sawtooths (it climbs, GC drops it, and the
 * post-GC floor stays flat). A post-GC floor that keeps creeping up across reports is
 * a leak, and this will tell you which session it started in.</p>
 */
public final class MemoryWatchdog {

    private static final long MB = 1024L * 1024L;

    /**
     * Volatile because the sampling thread itself never reads it, but a shutdown hook on
     * another thread does - and a stale read there would leave the thread running.
     */
    private static volatile Thread thread;
    private static boolean hookInstalled;

    private MemoryWatchdog() {
    }

    public static synchronized void start() {
        if (!VeloxConfig.INSTANCE.memoryWatchdog || thread != null) {
            return;
        }

        int interval = Math.max(30, VeloxConfig.INSTANCE.memoryWatchdogIntervalSeconds);

        thread = new Thread(() -> {
            MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
            long lowWaterAfterGc = Long.MAX_VALUE;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(interval * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                MemoryUsage heap = bean.getHeapMemoryUsage();
                long used = heap.getUsed();
                long max = heap.getMax();
                long committed = heap.getCommitted();

                double pct = max > 0 ? (used * 100.0) / max : -1;
                long floor = used;
                if (floor < lowWaterAfterGc) {
                    lowWaterAfterGc = floor;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("[Velox] heap ")
                        .append(used / MB).append('/').append(committed / MB)
                        .append(" MB (max ").append(max / MB).append(" MB)");
                if (pct >= 0) {
                    sb.append(String.format(" - %.1f%% used", pct));
                }
                sb.append(", lowest observed ").append(lowWaterAfterGc / MB).append(" MB");

                if (VeloxConfig.INSTANCE.collectStats) {
                    sb.append(" | ").append(VeloxRuntime.report());
                }

                Velox.LOGGER.info(sb.toString());

                if (pct >= 90.0) {
                    Velox.LOGGER.warn(
                            "[Velox] Heap is {}% full and stays above 90%. Raise -Xmx, or profile with a sampler - "
                                    + "at this level the JVM spends most of its time collecting instead of ticking.",
                            String.format("%.0f", pct));
                }
            }
        }, "velox-memory-watchdog");

        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();

        installShutdownHook();
    }

    public static synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * Interrupt the sampling thread when the JVM goes down.
     *
     * <p>The thread is a daemon so the JVM would exit without it anyway, but a hard exit
     * during the five-minute sleep is untidy in the log. Registration is best-effort: if the
     * JVM is already shutting down it throws, and the daemon flag covers that case.</p>
     */
    private static void installShutdownHook() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(MemoryWatchdog::stop, "velox-watchdog-shutdown"));
        } catch (Throwable t) {
            // Already shutting down, or hooks are not permitted. The daemon flag is enough.
        }
    }
}
