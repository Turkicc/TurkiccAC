package com.example.ac.util;

/**
 * Self-contained geometry helpers used by the combat checks.
 *
 * We intentionally use our own tiny {@link Vec3}/{@link AABB} types instead of
 * PacketEvents' or Bukkit's vector classes so that all check math is pure,
 * allocation-light, and runnable off the main/region thread without touching
 * the server's mutable world objects.
 */
public final class MathUtil {

    private MathUtil() {}

    /** Standing eye height (blocks). */
    public static final double EYE_HEIGHT = 1.62D;
    /** Sneaking eye height on modern versions. */
    public static final double EYE_HEIGHT_SNEAK = 1.27D;
    /** Player hitbox width / depth (full). */
    public static final double PLAYER_WIDTH = 0.6D;
    /** Player hitbox height. */
    public static final double PLAYER_HEIGHT = 1.8D;

    /** Immutable 3D vector. */
    public static final class Vec3 {
        public final double x, y, z;
        public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        public Vec3 add(double ax, double ay, double az) { return new Vec3(x + ax, y + ay, z + az); }
        public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
        public double length() { return Math.sqrt(x * x + y * y + z * z); }
        public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
        public Vec3 normalize() {
            double len = length();
            return len < 1.0e-8 ? new Vec3(0, 0, 0) : new Vec3(x / len, y / len, z / len);
        }
    }

    /** Axis-aligned bounding box. */
    public static final class AABB {
        public final double minX, minY, minZ, maxX, maxY, maxZ;
        public AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }
        /** Build a player hitbox centred on (feetX, feetY, feetZ). */
        public static AABB player(double feetX, double feetY, double feetZ) {
            double hw = PLAYER_WIDTH / 2.0;
            return new AABB(feetX - hw, feetY, feetZ - hw, feetX + hw, feetY + PLAYER_HEIGHT, feetZ + hw);
        }
        public AABB expand(double amount) {
            return new AABB(minX - amount, minY - amount, minZ - amount,
                    maxX + amount, maxY + amount, maxZ + amount);
        }
        public Vec3 center() {
            return new Vec3((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        }
    }

    /**
     * Unit look direction from Minecraft yaw/pitch (degrees).
     * Matches vanilla: yaw 0 = +Z (south), yaw increases clockwise.
     */
    public static Vec3 directionFromRotation(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double cosP = Math.cos(p);
        double dx = -cosP * Math.sin(y);
        double dy = -Math.sin(p);
        double dz = cosP * Math.cos(y);
        return new Vec3(dx, dy, dz);
    }

    /**
     * Closest distance from a point to the surface/interior of an AABB.
     * Returns 0 if the point is inside the box. This is the conservative
     * (false-positive-safe) "reach" metric: a player cannot possibly be
     * closer to the target than its nearest point.
     */
    public static double distancePointToAABB(Vec3 p, AABB box) {
        double dx = Math.max(Math.max(box.minX - p.x, 0.0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0.0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0.0), p.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Ray vs AABB (slab method). Returns the distance along {@code dir} (which
     * must be normalized) to the first intersection, or -1 if the ray misses
     * within {@code maxDistance}. Used to verify an attack ray actually hits a
     * hitbox (reach magnitude / raytrace sub-check).
     */
    public static double rayIntersectAABB(Vec3 origin, Vec3 dir, AABB box, double maxDistance) {
        double tMin = 0.0;
        double tMax = maxDistance;

        // X slab
        if (Math.abs(dir.x) < 1.0e-8) {
            if (origin.x < box.minX || origin.x > box.maxX) return -1;
        } else {
            double inv = 1.0 / dir.x;
            double t1 = (box.minX - origin.x) * inv;
            double t2 = (box.maxX - origin.x) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        // Y slab
        if (Math.abs(dir.y) < 1.0e-8) {
            if (origin.y < box.minY || origin.y > box.maxY) return -1;
        } else {
            double inv = 1.0 / dir.y;
            double t1 = (box.minY - origin.y) * inv;
            double t2 = (box.maxY - origin.y) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        // Z slab
        if (Math.abs(dir.z) < 1.0e-8) {
            if (origin.z < box.minZ || origin.z > box.maxZ) return -1;
        } else {
            double inv = 1.0 / dir.z;
            double t1 = (box.minZ - origin.z) * inv;
            double t2 = (box.maxZ - origin.z) * inv;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return -1;
        }
        return tMin;
    }

    /**
     * Smallest angle (degrees) between the player's look direction and the
     * direction from the eye to the nearest point of the target hitbox.
     * 0 = looking dead-on. Used by the angle / silent-aim checks.
     */
    public static double angleToBox(Vec3 eye, Vec3 lookDir, AABB box) {
        // Aim at the closest point on the box, which is the most generous
        // interpretation for the player.
        double cx = clamp(eye.x, box.minX, box.maxX);
        double cy = clamp(eye.y, box.minY, box.maxY);
        double cz = clamp(eye.z, box.minZ, box.maxZ);
        Vec3 toTarget = new Vec3(cx - eye.x, cy - eye.y, cz - eye.z).normalize();
        double dot = clamp(lookDir.normalize().dot(toTarget), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Normalize a yaw delta into [-180, 180]. */
    public static float wrapDegrees(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        if (d < -180.0f) d += 360.0f;
        return d;
    }

    /** Population standard deviation. */
    public static double stddev(double[] values, int count) {
        if (count <= 1) return 0.0;
        double mean = 0.0;
        for (int i = 0; i < count; i++) mean += values[i];
        mean /= count;
        double var = 0.0;
        for (int i = 0; i < count; i++) {
            double d = values[i] - mean;
            var += d * d;
        }
        return Math.sqrt(var / count);
    }
}
