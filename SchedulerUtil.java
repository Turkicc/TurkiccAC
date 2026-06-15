package com.example.ac.check.combat;

import com.example.ac.check.Check;
import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.violation.ViolationManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.LongSupplier;

/**
 * Check 5 — Silent swap.
 *
 * Silent-swap cheats flick the held hotbar slot (or offhand) to a different
 * item to perform an action, then flick straight back, so no held-item change
 * is ever visible. Server-side we can't see the client's render, but we CAN see
 * the packet timing: a slot change immediately before an action followed by a
 * revert immediately after, both within a tick or two — and repeated.
 *
 * We track the slot we were on before the last change; when a change returns us
 * to that slot within {@code window-ticks} and an attack happened in between, we
 * count one silent-swap signature. One accidental fast swap is human, so VL only
 * ramps once the signature repeats ({@code min-repeats}).
 *
 * Limitation: the spec also suggests cross-checking the server's held slot
 * against the item whose attributes were actually applied. On an authoritative
 * server those always agree (the exploit lives in the client's *visuals*), so
 * that cross-check can't fire here; the attribute-mismatch case that DOES matter
 * (mace attribute swapping) is handled by {@link MaceStunSlamCheck}. This check
 * is therefore purely the swap-timing signature.
 */
public final class SilentSwapCheck extends Check {

    private int windowTicks;
    private int minRepeats;
    private double weight;

    public SilentSwapCheck(JavaPlugin plugin, ACConfig config, PlayerDataManager dm,
                           ViolationManager vm, LongSupplier tick) {
        super("silent-swap", plugin, config, dm, vm, tick);
        reloadSettings();
    }

    @Override
    protected void reloadSettings() {
        windowTicks = cfg != null ? cfg.getInt("window-ticks", 2) : 2;
        minRepeats = cfg != null ? cfg.getInt("min-repeats", 2) : 2;
        weight = cfg != null ? cfg.getDouble("weight", 1.5) : 1.5;
    }

    /** Hotbar slot change. {@code newSlot} is the slot now selected. */
    public void onSlotChange(PlayerData data, int newSlot) {
        long t = now();
        boolean active = enabled() && !isExempt(data);

        if (active && data.lastSlotChangeTick != Long.MIN_VALUE) {
            long sinceChange = t - data.lastSlotChangeTick;
            boolean revert = newSlot == data.slotBeforeLastChange;
            boolean actionBetween = data.lastAttackTick != Long.MIN_VALUE
                    && data.lastAttackTick >= data.lastSlotChangeTick
                    && data.lastAttackTick <= t
                    && (t - data.lastAttackTick) <= windowTicks + 1;

            if (revert && sinceChange <= windowTicks && actionBetween) {
                data.silentSwapRepeats++;
                if (data.silentSwapRepeats >= minRepeats) {
                    double scale = 1.0 + Math.min(2.0, (data.silentSwapRepeats - minRepeats) * 0.5);
                    flag(data, "swap→attack→revert x" + data.silentSwapRepeats
                            + " (" + sinceChange + "t)", weight * scale);
                }
            } else if (sinceChange > (long) windowTicks * 4) {
                data.silentSwapRepeats = 0; // they stopped; let the streak lapse
            }
        }

        // Update tracking regardless of enabled state so detection is correct
        // the moment it's switched on.
        data.slotBeforeLastChange = data.heldSlot;
        data.lastHeldSlot = data.heldSlot;
        data.heldSlot = newSlot;
        data.lastSlotChangeTick = t;
    }

    /** Offhand swap (the F key / SWAP_ITEM_WITH_OFFHAND digging action). */
    public void onOffhandSwap(PlayerData data) {
        long t = now();
        if (enabled() && !isExempt(data) && data.lastOffhandSwapTick != Long.MIN_VALUE) {
            long sinceSwap = t - data.lastOffhandSwapTick;
            boolean actionBetween = data.lastAttackTick != Long.MIN_VALUE
                    && data.lastAttackTick >= data.lastOffhandSwapTick
                    && data.lastAttackTick <= t
                    && (t - data.lastAttackTick) <= windowTicks + 1;
            if (sinceSwap <= (long) windowTicks * 2 && actionBetween) {
                data.silentSwapRepeats++;
                if (data.silentSwapRepeats >= minRepeats) {
                    flag(data, "offhand swap→attack→revert x" + data.silentSwapRepeats, weight);
                }
            }
        }
        data.lastOffhandSwapTick = t;
    }
}
