package com.example.ac.check.movement;

import com.example.ac.data.PlayerData;

/**
 * Hook for the "gold standard" movement check described in the build spec: a
 * 1:1 replication of the player's physically-possible movement each tick.
 * Instead of a threshold, you simulate every legal outcome given the player's
 * inputs, knockback, collisions, fluids, vehicles and block effects, then check
 * whether the player's ACTUAL displacement falls inside that legal set. A
 * correct simulation is effectively unbypassable because it encodes all legal
 * moves; the threshold model in {@link SpeedCheck} is only the fallback.
 *
 * <p><b>This is deliberately a stub.</b> A faithful engine is thousands of lines
 * and needs the per-player world replica (block states queried off-thread) plus
 * full reimplementation of vanilla {@code LivingEntity}/{@code Player} movement
 * physics for the exact MC version. Building that is a project in itself and is
 * out of scope for this deliverable, so {@link #verify} returns
 * {@link Result#UNAVAILABLE} until you implement it. {@code SpeedCheck} treats
 * UNAVAILABLE as "fall back to thresholds", so the plugin works today and you
 * can grow into the engine later by:
 *   1. building {@code WorldReplica} from chunk/block-change packets,
 *   2. porting per-tick physics (input -> friction -> gravity -> collide),
 *   3. returning a tight legal displacement bound here.
 */
public interface SimulationEngine {

    enum Verdict { LEGAL, ILLEGAL, UNAVAILABLE }

    final class Result {
        public final Verdict verdict;
        /** How far past the legal envelope (blocks), when ILLEGAL; else 0. */
        public final double overage;
        public final String detail;

        public Result(Verdict verdict, double overage, String detail) {
            this.verdict = verdict;
            this.overage = overage;
            this.detail = detail;
        }

        public static final Result UNAVAILABLE =
                new Result(Verdict.UNAVAILABLE, 0, "simulation engine not implemented");
        public static Result legal() { return new Result(Verdict.LEGAL, 0, "ok"); }
        public static Result illegal(double overage, String detail) {
            return new Result(Verdict.ILLEGAL, overage, detail);
        }
    }

    /**
     * Verify a player's movement for the just-completed tick.
     *
     * @param data        the player
     * @param dx,dy,dz    actual displacement this tick (blocks)
     * @param tick        the tick being evaluated
     */
    Result verify(PlayerData data, double dx, double dy, double dz, long tick);

    /** The always-available no-op used until a real engine is dropped in. */
    final class Unimplemented implements SimulationEngine {
        @Override
        public Result verify(PlayerData data, double dx, double dy, double dz, long tick) {
            return Result.UNAVAILABLE;
        }
    }
}
