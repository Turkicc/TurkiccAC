package com.example.ac.check.combat;

import com.example.ac.check.AttackContext;
import com.example.ac.check.Check;
import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.violation.FlagResult;
import com.example.ac.violation.ViolationManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.LongSupplier;

/**
 * Check 6 — Mace 1-tick stun slam.
 *
 * Abuses attribute swapping (MC-28289): switching the held hotbar slot in the
 * SAME tick as an attack makes the server read damage from the new item while
 * the cooldown was already charged on the old one. The nasty PvP combo is an
 * axe hit (disables the shield) chained instantly into a mace smash on the same
 * target — a near-unsurvivable one-tick burst that a human cannot produce by
 * switching weapons and re-charging the attack cooldown.
 *
 * Primary, high-confidence signatures (synchronous, can cancel the hit):
 *   1. A hotbar slot change on the SAME tick as the attack (the attribute-swap
 *      fingerprint). With {@code legal-swap-window-ticks: 0} every same-tick
 *      swap-attack is illegal; set it >0 if a plugin defines a legal window.
 *   2. axe(disable) -> mace(smash) on the same target within {@code max-combo-ticks}.
 *   3. A full-power mace hit landing faster than {@code full-power-cooldown-ticks}
 *      since the previous mace hit (impossible without the cooldown exploit).
 *
 * Secondary corroboration (from the Bukkit damage event): a heavy hit lands but
 * the server-side held item is no longer a mace, i.e. they swapped the mace in
 * for the attribute read and straight back out.
 */
public final class MaceStunSlamCheck extends Check {

    private int maxComboTicks;
    private int fullPowerCooldownTicks;
    private int legalSwapWindowTicks;
    private double weight;

    public MaceStunSlamCheck(JavaPlugin plugin, ACConfig config, PlayerDataManager dm,
                             ViolationManager vm, LongSupplier tick) {
        super("mace-stun-slam", plugin, config, dm, vm, tick);
        reloadSettings();
    }

    @Override
    protected void reloadSettings() {
        maxComboTicks = cfg != null ? cfg.getInt("max-combo-ticks", 1) : 1;
        fullPowerCooldownTicks = cfg != null ? cfg.getInt("full-power-cooldown-ticks", 1) : 1;
        legalSwapWindowTicks = cfg != null ? cfg.getInt("legal-swap-window-ticks", 0) : 0;
        weight = cfg != null ? cfg.getDouble("weight", 2.0) : 2.0;
    }

    /** @return true if the hit should be cancelled. */
    public boolean handle(AttackContext ctx) {
        PlayerData a = ctx.attacker;
        long tick = ctx.tick;
        if (!enabled() || isExempt(a)) {
            updateWeaponTicks(a, tick);
            return false;
        }

        boolean cancel = false;

        // 1) same-tick slot change as this attack (attribute-swap fingerprint).
        if (a.holdingMace && a.lastSlotChangeTick == tick) {
            long gap = tick - a.lastSlotChangeTick; // == 0
            if (legalSwapWindowTicks == 0 || gap < legalSwapWindowTicks) {
                FlagResult r = flag(a,
                        "same-tick swap on attack (attribute-swap, slot " + a.heldSlot + ")", weight);
                cancel |= r.cancel;
            }
        }

        // 2) axe(shield-disable) -> mace(smash) on the SAME target, too fast.
        if (a.holdingMace && a.lastAxeAttackTick != Long.MIN_VALUE
                && (tick - a.lastAxeAttackTick) <= maxComboTicks
                && a.lastAttackedEntityId == ctx.targetEntityId) {
            FlagResult r = flag(a,
                    "axe→mace combo in " + (tick - a.lastAxeAttackTick) + "t on same target",
                    weight * 1.5);
            cancel |= r.cancel;
        }

        // 3) full-power mace smash faster than the weapon cooldown allows.
        if (a.holdingMace && a.lastMaceAttackTick != Long.MIN_VALUE
                && (tick - a.lastMaceAttackTick) <= fullPowerCooldownTicks) {
            FlagResult r = flag(a,
                    "mace hit " + (tick - a.lastMaceAttackTick) + "t <= fast-slam limit "
                            + fullPowerCooldownTicks + "t", weight);
            cancel |= r.cancel;
        }

        updateWeaponTicks(a, tick);
        return cancel;
    }

    private void updateWeaponTicks(PlayerData a, long tick) {
        if (a.holdingMace) a.lastMaceAttackTick = tick;
        if (a.holdingAxe) a.lastAxeAttackTick = tick;
    }

    /**
     * Secondary cross-check from {@code EntityDamageByEntityEvent}. A mace smash
     * deals heavy damage; if the post-mitigation damage is high but the attacker
     * is no longer holding a mace server-side, they swapped the mace in only for
     * the attribute read. Best-effort corroboration, so a small weight.
     */
    public void onDamage(PlayerData attacker, int victimEntityId, double finalDamage) {
        if (!enabled() || isExempt(attacker)) return;
        long t = now();
        boolean recentMace = attacker.lastMaceAttackTick != Long.MIN_VALUE
                && (t - attacker.lastMaceAttackTick) <= maxComboTicks + 1;
        if (recentMace && !attacker.holdingMace && finalDamage >= 9.0) {
            flag(attacker, String.format(
                    "heavy hit %.1f with mace swapped out (dmg=mace, held≠mace)", finalDamage),
                    weight);
        }
    }
}
