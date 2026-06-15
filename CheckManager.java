package com.example.ac.check.combat;

import com.example.ac.check.AttackContext;
import com.example.ac.check.Check;
import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.data.PositionData;
import com.example.ac.util.MathUtil;
import com.example.ac.violation.FlagResult;
import com.example.ac.violation.ViolationManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.LongSupplier;

/**
 * Check 1 — Reach.
 *
 * On an attack we already have the attacker's last-known eye position and the
 * victim rewound to where the attacker saw them. We measure the CLOSEST
 * distance from the eye to the victim hitbox (the nearest point of the AABB):
 * the player physically cannot be closer than that, so if even the nearest
 * point is beyond the legal limit the hit is illegitimate. Evaluated across the
 * whole rewind window and we keep the smallest distance, so high-ping players
 * get the benefit of the doubt.
 *
 * Position-spoof reach (sending a fake-closer position for the attack tick) is
 * NOT caught here on purpose — it shows up as illegal movement and is caught by
 * the Speed/movement check. This check catches the common case where the client
 * simply attacks an entity that is genuinely too far.
 */
public final class ReachCheck extends Check {

    private double maxDistance;
    private double tolerance;
    private double hitboxExpansion;

    public ReachCheck(JavaPlugin plugin, ACConfig config, PlayerDataManager dm,
                      ViolationManager vm, LongSupplier tick) {
        super("reach", plugin, config, dm, vm, tick);
        reloadSettings();
    }

    @Override
    protected void reloadSettings() {
        maxDistance = cfg != null ? cfg.getDouble("max-distance", 3.0) : 3.0;
        tolerance = cfg != null ? cfg.getDouble("tolerance", 0.03) : 0.03;
        hitboxExpansion = cfg != null ? cfg.getDouble("hitbox-expansion", 0.03) : 0.03;
    }

    /** @return true if the attack should be cancelled. */
    public boolean handle(AttackContext ctx) {
        if (!enabled() || !ctx.hasVictimHitbox()) return false;
        PlayerData attacker = ctx.attacker;
        if (isExempt(attacker)) return false;

        double limit = maxDistance + tolerance;
        double best = Double.MAX_VALUE;
        for (PositionData pd : ctx.victimWindow) {
            MathUtil.AABB box = ctx.hitbox(pd, hitboxExpansion);
            double d = MathUtil.distancePointToAABB(ctx.eye, box);
            if (d < best) best = d;
        }
        if (best == Double.MAX_VALUE) return false;

        if (best > limit) {
            // Weight scales mildly with how far past the limit they are, so a
            // 3.6-block hit ramps faster than a 3.05 borderline one.
            double overage = best - limit;
            double weight = 1.0 + Math.min(3.0, overage * 4.0);
            FlagResult r = flag(attacker,
                    String.format("reach=%.3f (limit %.2f)", best, limit), weight);
            return r.cancel;
        }
        return false;
    }
}
