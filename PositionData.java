package com.example.ac.check.combat;

import com.example.ac.check.AttackContext;
import com.example.ac.check.Check;
import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.data.PositionData;
import com.example.ac.util.MathUtil;
import com.example.ac.util.SchedulerUtil;
import com.example.ac.violation.FlagResult;
import com.example.ac.violation.ViolationManager;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.function.LongSupplier;

/**
 * Check 3 — Killaura. A bundle of sub-checks, each adding to the single
 * {@code killaura} VL bucket so they corroborate one another:
 *
 *  - angle:        could the player's actual look have been aimed at the target?
 *  - raytrace:     does the look ray actually intersect the (rewound) hitbox?
 *  - multi-target: too many distinct entities struck in too short a window.
 *  - through-wall: target fully occluded by solid blocks (needs world access;
 *                  OFF by default, runs async on the entity thread).
 *  - rotation:     behavioral aim analysis over many samples — the strongest
 *                  signal against humanized aimbots (no micro-jitter / perfectly
 *                  constant angular velocity).
 *
 * The angle/raytrace/multi-target sub-checks are pure math on cached data, run
 * synchronously on the packet thread, and can CANCEL the hit. Through-wall is
 * scheduled onto the world thread and so can only contribute VL after the fact
 * (it can still escalate to a kick/ban via the VL system, but cannot un-send
 * the specific packet). Rotation analysis is fed from movement packets.
 */
public final class KillauraCheck extends Check {

    // angle
    private boolean angleEnabled;
    private double maxAngleDegrees;
    private double angleWeight;
    // raytrace
    private boolean raytraceEnabled;
    private double raytraceWeight;
    private double raytraceMaxDist = 8.0;
    private double raytraceHitboxExpansion = 0.12;
    // through-wall
    private boolean throughWallEnabled;
    private double throughWallWeight;
    // multi-target
    private boolean multiEnabled;
    private int maxTargets;
    private long multiWindowMs;
    private double multiWeight;
    // rotation behavioral
    private boolean rotationEnabled;
    private int rotationSampleSize;
    private double minJitterStddev;
    private double constantVelocityRatio;
    private double rotationWeight;

    // Below this mean absolute yaw delta we treat the player as "not actively
    // aiming" and skip behavioral verdicts, so standing still never flags.
    private static final double AIM_ACTIVITY_FLOOR = 0.05;

    public KillauraCheck(JavaPlugin plugin, ACConfig config, PlayerDataManager dm,
                         ViolationManager vm, LongSupplier tick) {
        super("killaura", plugin, config, dm, vm, tick);
        reloadSettings();
    }

    @Override
    protected void reloadSettings() {
        ConfigurationSection sub = cfg != null ? cfg.getConfigurationSection("sub-checks") : null;

        ConfigurationSection a = section(sub, "angle");
        angleEnabled = a == null || a.getBoolean("enabled", true);
        maxAngleDegrees = a != null ? a.getDouble("max-angle-degrees", 45.0) : 45.0;
        angleWeight = a != null ? a.getDouble("weight", 1.0) : 1.0;

        ConfigurationSection r = section(sub, "raytrace");
        raytraceEnabled = r == null || r.getBoolean("enabled", true);
        raytraceWeight = r != null ? r.getDouble("weight", 1.5) : 1.5;
        raytraceHitboxExpansion = r != null ? r.getDouble("hitbox-expansion", 0.12) : 0.12;

        ConfigurationSection w = section(sub, "through-wall");
        throughWallEnabled = w != null && w.getBoolean("enabled", false);
        throughWallWeight = w != null ? w.getDouble("weight", 3.0) : 3.0;

        ConfigurationSection m = section(sub, "multi-target");
        multiEnabled = m == null || m.getBoolean("enabled", true);
        maxTargets = m != null ? m.getInt("max-targets", 2) : 2;
        multiWindowMs = m != null ? m.getLong("window-ms", 150) : 150;
        multiWeight = m != null ? m.getDouble("weight", 2.0) : 2.0;

        ConfigurationSection rot = section(sub, "rotation");
        rotationEnabled = rot == null || rot.getBoolean("enabled", true);
        rotationSampleSize = rot != null ? rot.getInt("sample-size", 40) : 40;
        minJitterStddev = rot != null ? rot.getDouble("min-jitter-stddev", 0.008) : 0.008;
        constantVelocityRatio = rot != null ? rot.getDouble("constant-velocity-ratio", 0.85) : 0.85;
        rotationWeight = rot != null ? rot.getDouble("weight", 1.0) : 1.0;
    }

    private static ConfigurationSection section(ConfigurationSection parent, String name) {
        return parent == null ? null : parent.getConfigurationSection(name);
    }

    // -----------------------------------------------------------------------
    //  Attack-time sub-checks
    // -----------------------------------------------------------------------

    /** @return true if the hit should be cancelled (synchronous sub-checks). */
    public boolean handle(AttackContext ctx, Player attackerPlayer) {
        if (!enabled()) return false;
        PlayerData attacker = ctx.attacker;
        if (isExempt(attacker)) return false;

        // multi-target works even for mob victims (it only counts ids).
        boolean cancel = false;
        if (multiEnabled) {
            int distinct = attacker.distinctTargetsWithin(multiWindowMs, System.currentTimeMillis());
            if (distinct >= maxTargets) {
                FlagResult fr = flag(attacker,
                        "multi-target=" + distinct + " in " + multiWindowMs + "ms", multiWeight);
                cancel |= fr.cancel;
            }
        }

        // The hitbox sub-checks need a tracked player victim with rewind data.
        if (!ctx.hasVictimHitbox()) return cancel;

        // Pick the snapshot the player was most plausibly aiming at (min angle).
        PositionData bestSnap = null;
        double bestAngle = Double.MAX_VALUE;
        for (PositionData pd : ctx.victimWindow) {
            MathUtil.AABB box = MathUtil.AABB.player(pd.x, pd.y, pd.z);
            double ang = MathUtil.angleToBox(ctx.eye, ctx.lookDir, box);
            if (ang < bestAngle) { bestAngle = ang; bestSnap = pd; }
        }

        if (angleEnabled && bestAngle != Double.MAX_VALUE && bestAngle > maxAngleDegrees) {
            FlagResult fr = flag(attacker,
                    String.format("angle=%.1f° > %.1f°", bestAngle, maxAngleDegrees), angleWeight);
            cancel |= fr.cancel;
        }

        if (raytraceEnabled) {
            boolean anyHit = false;
            for (PositionData pd : ctx.victimWindow) {
                MathUtil.AABB box = ctx.hitbox(pd, raytraceHitboxExpansion);
                if (MathUtil.rayIntersectAABB(ctx.eye, ctx.lookDir, box, raytraceMaxDist) >= 0) {
                    anyHit = true;
                    break;
                }
            }
            if (!anyHit) {
                FlagResult fr = flag(attacker, "attack ray missed hitbox", raytraceWeight);
                cancel |= fr.cancel;
            }
        }

        // Through-wall: needs block lookups -> schedule on the world thread.
        // Cannot cancel this packet (already returned) but still raises VL.
        if (throughWallEnabled && attackerPlayer != null && bestSnap != null) {
            scheduleThroughWallCheck(attacker, attackerPlayer, ctx.eye, bestSnap);
        }

        return cancel;
    }

    private void scheduleThroughWallCheck(PlayerData attacker, Player player,
                                          MathUtil.Vec3 eye, PositionData target) {
        final double tx = target.x, ty = target.y + 0.9, tz = target.z; // ~mid hitbox
        SchedulerUtil.runForEntity(plugin, player, () -> {
            if (!player.isValid()) return;
            Location origin = new Location(player.getWorld(), eye.x, eye.y, eye.z);
            Vector dir = new Vector(tx - eye.x, ty - eye.y, tz - eye.z);
            double dist = dir.length();
            if (dist < 1.0e-4) return;
            dir.normalize();
            // FluidCollisionMode.NEVER, ignorePassableBlocks=true: only solids occlude.
            RayTraceResult res = player.getWorld().rayTraceBlocks(
                    origin, dir, dist, FluidCollisionMode.NEVER, true);
            if (res != null && res.getHitBlock() != null) {
                // A solid block sits between the eye and the target -> occluded.
                flag(attacker, "attacked target through a wall", throughWallWeight);
            }
        });
    }

    // -----------------------------------------------------------------------
    //  Behavioral rotation analysis (fed from movement packets)
    // -----------------------------------------------------------------------

    public void onRotation(PlayerData data) {
        if (!enabled() || !rotationEnabled) return;

        double yawDelta = MathUtil.wrapDegrees(data.yaw - data.lastYaw);
        double pitchDelta = data.pitch - data.lastPitch;
        data.recordRotationSample(yawDelta, pitchDelta);

        int target = Math.min(rotationSampleSize, data.yawDeltas().length);
        if (data.rotationSampleCount() < target) return;

        double[] yaws = data.yawDeltas();
        int n = target;

        // Mean absolute yaw delta: are they actually turning (aiming)?
        double meanAbs = 0.0;
        for (int i = 0; i < n; i++) meanAbs += Math.abs(yaws[i]);
        meanAbs /= n;

        if (meanAbs < AIM_ACTIVITY_FLOOR) { data.clearRotationSamples(); return; }

        // Constant angular velocity (linear interpolation / smoothing): the
        // fraction of consecutive deltas that are near-identical and nonzero.
        int constant = 0, pairs = 0;
        for (int i = 1; i < n; i++) {
            if (Math.abs(yaws[i]) < 1.0e-3) continue;
            pairs++;
            if (Math.abs(yaws[i] - yaws[i - 1]) < 1.0e-2) constant++;
        }
        double cvRatio = pairs > 0 ? (double) constant / pairs : 0.0;

        // Micro-jitter: real mouse aim has noise; an aimbot's smoothed output is
        // unnaturally clean. Low stddev *while actively aiming* is suspicious.
        double jitter = MathUtil.stddev(yaws, n);

        if (cvRatio > constantVelocityRatio) {
            flag(data, String.format("linear aim cv=%.2f", cvRatio), rotationWeight);
        } else if (jitter < minJitterStddev) {
            flag(data, String.format("no aim jitter σ=%.4f", jitter), rotationWeight);
        }

        data.clearRotationSamples();
    }
}
