package com.example.ac.listener;

import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.manager.CheckManager;
import com.example.ac.util.SchedulerUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.LongSupplier;

/**
 * Bukkit-event side of the plugin. Three jobs:
 *
 *  1. Lifecycle: create/destroy {@link PlayerData}, link entity ids (so attacks
 *     resolve to a victim), and stamp join/respawn/teleport/world-change/
 *     velocity ticks that the shared exemptions and the speed grace rely on.
 *
 *  2. Server-authoritative item verification: whenever the held item could
 *     change we re-derive the weapon-category flags ({@code holdingMace} etc.)
 *     from the ACTUAL server inventory — never from anything the client says.
 *
 *  3. The mace attribute-swap damage cross-check, fed from the real damage event.
 *
 * Everything here runs on the thread Bukkit fires the event on, which on Folia
 * is already the entity's owning thread, so inventory reads are safe.
 */
public final class BukkitListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerDataManager dataManager;
    private final CheckManager checks;
    private final com.example.ac.violation.ViolationManager violations;
    private final LongSupplier tick;

    public BukkitListener(JavaPlugin plugin, PlayerDataManager dataManager,
                          CheckManager checks, com.example.ac.violation.ViolationManager violations,
                          LongSupplier tick) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.checks = checks;
        this.violations = violations;
        this.tick = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerData data = dataManager.getOrCreate(p.getUniqueId(), p.getName());
        dataManager.linkEntityId(data, p.getEntityId());
        violations.restore(data);
        long now = tick.getAsLong();
        data.joinTick = now;
        data.lastTeleportTick = now; // settle-in grace
        seedPosition(data, p.getLocation(), now);
        refreshHeldItem(p, data);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        dataManager.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData data = dataManager.get(e.getPlayer().getUniqueId());
        if (data == null) return;
        long now = tick.getAsLong();
        data.lastRespawnTick = now;
        seedPosition(data, e.getRespawnLocation(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData data = dataManager.get(e.getPlayer().getUniqueId());
        if (data == null || e.getTo() == null) return;
        long now = tick.getAsLong();
        data.lastTeleportTick = now;
        seedPosition(data, e.getTo(), now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        PlayerData data = dataManager.get(e.getPlayer().getUniqueId());
        if (data != null) data.lastTeleportTick = tick.getAsLong();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData data = dataManager.get(e.getPlayer().getUniqueId());
        if (data != null) data.lastVelocityTick = tick.getAsLong();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        PlayerData data = dataManager.get(e.getEntity().getUniqueId());
        if (data != null) data.lastRespawnTick = tick.getAsLong();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        // Knockback grace for the victim if it's a tracked player.
        PlayerData victim = dataManager.get(victimUuid(e));
        if (victim != null) victim.lastVelocityTick = tick.getAsLong();

        if (e.getDamager() instanceof Player attacker) {
            PlayerData ad = dataManager.get(attacker.getUniqueId());
            if (ad != null) {
                checks.onDamage(ad, e.getEntity().getEntityId(), e.getFinalDamage());
            }
        }
    }

    private static java.util.UUID victimUuid(EntityDamageByEntityEvent e) {
        return e.getEntity() instanceof Player p ? p.getUniqueId() : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        PlayerData data = dataManager.get(p.getUniqueId());
        if (data == null) return;
        ItemStack item = p.getInventory().getItem(e.getNewSlot());
        applyItemFlags(data, item);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private void seedPosition(PlayerData d, Location loc, long now) {
        d.x = d.lastX = loc.getX();
        d.y = d.lastY = loc.getY();
        d.z = d.lastZ = loc.getZ();
        d.yaw = d.lastYaw = loc.getYaw();
        d.pitch = d.lastPitch = loc.getPitch();
        d.moveAccumTick = now;
        d.moveAccumX = 0;
        d.moveAccumZ = 0;
        d.speedOverLimitStreak = 0;
    }

    /** Re-read the main-hand item and update verified weapon flags. */
    public void refreshHeldItem(Player p, PlayerData data) {
        applyItemFlags(data, p.getInventory().getItemInMainHand());
    }

    /**
     * Re-read movement-state flags the parsed packets don't carry (vehicle,
     * elytra, riptide, Speed/Jump amplifiers). Best-effort and guarded so API
     * differences across server versions can't throw.
     */
    public void refreshState(Player p, PlayerData data) {
        try { data.inVehicle = p.isInsideVehicle(); } catch (Throwable ignored) { }
        try { data.gliding = p.isGliding(); } catch (Throwable ignored) { }
        try { data.riptiding = p.isRiptiding(); } catch (Throwable ignored) { }
        data.speedAmplifier = effectAmplifier(p, "speed");
        data.jumpAmplifier = effectAmplifier(p, "jump");
    }

    private int effectAmplifier(Player p, String contains) {
        try {
            for (org.bukkit.potion.PotionEffect pe : p.getActivePotionEffects()) {
                String k;
                try {
                    k = pe.getType().getKey().getKey().toLowerCase(java.util.Locale.ROOT);
                } catch (Throwable t) {
                    k = pe.getType().getName().toLowerCase(java.util.Locale.ROOT);
                }
                if (k.contains(contains)) return pe.getAmplifier();
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    private void applyItemFlags(PlayerData data, ItemStack item) {
        String n = item == null ? "AIR" : item.getType().name();
        data.heldItemType = n;
        // Name-based so this compiles regardless of which Material constants
        // exist in your server's API (e.g. SPEAR may be datapack/mod-provided).
        data.holdingMace = n.equals("MACE");
        data.holdingAxe = n.endsWith("_AXE");
        boolean spear = n.equals("SPEAR") || n.endsWith("_SPEAR") || n.contains("SPEAR");
        data.holdingSpear = spear;
        data.holdingLungeSpear = spear && hasLungeEnchant(item);
    }

    private boolean hasLungeEnchant(ItemStack item) {
        if (item == null) return false;
        try {
            return item.getEnchantments().keySet().stream().anyMatch(ench -> {
                try {
                    return ench.getKey().getKey().toLowerCase(java.util.Locale.ROOT).contains("lunge");
                } catch (Throwable ignored) {
                    return ench.toString().toLowerCase(java.util.Locale.ROOT).contains("lunge");
                }
            });
        } catch (Throwable ignored) {
            return false;
        }
    }
}
