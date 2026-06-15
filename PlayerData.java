package com.example.ac.check;

import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.violation.FlagResult;
import com.example.ac.violation.ViolationManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.LongSupplier;

/**
 * Base class for every check. One subclass per check (per the spec). Subclasses
 * read their own settings from {@link #cfg} so adding a check is a single-file
 * change plus a registration line in {@code CheckManager}.
 */
public abstract class Check {

    protected final JavaPlugin plugin;
    protected final ACConfig config;
    protected final PlayerDataManager dataManager;
    protected final ViolationManager violations;
    private final LongSupplier tickSupplier;

    /** This check's {@code checks.<name>} section. May be null if absent. */
    protected final ConfigurationSection cfg;

    private final String name;
    private boolean enabled;
    private double maxVl;
    private double decay;
    private String punishmentTier;

    protected Check(String name, JavaPlugin plugin, ACConfig config,
                    PlayerDataManager dataManager, ViolationManager violations,
                    LongSupplier tickSupplier) {
        this.name = name;
        this.plugin = plugin;
        this.config = config;
        this.dataManager = dataManager;
        this.violations = violations;
        this.tickSupplier = tickSupplier;
        this.cfg = config.checkSection(name);
        loadCommon();
    }

    /** Re-read common config values (called on reload). */
    public void loadCommon() {
        ConfigurationSection s = config.checkSection(name);
        this.enabled = s != null && s.getBoolean("enabled", true);
        this.maxVl = s != null ? s.getDouble("max-vl", 60) : 60;
        this.decay = s != null ? s.getDouble("decay", 2) : 2;
        this.punishmentTier = s != null ? s.getString("punishment", "combat-default") : "combat-default";
    }

    /** Full reload: common values + check-specific settings. */
    public final void reload() {
        loadCommon();
        reloadSettings();
    }

    /** Override to re-read check-specific settings from {@link #cfg}. */
    protected void reloadSettings() { }

    // --- accessors used by managers ---------------------------------------
    public String name() { return name; }
    public boolean enabled() { return enabled; }
    public double maxVl() { return maxVl; }
    public double decay() { return decay; }
    public String punishmentTier() { return punishmentTier; }

    /** Current server tick (monotonic counter maintained by the plugin). */
    protected long now() { return tickSupplier.getAsLong(); }

    /** Add weighted VL and (maybe) punish. See {@link ViolationManager#flag}. */
    protected FlagResult flag(PlayerData data, String reason, double weight) {
        return violations.flag(this, data, reason, weight);
    }

    /**
     * Shared exemptions every check should honor: post-teleport/join/respawn
     * grace, optional vehicle/elytra skips, ping cap, and low-TPS suppression.
     * The {@code anticheat.bypass} permission is handled earlier (in the packet
     * listener) so we never even build wrappers for exempt staff.
     */
    protected boolean isExempt(PlayerData data) {
        long t = now();
        if (t - data.joinTick < config.afterJoinTicks) return true;
        if (data.lastTeleportTick != Long.MIN_VALUE
                && t - data.lastTeleportTick < config.afterTeleportTicks) return true;
        if (data.lastRespawnTick != Long.MIN_VALUE
                && t - data.lastRespawnTick < config.afterRespawnTicks) return true;
        if (data.lastPacketGapTick != Long.MIN_VALUE
                && t - data.lastPacketGapTick < config.afterPacketGapTicks) return true;
        if (config.maxPing > 0 && data.pingMs > config.maxPing) return true;
        if (config.skipVehicles && data.inVehicle) return true;
        if (config.skipElytra && data.gliding) return true;
        if (config.minTps > 0 && currentTps() < config.minTps) return true;
        return false;
    }

    private double currentTps() {
        try {
            double[] tps = Bukkit.getTPS();
            return tps.length > 0 ? tps[0] : 20.0;
        } catch (Throwable ignored) {
            return 20.0; // some platforms may not expose getTPS
        }
    }
}
