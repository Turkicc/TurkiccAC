package com.example.ac.check.movement;

import com.example.ac.check.Check;
import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.violation.FlagResult;
import com.example.ac.violation.ViolationManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.LongSupplier;

/**
 * Check 2 — Speed.
 *
 * Two engines, selected by {@code use-simulation}:
 *
 *  - Simulation (preferred): delegates to {@link SimulationEngine}. That is the
 *    correct, effectively-unbypassable approach, but the engine ships as a stub
 *    (see its javadoc), so this path only does anything once you implement it.
 *
 *  - Threshold (fallback, default): each tick we compute the maximum legal
 *    horizontal displacement for the player's current state and compare it to
 *    the actual displacement. We accumulate per-packet motion and evaluate once
 *    per tick (a cheat can't dodge by splitting a tick's motion across packets).
 *
 * The threshold model is intentionally generous — it does NOT know the block
 * under the player (no world replica), so ice/slime/soul-sand and similar are
 * absorbed by {@code threshold-multiplier} and the air/sprint-jump allowance
 * rather than modelled exactly. That keeps false positives low at the cost of
 * some sensitivity; the simulation engine is where you buy precision back.
 *
 * False-positive guards: a velocity grace window after knockback/explosions/
 * teleports, skipping riptide/elytra bursts, and a consecutive-tick streak so a
 * single lag spike never flags.
 */
public final class SpeedCheck extends Check {

    // Vanilla-ish horizontal ground speeds (blocks/tick). Approximate on purpose.
    private static final double WALK = 0.221;
    private static final double SPRINT = 0.2873;

    private final SimulationEngine simulation;

    private boolean useSimulation;
    private double thresholdMultiplier;
    private int minConsecutiveTicks;
    private int velocityGraceTicks;
    // Extra headroom while airborne to cover sprint-jump (bunny-hop) bursts,
    // which legitimately exceed flat-ground sprint speed for a tick or two.
    private double sprintJumpMultiplier = 1.9;

    public SpeedCheck(JavaPlugin plugin, ACConfig config, PlayerDataManager dm,
                      ViolationManager vm, LongSupplier tick, SimulationEngine simulation) {
        super("speed", plugin, config, dm, vm, tick);
        this.simulation = simulation;
        reloadSettings();
    }

    @Override
    protected void reloadSettings() {
        useSimulation = cfg != null && cfg.getBoolean("use-simulation", false);
        thresholdMultiplier = cfg != null ? cfg.getDouble("threshold-multiplier", 1.10) : 1.10;
        minConsecutiveTicks = cfg != null ? cfg.getInt("min-consecutive-ticks", 3) : 3;
        velocityGraceTicks = cfg != null ? cfg.getInt("velocity-grace-ticks", 10) : 10;
        sprintJumpMultiplier = cfg != null ? cfg.getDouble("sprint-jump-multiplier", 1.9) : 1.9;
    }

    /**
     * Called for every position-bearing movement packet. Accumulates this
     * packet's displacement into the current tick and, when the tick rolls
     * over, evaluates the completed tick.
     *
     * @return true if a set-back should be applied (caller teleports the player
     *         back to their last legal position).
     */
    public boolean handle(PlayerData data) {
        if (!enabled() || isExempt(data)) {
            // keep accumulation coherent even while exempt
            data.moveAccumTick = now();
            data.moveAccumX = 0;
            data.moveAccumZ = 0;
            return false;
        }

        double dx = data.x - data.lastX;
        double dz = data.z - data.lastZ;
        long t = now();

        if (data.moveAccumTick == Long.MIN_VALUE) {
            data.moveAccumTick = t;
            data.moveAccumX = dx;
            data.moveAccumZ = dz;
            return false;
        }

        if (t == data.moveAccumTick) {
            data.moveAccumX += dx;
            data.moveAccumZ += dz;
            return false;
        }

        // Tick rolled over: evaluate the tick that just finished, then start a
        // fresh accumulation seeded with the current packet's delta.
        double accX = data.moveAccumX;
        double accZ = data.moveAccumZ;
        long evaluatedTick = data.moveAccumTick;
        double accY = data.y - data.lastY; // last packet's vertical, best-effort

        data.moveAccumTick = t;
        data.moveAccumX = dx;
        data.moveAccumZ = dz;

        return evaluate(data, accX, accY, accZ, evaluatedTick);
    }

    private boolean evaluate(PlayerData data, double dx, double dy, double dz, long tick) {
        // Skip ticks dominated by external velocity or special movement modes.
        if (data.riptiding || data.gliding) { data.speedOverLimitStreak = 0; return false; }
        if (data.lastVelocityTick != Long.MIN_VALUE
                && now() - data.lastVelocityTick < velocityGraceTicks) {
            data.speedOverLimitStreak = 0;
            return false;
        }

        double horizontal = Math.sqrt(dx * dx + dz * dz);

        // ---- simulation path (preferred, stubbed) -------------------------
        if (useSimulation) {
            SimulationEngine.Result r = simulation.verify(data, dx, dy, dz, tick);
            if (r.verdict == SimulationEngine.Verdict.ILLEGAL) {
                return flagSpeed(data, String.format("sim illegal Δh=%.3f (%s)", horizontal, r.detail),
                        1.0 + Math.min(3.0, r.overage * 5.0));
            }
            if (r.verdict == SimulationEngine.Verdict.LEGAL) {
                data.speedOverLimitStreak = 0;
                return false;
            }
            // UNAVAILABLE -> fall through to thresholds.
        }

        // ---- threshold path (fallback) ------------------------------------
        double base = data.sprinting ? SPRINT : WALK;
        if (data.speedAmplifier >= 0) base *= (1.0 + 0.2 * (data.speedAmplifier + 1));
        double allowed = base * thresholdMultiplier;
        if (!data.onGround) allowed = base * sprintJumpMultiplier * thresholdMultiplier;

        if (horizontal > allowed) {
            data.speedOverLimitStreak++;
            if (data.speedOverLimitStreak >= minConsecutiveTicks) {
                double overage = horizontal - allowed;
                double weight = 1.0 + Math.min(3.0, overage * 6.0);
                return flagSpeed(data, String.format("Δh=%.3f > max %.3f (x%d)",
                        horizontal, allowed, data.speedOverLimitStreak), weight);
            }
        } else {
            data.speedOverLimitStreak = 0;
        }
        return false;
    }

    private boolean flagSpeed(PlayerData data, String reason, double weight) {
        FlagResult r = flag(data, reason, weight);
        return r.cancel; // CANCEL tier == set the player back
    }
}
