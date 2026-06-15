package com.example.ac.data;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every {@link PlayerData} instance and provides O(1) lookup by UUID and
 * by entity id (the latter is needed to turn an attacked-entity id from an
 * InteractEntity/Attack packet into the victim's data for lag-comp rewind).
 */
public final class PlayerDataManager {

    private final ConcurrentHashMap<UUID, PlayerData> byUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PlayerData> byEntityId = new ConcurrentHashMap<>();

    private final int historyTicks;
    private final int rotationSampleSize;

    public PlayerDataManager(int historyTicks, int rotationSampleSize) {
        this.historyTicks = historyTicks;
        this.rotationSampleSize = rotationSampleSize;
    }

    public PlayerData getOrCreate(UUID uuid, String name) {
        return byUuid.computeIfAbsent(uuid,
                u -> new PlayerData(u, name, historyTicks, rotationSampleSize));
    }

    public PlayerData get(UUID uuid) { return byUuid.get(uuid); }

    public PlayerData getByEntityId(int entityId) { return byEntityId.get(entityId); }

    /** Register the entity id once it's known (on join / first packet). */
    public void linkEntityId(PlayerData data, int entityId) {
        if (data.entityId != -1) byEntityId.remove(data.entityId);
        data.entityId = entityId;
        byEntityId.put(entityId, data);
    }

    public void remove(UUID uuid) {
        PlayerData data = byUuid.remove(uuid);
        if (data != null && data.entityId != -1) byEntityId.remove(data.entityId);
    }

    public Collection<PlayerData> all() { return byUuid.values(); }
}
