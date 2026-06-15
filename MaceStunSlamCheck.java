package com.example.ac.listener;

import com.example.ac.data.PlayerData;
import com.example.ac.data.PlayerDataManager;
import com.example.ac.data.PositionData;
import com.example.ac.manager.CheckManager;
import com.example.ac.util.SchedulerUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPosition;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.LongSupplier;
import java.util.logging.Level;

/**
 * The single PacketEvents listener. It runs on Netty threads, so it only
 * touches thread-safe cached {@link PlayerData} and the {@link CheckManager}
 * (whose math is pure). The only Bukkit-world interaction is the set-back
 * teleport, which is dispatched to the player's owning thread via
 * {@link SchedulerUtil}.
 *
 * <p><b>Version note:</b> PacketEvents wrapper/enum names drift between releases.
 * This file targets the 2.12.x API. If it fails to compile against your exact
 * version, the fixes are almost always renamed getters or wrapper classes here
 * (e.g. {@code getSlot}, {@code InteractAction}, {@code DiggingAction}) — the
 * detection logic in the checks is unaffected. Also note the attack-packet model
 * changed in very recent MC builds (a dedicated Attack packet); on 1.21.11
 * attacks still arrive as InteractEntity with the ATTACK action, which is what
 * we handle. If you target a build that uses the separate Attack packet, add a
 * branch for it that calls {@link CheckManager#onAttack} the same way.
 */
public final class PacketListener extends PacketListenerAbstract {

    private final JavaPlugin plugin;
    private final PlayerDataManager dataManager;
    private final CheckManager checks;
    private final com.example.ac.config.ACConfig config;
    private final LongSupplier tick;

    public PacketListener(JavaPlugin plugin, PlayerDataManager dataManager,
                          CheckManager checks, com.example.ac.config.ACConfig config,
                          LongSupplier tick) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.checks = checks;
        this.config = config;
        this.tick = tick;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        final User user = event.getUser();
        if (user == null || user.getUUID() == null) return;

        Player player = event.getPlayer() instanceof Player p ? p : null;
        // Staff bypass: skip everything (and never build wrappers for them).
        if (player != null && player.hasPermission("anticheat.bypass")) return;

        PlayerData data = dataManager.getOrCreate(user.getUUID(),
                user.getName() != null ? user.getName() : "unknown");
        // Keep ping fresh for lag-comp + the ping exemption.
        try {
            data.pingMs = PacketEvents.getAPI().getPlayerManager().getPing(user);
        } catch (Throwable ignored) { /* ping not available yet */ }

        // Lag/FPS grace: a big gap between packets => pause checks for a while.
        long nowMs = System.currentTimeMillis();
        if (data.lastPacketMs != 0L && nowMs - data.lastPacketMs > config.packetGapMs) {
            data.lagGraceUntilTick = tick.getAsLong() + config.packetGapGraceTicks;
        }
        data.lastPacketMs = nowMs;

        try {
            dispatch(event, data, player);
        } catch (Throwable t) {
            // Never let a packet-parsing hiccup break the Netty pipeline.
            plugin.getLogger().log(Level.FINE, "packet handling error", t);
        }
    }

    private void dispatch(PacketReceiveEvent event, PlayerData data, Player player) {
        final com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type =
                event.getPacketType();
        final long now = tick.getAsLong();

        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
            if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                boolean cancel = checks.onAttack(data, w.getEntityId(), player);
                if (cancel) event.setCancelled(true);
            }

        } else if (type == PacketType.Play.Client.PLAYER_POSITION) {
            WrapperPlayClientPlayerPosition w = new WrapperPlayClientPlayerPosition(event);
            Vector3d pos = w.getPosition();
            applyPosition(data, pos.getX(), pos.getY(), pos.getZ(), w.isOnGround(), now);
            recordHistory(data, now);
            if (checks.onMovement(data)) setback(player, data, now);

        } else if (type == PacketType.Play.Client.PLAYER_ROTATION) {
            WrapperPlayClientPlayerRotation w = new WrapperPlayClientPlayerRotation(event);
            applyRotation(data, w.getYaw(), w.getPitch(), w.isOnGround());
            recordHistory(data, now);
            checks.onRotation(data);

        } else if (type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            WrapperPlayClientPlayerPositionAndRotation w =
                    new WrapperPlayClientPlayerPositionAndRotation(event);
            Vector3d pos = w.getPosition();
            applyPosition(data, pos.getX(), pos.getY(), pos.getZ(), w.isOnGround(), now);
            applyRotation(data, w.getYaw(), w.getPitch(), w.isOnGround());
            recordHistory(data, now);
            boolean sb = checks.onMovement(data);
            checks.onRotation(data);
            if (sb) setback(player, data, now);

        } else if (type == PacketType.Play.Client.PLAYER_FLYING) {
            // onGround-only update (no position or rotation payload).
            WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);
            data.onGround = w.isOnGround();

        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange w = new WrapperPlayClientHeldItemChange(event);
            checks.onSlotChange(data, w.getSlot());

        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging w = new WrapperPlayClientPlayerDigging(event);
            if (w.getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                checks.onOffhandSwap(data);
            }

        } else if (type == PacketType.Play.Client.ANIMATION) {
            // Client swing — a lunge can be triggered swinging into open air.
            new WrapperPlayClientAnimation(event); // (hand unused; presence is the signal)
            checks.onSwing(data);

        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            // Sprint/sneak toggles feed the speed model's state.
            WrapperPlayClientEntityAction w = new WrapperPlayClientEntityAction(event);
            switch (w.getAction()) {
                case START_SPRINTING -> data.sprinting = true;
                case STOP_SPRINTING -> data.sprinting = false;
                case START_SNEAKING -> data.sneaking = true;
                case STOP_SNEAKING -> data.sneaking = false;
                default -> { /* other actions (jump-with-horse etc.) ignored */ }
            }
        }
    }

    private void applyPosition(PlayerData d, double x, double y, double z,
                               boolean onGround, long now) {
        d.lastX = d.x; d.lastY = d.y; d.lastZ = d.z;
        d.x = x; d.y = y; d.z = z;
        d.onGround = onGround;
        d.hasPositionThisTick = true;
    }

    private void applyRotation(PlayerData d, float yaw, float pitch, boolean onGround) {
        d.lastYaw = d.yaw; d.lastPitch = d.pitch;
        d.yaw = yaw; d.pitch = pitch;
        d.onGround = onGround;
    }

    private void recordHistory(PlayerData d, long now) {
        d.recordHistory(new PositionData(
                d.x, d.y, d.z, d.yaw, d.pitch, d.onGround, now, System.currentTimeMillis()));
    }

    /** Teleport the player back to their last legal position (Folia-safe). */
    private void setback(Player player, PlayerData d, long now) {
        if (player == null) return;
        // Grace immediately so in-flight packets before the teleport lands don't
        // pile on more set-backs.
        d.lastTeleportTick = now;
        final double bx = d.lastX, by = d.lastY, bz = d.lastZ;
        final float byaw = d.yaw, bpitch = d.pitch;
        SchedulerUtil.runForEntity(plugin, player, () -> {
            if (player.isValid()) {
                player.teleport(new Location(player.getWorld(), bx, by, bz, byaw, bpitch));
            }
        });
    }
}
