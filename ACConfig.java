package com.example.ac.check;

import com.example.ac.data.PlayerData;
import com.example.ac.data.PositionData;
import com.example.ac.util.MathUtil;

/**
 * Everything a combat check needs about a single attack, computed once by
 * {@link com.example.ac.manager.CheckManager} and reused so we don't rewind the
 * victim multiple times.
 *
 * The {@link #victimWindow} is the set of buffered victim snapshots around the
 * tick the attacker would have seen (now - ping). Checks evaluate against the
 * MOST attacker-favorable snapshot in this window, which is what keeps high-ping
 * players from being false-flagged.
 */
public final class AttackContext {

    public final PlayerData attacker;
    public final PlayerData victim;        // null if target isn't a tracked player
    public final int targetEntityId;
    public final PositionData[] victimWindow; // null/empty if victim untracked
    public final MathUtil.Vec3 eye;
    public final MathUtil.Vec3 lookDir;
    public final long tick;
    public final int attackerPing;

    public AttackContext(PlayerData attacker, PlayerData victim, int targetEntityId,
                         PositionData[] victimWindow, MathUtil.Vec3 eye,
                         MathUtil.Vec3 lookDir, long tick, int attackerPing) {
        this.attacker = attacker;
        this.victim = victim;
        this.targetEntityId = targetEntityId;
        this.victimWindow = victimWindow;
        this.eye = eye;
        this.lookDir = lookDir;
        this.tick = tick;
        this.attackerPing = attackerPing;
    }

    public boolean hasVictimHitbox() {
        return victimWindow != null && victimWindow.length > 0;
    }

    /** Victim hitbox for a given snapshot, expanded by {@code expand} blocks. */
    public MathUtil.AABB hitbox(PositionData pd, double expand) {
        return MathUtil.AABB.player(pd.x, pd.y, pd.z).expand(expand);
    }
}
