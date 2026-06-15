package com.example.ac.manager;

import com.example.ac.check.AttackContext;
import com.example.ac.check.Check;
import com.example.ac.check.combat.KillauraCheck;
import com.example.ac.check.combat.LungeSwapCheck;
import com.example.ac.check.combat.MaceStunSlamCheck;
import com.example.ac.check.combat.ReachCheck;
import com.example.ac.check.combat.SilentAimCheck;
import com.example.ac.check.combat.SilentSwapCheck;
import com.example.ac.check.movement.SimulationEngine;
import com.example.ac.check.movement.SpeedCheck;
import com.example.ac.config.ACConfig;
import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.data.PositionData;
import com.example.ac.util.MathUtil;
import com.example.ac.violation.ViolationManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Owns every check, turns raw packet events into check calls, and builds the
 * shared {@link AttackContext} (resolving + rewinding the victim) exactly once
 * per attack. Also serves per-check decay rates to the {@link ViolationManager}.
 *
 * Threading: every method here runs on the caller's thread — almost always a
 * PacketEvents Netty thread — operating on thread-safe cached {@link PlayerData}.
 * Nothing here touches the live Bukkit world; checks that must (killaura's
 * through-wall) hop threads internally.
 */
public final class CheckManager implements ViolationManager.ViolationDecayLookup {

    private final ACConfig config;
    private final PlayerDataManager dataManager;
    private final LongSupplier tickSupplier;

    private final ReachCheck reach;
    private final SpeedCheck speed;
    private final KillauraCheck killaura;
    private final SilentAimCheck silentAim;
    private final SilentSwapCheck silentSwap;
    private final MaceStunSlamCheck mace;
    private final LungeSwapCheck lunge;

    private final List<Check> all;
    private final Map<String, Check> byName = new HashMap<>();

    public CheckManager(JavaPlugin plugin, ACConfig config,
                        PlayerDataManager dataManager, ViolationManager violations,
                        LongSupplier tickSupplier) {
        this.config = config;
        this.dataManager = dataManager;
        this.tickSupplier = tickSupplier;

        // The movement simulation engine is a stub until you build it out; the
        // speed check falls back to thresholds while it reports UNAVAILABLE.
        SimulationEngine simulation = new SimulationEngine.Unimplemented();

        this.reach = new ReachCheck(plugin, config, dataManager, violations, tickSupplier);
        this.speed = new SpeedCheck(plugin, config, dataManager, violations, tickSupplier, simulation);
        this.killaura = new KillauraCheck(plugin, config, dataManager, violations, tickSupplier);
        this.silentAim = new SilentAimCheck(plugin, config, dataManager, violations, tickSupplier);
        this.silentSwap = new SilentSwapCheck(plugin, config, dataManager, violations, tickSupplier);
        this.mace = new MaceStunSlamCheck(plugin, config, dataManager, violations, tickSupplier);
        this.lunge = new LungeSwapCheck(plugin, config, dataManager, violations, tickSupplier);

        this.all = List.of(reach, speed, killaura, silentAim, silentSwap, mace, lunge);
        for (Check c : all) byName.put(c.name(), c);
    }

    public void reload() {
        for (Check c : all) c.reload();
    }

    // -----------------------------------------------------------------------
    //  Combat
    // -----------------------------------------------------------------------

    /**
     * Process an attack (InteractEntity/ATTACK). Builds the rewound victim
     * context and runs every combat check.
     *
     * @return true if the attack packet should be cancelled.
     */
    public boolean onAttack(PlayerData attacker, int targetEntityId, Player attackerPlayer) {
        long now = tickSupplier.getAsLong();

        // Record for the multi-target sub-check (includes the current target).
        attacker.recordAttack(targetEntityId, System.currentTimeMillis());

        int ping = attacker.pingMs;
        long pingTicks = Math.round(ping / 50.0);
        long targetTick = now - pingTicks;
        int window = config.rewindToleranceTicks + 1;

        PlayerData victim = dataManager.getByEntityId(targetEntityId);
        PositionData[] victimWindow = victim != null ? victim.getWindow(targetTick, window) : null;

        MathUtil.Vec3 eye = attacker.eyePosition();
        MathUtil.Vec3 look = MathUtil.directionFromRotation(attacker.yaw, attacker.pitch);

        AttackContext ctx = new AttackContext(
                attacker, victim, targetEntityId, victimWindow, eye, look, now, ping);

        boolean cancel = false;
        // Order: cheapest/highest-confidence first. Each may add VL; we OR the
        // cancel decisions so any single check can stop the hit.
        cancel |= reach.handle(ctx);
        cancel |= silentAim.handle(ctx);
        cancel |= killaura.handle(ctx, attackerPlayer);
        cancel |= mace.handle(ctx);
        cancel |= lunge.handleAttack(ctx);

        // Update last-attack markers AFTER the checks so they saw the PREVIOUS
        // attack as "last" (the mace combo check relies on this).
        attacker.lastAttackedEntityId = targetEntityId;
        attacker.lastAttackTick = now;
        return cancel;
    }

    // -----------------------------------------------------------------------
    //  Movement / rotation / inventory dispatch
    // -----------------------------------------------------------------------

    /** Position-bearing movement packet. @return true if a set-back is needed. */
    public boolean onMovement(PlayerData data) {
        return speed.handle(data);
    }

    /** Rotation-bearing packet -> behavioral aim analysis. */
    public void onRotation(PlayerData data) {
        killaura.onRotation(data);
    }

    public void onSlotChange(PlayerData data, int newSlot) {
        silentSwap.onSlotChange(data, newSlot);
    }

    public void onOffhandSwap(PlayerData data) {
        silentSwap.onOffhandSwap(data);
    }

    public void onSwing(PlayerData data) {
        lunge.onSwing(data);
    }

    /** Bukkit-side damage cross-check (mace attribute swap corroboration). */
    public void onDamage(PlayerData attacker, int victimEntityId, double finalDamage) {
        mace.onDamage(attacker, victimEntityId, finalDamage);
    }

    // -----------------------------------------------------------------------
    //  Decay lookup
    // -----------------------------------------------------------------------

    @Override
    public double decayFor(String checkName) {
        Check c = byName.get(checkName);
        return c != null ? c.decay() : 0.0;
    }
}
