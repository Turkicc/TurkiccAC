package com.example.ac.data;

/**
 * One snapshot of a player's position + rotation at a given server tick,
 * stored in the lag-compensation ring buffer. Immutable so it can be read
 * from any thread without synchronization.
 */
public final class PositionData {

    public final double x, y, z;
    public final float yaw, pitch;
    public final boolean onGround;
    public final long tick;       // server tick when recorded
    public final long timeMs;     // wall-clock millis when recorded

    public PositionData(double x, double y, double z, float yaw, float pitch,
                        boolean onGround, long tick, long timeMs) {
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
        this.onGround = onGround;
        this.tick = tick;
        this.timeMs = timeMs;
    }
}
