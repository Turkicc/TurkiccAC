package com.example.ac.data;

import com.example.ac.util.MathUtil;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All mutable per-player state the checks read and write.
 *
 * Concurrency: PacketEvents fires packets for a single player on a single
 * Netty thread, so per-player fields written only from packet handlers don't
 * need locks against themselves. The VL map and the position ring buffer are,
 * however, also read by the violation-decay task and (for the buffer) by other
 * players' combat checks during lag-comp rewind, so those use thread-safe
 * structures / volatile snapshots.
 */
public final class PlayerData {

    public final UUID uuid;
    public final String name;
    /** Entity id of this player, used to resolve attack targets to PlayerData. */
    public volatile int entityId = -1;

    // ---- lag-compensation ring buffer -------------------------------------
    private final PositionData[] history;
    private volatile int historyIndex = 0;
    private volatile int historySize = 0;

    // ---- live movement state (written from packet thread) -----------------
    public volatile double x, y, z;
    public volatile double lastX, lastY, lastZ;
    public volatile float yaw, pitch;
    public volatile float lastYaw, lastPitch;
    public volatile boolean onGround = true;
    public volatile boolean sprinting = false;
    public volatile boolean sneaking = false;
    public volatile boolean hasPositionThisTick = false;

    // movement-state flags set from Bukkit (best-effort, refreshed periodically)
    public volatile boolean inVehicle = false;
    public volatile boolean gliding = false;       // elytra
    public volatile boolean riptiding = false;
    public volatile boolean onClimbable = false;
    public volatile double speedAttribute = 0.0;   // movement speed attribute bonus
    public volatile int speedAmplifier = -1;        // Speed potion amplifier, -1 = none
    public volatile int jumpAmplifier = -1;

    // ---- timing (server ticks) --------------------------------------------
    public volatile long lastAttackTick = Long.MIN_VALUE;
    public volatile int lastAttackedEntityId = -1;
    public volatile long lastLungeTick = Long.MIN_VALUE;
    public volatile long lastVelocityTick = Long.MIN_VALUE; // kb/explosion/teleport
    public volatile long joinTick = 0;
    public volatile long lastTeleportTick = Long.MIN_VALUE;
    public volatile long lastRespawnTick = Long.MIN_VALUE;

    // Per-weapon attack ticks, used by the mace 1-tick-slam check to spot an
    // axe(shield-disable) -> mace(smash) chain that's faster than a legal
    // switch + cooldown. Set by the mace check itself at attack time.
    public volatile long lastAxeAttackTick = Long.MIN_VALUE;
    public volatile long lastMaceAttackTick = Long.MIN_VALUE;

    // ---- inventory / swap tracking ----------------------------------------
    public volatile int heldSlot = 0;
    public volatile int lastHeldSlot = 0;
    public volatile long lastSlotChangeTick = Long.MIN_VALUE;
    public volatile long lastOffhandSwapTick = Long.MIN_VALUE;
    /** Slot we were on BEFORE the most recent slot change (for revert detection). */
    public volatile int slotBeforeLastChange = 0;
    /** Consecutive silent-swap signatures observed (decays via the VL system). */
    public int silentSwapRepeats = 0;
    /** Material name currently held in main hand (refreshed from Bukkit). */
    public volatile String heldItemType = "AIR";
    // Server-verified weapon categories for the current main-hand item.
    // Refreshed on slot change + periodically from the Bukkit thread; never
    // trusted from the client. Used by mace/lunge/swap checks.
    public volatile boolean holdingMace = false;
    public volatile boolean holdingAxe = false;
    public volatile boolean holdingSpear = false;
    public volatile boolean holdingLungeSpear = false; // spear WITH the Lunge enchant

    // ---- consecutive-tick counters used by threshold checks ---------------
    public int speedOverLimitStreak = 0;
    // Per-tick horizontal-movement accumulation (the client may send several
    // movement packets per tick; we sum them and evaluate once the tick rolls
    // over so a cheat can't dodge by splitting one tick's motion into packets).
    public double moveAccumX = 0.0, moveAccumZ = 0.0;
    public long moveAccumTick = Long.MIN_VALUE;

    // ---- ping --------------------------------------------------------------
    public volatile int pingMs = 0;

    // ---- lag / FPS packet-gap grace ---------------------------------------
    /** Wall-clock ms of the last received packet (0 = none yet). */
    public volatile long lastPacketMs = 0L;
    /** Checks are exempt while the current tick is below this (set on a gap). */
    public volatile long lagGraceUntilTick = Long.MIN_VALUE;

    // ---- per-check violation levels ---------------------------------------
    private final ConcurrentHashMap<String, Double> violations = new ConcurrentHashMap<>();

    // ---- rotation behavioral sample buffer (killaura.rotation) ------------
    private final double[] yawDeltaSamples;
    private final double[] pitchDeltaSamples;
    private int rotSampleCount = 0;
    private int rotSampleIndex = 0;

    // ---- recent-attack ring (killaura.multi-target) -----------------------
    // Small fixed ring of (entityId, wall-clock ms) for the last few attacks,
    // so we can count distinct targets struck inside a short time window.
    private static final int ATTACK_RING = 16;
    private final int[] attackTargets = new int[ATTACK_RING];
    private final long[] attackTimesMs = new long[ATTACK_RING];
    private int attackRingIndex = 0;
    private int attackRingSize = 0;

    public PlayerData(UUID uuid, String name, int historyTicks, int rotationSampleSize) {
        this.uuid = uuid;
        this.name = name;
        this.history = new PositionData[Math.max(4, historyTicks)];
        this.yawDeltaSamples = new double[Math.max(8, rotationSampleSize)];
        this.pitchDeltaSamples = new double[Math.max(8, rotationSampleSize)];
    }

    // -----------------------------------------------------------------------
    //  Ring buffer
    // -----------------------------------------------------------------------

    /** Push a snapshot. Called once whenever a position/rotation packet arrives. */
    public void recordHistory(PositionData pd) {
        synchronized (history) {
            history[historyIndex] = pd;
            historyIndex = (historyIndex + 1) % history.length;
            if (historySize < history.length) historySize++;
        }
    }

    /**
     * Find the buffered snapshot whose tick is closest to {@code targetTick}.
     * Returns null if the buffer is empty. Used to rewind a victim for lag comp.
     */
    public PositionData getClosestToTick(long targetTick) {
        synchronized (history) {
            PositionData best = null;
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < historySize; i++) {
                PositionData pd = history[i];
                if (pd == null) continue;
                long diff = Math.abs(pd.tick - targetTick);
                if (diff < bestDiff) { bestDiff = diff; best = pd; }
            }
            return best;
        }
    }

    /**
     * Snapshot of all buffered positions within +/- window of targetTick,
     * so a reach check can pick the most attacker-favorable rewind.
     */
    public PositionData[] getWindow(long targetTick, int window) {
        synchronized (history) {
            PositionData[] out = new PositionData[historySize];
            int n = 0;
            for (int i = 0; i < historySize; i++) {
                PositionData pd = history[i];
                if (pd == null) continue;
                if (Math.abs(pd.tick - targetTick) <= window) out[n++] = pd;
            }
            return Arrays.copyOf(out, n);
        }
    }

    // -----------------------------------------------------------------------
    //  Violations
    // -----------------------------------------------------------------------

    public double addViolation(String check, double amount, double max) {
        return violations.merge(check, amount, (a, b) -> Math.min(max, a + b));
    }

    public double getViolation(String check) {
        return violations.getOrDefault(check, 0.0);
    }

    public void decayViolation(String check, double amount) {
        violations.computeIfPresent(check, (k, v) -> {
            double nv = v - amount;
            return nv <= 0.0 ? null : nv;
        });
    }

    public ConcurrentHashMap<String, Double> getViolations() { return violations; }

    /** Set a check's VL directly (used to restore saved violations on join). */
    public void restoreViolation(String check, double vl) {
        if (vl > 0.0) violations.put(check, vl);
    }

    // -----------------------------------------------------------------------
    //  Rotation samples
    // -----------------------------------------------------------------------

    public void recordRotationSample(double yawDelta, double pitchDelta) {
        yawDeltaSamples[rotSampleIndex] = yawDelta;
        pitchDeltaSamples[rotSampleIndex] = pitchDelta;
        rotSampleIndex = (rotSampleIndex + 1) % yawDeltaSamples.length;
        if (rotSampleCount < yawDeltaSamples.length) rotSampleCount++;
    }

    public int rotationSampleCount() { return rotSampleCount; }
    public double[] yawDeltas() { return yawDeltaSamples; }
    public double[] pitchDeltas() { return pitchDeltaSamples; }
    public void clearRotationSamples() { rotSampleCount = 0; rotSampleIndex = 0; }

    // -----------------------------------------------------------------------
    //  Recent attacks (multi-target)
    // -----------------------------------------------------------------------

    /** Record that this player just attacked {@code entityId} at {@code timeMs}. */
    public void recordAttack(int entityId, long timeMs) {
        attackTargets[attackRingIndex] = entityId;
        attackTimesMs[attackRingIndex] = timeMs;
        attackRingIndex = (attackRingIndex + 1) % ATTACK_RING;
        if (attackRingSize < ATTACK_RING) attackRingSize++;
    }

    /** Count distinct entity ids attacked within the last {@code windowMs}. */
    public int distinctTargetsWithin(long windowMs, long nowMs) {
        int count = 0;
        int[] seen = new int[ATTACK_RING];
        int seenN = 0;
        for (int i = 0; i < attackRingSize; i++) {
            if (nowMs - attackTimesMs[i] > windowMs) continue;
            int id = attackTargets[i];
            boolean dup = false;
            for (int j = 0; j < seenN; j++) if (seen[j] == id) { dup = true; break; }
            if (!dup) { seen[seenN++] = id; count++; }
        }
        return count;
    }

    // -----------------------------------------------------------------------
    //  Convenience
    // -----------------------------------------------------------------------

    public double eyeHeight() {
        return sneaking ? MathUtil.EYE_HEIGHT_SNEAK : MathUtil.EYE_HEIGHT;
    }

    public MathUtil.Vec3 eyePosition() {
        return new MathUtil.Vec3(x, y + eyeHeight(), z);
    }
}
