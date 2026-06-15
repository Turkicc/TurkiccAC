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
 * Check 4 — Silent aim.
 *
 * Silent aim spoofs the rotation sent to the server so hits land on a target
 * the camera isn't pointing at. Because the server is authoritative about which
 * rotation it received, we can verify it directly: the reported look ray must
 * come within a tight cone of the (rewound) victim hitbox. If the reported
 * rotation points away from the victim yet the attack still registered on them,
 * that's silent aim.
 *
 * This cone ({@code max-miss-degrees}) is tighter than the killaura {@code angle}
 * sub-check on purpose: here we expect a genuine near-hit, and we route to the
 * high-confidence {@code hard} punishment tier.
 *
 * Note on the "snap onto target for one tick then revert" variant: catching that
 * precisely needs correlating the rotation at the exact attack tick against the
 * surrounding ticks. The synchronous miss-angle test below is the high-value,
 * low-false-positive core; the {@code snap-revert-window-ticks} knob is wired
 * through for when you extend this with per-tick rotation correlation, and the
 * killaura behavioral analyzer already catches single-tick snaps statistically.
 */
public final class SilentAimCheck extends Check {

    private double maxMissDegrees;
    private double hitboxExpansion;
    private int snapRevertWindowTicks; // reserved for snap-revert extension
    private double weight;

    public SilentAimCheck(JavaPlugin plugin, ACConfig config, PlayerDataManager dm,
                          ViolationManager vm, LongSupplier tick) {
        super("silent-aim", plugin, config, dm, vm, tick);
        reloadSettings();
    }

    @Override
    protected void reloadSettings() {
        maxMissDegrees = cfg != null ? cfg.getDouble("max-miss-degrees", 20.0) : 20.0;
        hitboxExpansion = cfg != null ? cfg.getDouble("hitbox-expansion", 0.18) : 0.18;
        snapRevertWindowTicks = cfg != null ? cfg.getInt("snap-revert-window-ticks", 2) : 2;
        weight = cfg != null ? cfg.getDouble("weight", 1.2) : 1.2;
    }

    /** @return true if the hit should be cancelled. */
    public boolean handle(AttackContext ctx) {
        if (!enabled() || !ctx.hasVictimHitbox()) return false;
        PlayerData attacker = ctx.attacker;
        if (isExempt(attacker)) return false;

        // Most-favorable interpretation: smallest miss angle across the window.
        double bestAngle = Double.MAX_VALUE;
        for (PositionData pd : ctx.victimWindow) {
            MathUtil.AABB box = ctx.hitbox(pd, hitboxExpansion);
            double ang = MathUtil.angleToBox(ctx.eye, ctx.lookDir, box);
            if (ang < bestAngle) bestAngle = ang;
        }
        if (bestAngle == Double.MAX_VALUE) return false;

        if (bestAngle > maxMissDegrees) {
            // Reported camera misses the victim, yet the server got an attack on
            // them: the rotation used for hit resolution wasn't the camera's.
            double over = bestAngle - maxMissDegrees;
            double w = weight * (1.0 + Math.min(2.0, over / maxMissDegrees));
            FlagResult r = flag(attacker,
                    String.format("hit at %.1f° off-aim (max %.1f°)", bestAngle, maxMissDegrees), w);
            return r.cancel;
        }
        return false;
    }
}
