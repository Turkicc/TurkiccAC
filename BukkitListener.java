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
 * Check 7 — Lunge swapping / lunge-cooldown bypass.
 *
 * The spear's exclusive Lunge enchant dashes the player on a left-click and then
 * goes on cooldown. Cheats re-fire the lunge before that cooldown elapses, by
 * either swap-tricking (swap a fresh lunge spear in) or a modded client that
 * suppresses the cooldown. We stay METHOD-AGNOSTIC: the server treats the lunge
 * cooldown as authoritative and flags any lunge-spear activation that arrives
 * sooner than {@code lunge-cooldown-ticks} (minus a small ping tolerance),
 * regardless of how the client achieved it.
 *
 * A lunge activation is observed as an attack OR a swing while a lunge-enchanted
 * spear is held (we guard so the attack packet and its animation packet in the
 * same tick count as one activation).
 *
 * Limitation: distinguishing a true lunge *dash* from an ordinary *jab* with the
 * same weapon precisely needs deeper movement/effect hooks (a dash imparts
 * velocity; a jab does not). This interval model assumes lunge-spear left-clicks
 * are lunge attempts — so set {@code lunge-cooldown-ticks} to your server's real
 * lunge cooldown. If your jab cadence is legitimately faster than that, wire the
 * dash-velocity confirmation in before tightening this.
 */
public final class LungeSwapCheck extends Check {

    private int lungeCooldownTicks;
    private int cooldownToleranceTicks;
    private double weight;

    public LungeSwapCheck(JavaPlugin plugin, ACConfig config, PlayerDataManager dm,
                          ViolationManager vm, LongSupplier tick) {
        super("lunge-bypass", plugin, config, dm, vm, tick);
        reloadSettings();
    }

    @Override
    protected void reloadSettings() {
        lungeCooldownTicks = cfg != null ? cfg.getInt("lunge-cooldown-ticks", 20) : 20;
        cooldownToleranceTicks = cfg != null ? cfg.getInt("cooldown-tolerance-ticks", 2) : 2;
        weight = cfg != null ? cfg.getDouble("weight", 2.0) : 2.0;
    }

    /** Lunge attempt arriving via an attack. @return true to cancel the hit. */
    public boolean handleAttack(AttackContext ctx) {
        return tryLunge(ctx.attacker, ctx.tick);
    }

    /** Lunge attempt arriving via a swing (dash into open air). */
    public void onSwing(PlayerData data) {
        tryLunge(data, now());
    }

    private boolean tryLunge(PlayerData data, long tick) {
        if (!enabled() || isExempt(data)) return false;
        if (!data.holdingLungeSpear) return false;
        // Count at most one activation per tick (attack + animation arrive together).
        if (data.lastLungeTick == tick) return false;

        boolean cancel = false;
        if (data.lastLungeTick != Long.MIN_VALUE) {
            long since = tick - data.lastLungeTick;
            if (since < (lungeCooldownTicks - cooldownToleranceTicks)) {
                FlagResult r = flag(data,
                        "lunge re-fired in " + since + "t < cooldown " + lungeCooldownTicks + "t",
                        weight);
                cancel = r.cancel;
            }
        }
        data.lastLungeTick = tick;
        return cancel;
    }
}
