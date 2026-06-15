package com.example.ac.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * PacketEvents listeners run on Netty threads. Almost all of our check math
 * runs there on cached {@code PlayerData} (thread-safe by design). But anything
 * that touches the live Bukkit world/entities (block lookups, kicking,
 * teleporting for set-backs) MUST run on the right thread:
 *
 *   - Folia: the owning region/entity scheduler.
 *   - Paper/Spigot: the single main thread.
 *
 * This helper detects Folia at runtime (via the presence of the region
 * scheduler API) and routes accordingly, so the same plugin jar works on both.
 */
public final class SchedulerUtil {

    private static final boolean FOLIA = detectFolia();

    private SchedulerUtil() {}

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static boolean isFolia() { return FOLIA; }

    /** Run a task on the thread that owns {@code entity}. */
    public static void runForEntity(Plugin plugin, Entity entity, Runnable task) {
        if (FOLIA) {
            // entity.getScheduler().run(plugin, t -> task.run(), null) on Folia.
            entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
        } else {
            if (Bukkit.isPrimaryThread()) task.run();
            else Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Run a task that needs an entity, only if it's still valid. */
    public static void ifEntityValid(Plugin plugin, Entity entity, Consumer<Entity> task) {
        runForEntity(plugin, entity, () -> {
            if (entity.isValid()) task.accept(entity);
        });
    }

    /** Run a one-shot task on the main/global thread (e.g. dispatch a command). */
    public static void runGlobalOnce(Plugin plugin, Runnable task) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            if (Bukkit.isPrimaryThread()) task.run();
            else Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Schedule a repeating global task. On Folia this uses the global region
     * scheduler (fine for VL decay / bookkeeping that doesn't touch a specific
     * region). On Paper it's the normal scheduler.
     */
    public static void runRepeatingGlobal(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                    scheduledTask -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }
}
