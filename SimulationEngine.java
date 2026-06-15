package com.example.ac;

import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.listener.BukkitListener;
import com.example.ac.listener.PacketListener;
import com.example.ac.manager.CheckManager;
import com.example.ac.util.SchedulerUtil;
import com.example.ac.violation.ViolationManager;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * SentinelAC entry point.
 *
 * Wiring order matters: PacketEvents is built in {@link #onLoad()} (required by
 * its API) and initialised in {@link #onEnable()} after our managers exist. A
 * single monotonic tick counter is incremented once per server tick and handed
 * to every check via a {@link LongSupplier}; all check timing is expressed in
 * these ticks so the logic is independent of wall-clock scheduling.
 */
public final class AntiCheatPlugin extends JavaPlugin {

    private final AtomicLong tickCounter = new AtomicLong(0L);

    private ACConfig config;
    private PlayerDataManager dataManager;
    private ViolationManager violations;
    private CheckManager checkManager;
    private BukkitListener bukkitListener;

    @Override
    public void onLoad() {
        // PacketEvents must be created in onLoad.
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new ACConfig(this);

        int rotationSampleSize = getConfig()
                .getInt("checks.killaura.sub-checks.rotation.sample-size", 40);
        this.dataManager = new PlayerDataManager(config.historyTicks, rotationSampleSize);
        this.violations = new ViolationManager(this, config);

        final LongSupplier tickSupplier = tickCounter::get;
        this.checkManager = new CheckManager(this, config, dataManager, violations, tickSupplier);

        // PacketEvents settings (best-effort; ignore if the API differs).
        try {
            PacketEvents.getAPI().getSettings().checkForUpdates(false).reEncodeByDefault(false);
        } catch (Throwable ignored) { }
        PacketEvents.getAPI().init();
        PacketEvents.getAPI().getEventManager().registerListener(
                new PacketListener(this, config, dataManager, checkManager, tickSupplier));

        this.bukkitListener = new BukkitListener(this, dataManager, violations, checkManager, tickSupplier);
        getServer().getPluginManager().registerEvents(bukkitListener, this);

        // Tick counter: +1 every server tick.
        SchedulerUtil.runRepeatingGlobal(this, tickCounter::incrementAndGet, 1L, 1L);

        // VL decay on the configured interval.
        long interval = Math.max(1, config.violationTickInterval);
        SchedulerUtil.runRepeatingGlobal(this,
                () -> violations.decayTick(dataManager.all(), checkManager), interval, interval);

        // Periodically re-derive server-authoritative item + movement-state flags
        // (these aren't carried by the packets we parse).
        SchedulerUtil.runRepeatingGlobal(this, this::refreshAllStates, 20L, 20L);

        // Adopt already-online players (e.g. after a /reload).
        long now = tickCounter.get();
        for (Player p : getServer().getOnlinePlayers()) {
            PlayerData data = dataManager.getOrCreate(p.getUniqueId(), p.getName());
            violations.load(data);
            dataManager.linkEntityId(data, p.getEntityId());
            data.joinTick = now;
            data.lastTeleportTick = now;
            SchedulerUtil.runForEntity(this, p, () -> {
                bukkitListener.refreshHeldItem(p, data);
                bukkitListener.refreshState(p, data);
            });
        }

        getLogger().info("SentinelAC enabled (Folia mode: " + SchedulerUtil.isFolia() + ").");
    }

    @Override
    public void onDisable() {
        if (violations != null && dataManager != null) {
            violations.saveAll(dataManager.all());
        }
        try {
            PacketEvents.getAPI().terminate();
        } catch (Throwable ignored) { }
        getLogger().info("SentinelAC disabled.");
    }

    private void refreshAllStates() {
        for (Player p : getServer().getOnlinePlayers()) {
            PlayerData data = dataManager.get(p.getUniqueId());
            if (data == null) continue;
            SchedulerUtil.runForEntity(this, p, () -> {
                bukkitListener.refreshHeldItem(p, data);
                bukkitListener.refreshState(p, data);
            });
        }
    }

    // -----------------------------------------------------------------------
    //  /sentinel command
    // -----------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sentinel")) return false;

        if (args.length == 0) {
            sender.sendMessage("§7/sentinel §f<reload|vl <player>|alerts>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                config.reload();
                checkManager.reload();
                sender.sendMessage("§aSentinelAC config reloaded. "
                        + "§7(rotation sample-size changes need a restart)");
            }
            case "vl" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /sentinel vl <player>");
                    return true;
                }
                Player target = getServer().getPlayerExact(args[1]);
                PlayerData data = target != null ? dataManager.get(target.getUniqueId()) : null;
                if (data == null) {
                    sender.sendMessage("§cNo tracked data for '" + args[1] + "'.");
                    return true;
                }
                Map<String, Double> vls = data.getViolations();
                if (vls.isEmpty()) {
                    sender.sendMessage("§a" + data.name + " has no active violations.");
                } else {
                    sender.sendMessage("§7Violations for §f" + data.name + "§7 (ping "
                            + data.pingMs + "ms):");
                    vls.forEach((check, vl) ->
                            sender.sendMessage(String.format("  §e%-16s §f%.1f", check, vl)));
                }
            }
            case "alerts" -> {
                if (sender instanceof Player p) {
                    boolean on = violations.toggleAlerts(p.getUniqueId());
                    sender.sendMessage(on ? "§aAlerts enabled." : "§7Alerts muted.");
                } else {
                    sender.sendMessage("§7Console always receives alerts.");
                }
            }
            default -> sender.sendMessage("§7/sentinel §f<reload|vl <player>|alerts>");
        }
        return true;
    }
}
