package me.kirug.flatfilestorage.api.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A Folia-safe snapshot of a {@link Location}. Stores the world UUID (not a live World), so it can be
 * read and written on any thread without touching the server's world/entity state.
 *
 * <p>Convert to a live {@link Location} with {@link #toLocation()} on the region thread that owns that
 * world.
 */
public record StoredLocation(@Nullable UUID world, double x, double y, double z, float yaw, float pitch) {

    public static StoredLocation of(Location loc) {
        UUID world = loc.getWorld() == null ? null : loc.getWorld().getUID();
        return new StoredLocation(world, loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /** Resolves to a live {@link Location}. Call on the correct region thread. Null world if unloaded. */
    public @Nullable Location toLocation() {
        org.bukkit.World w = world == null ? null : Bukkit.getWorld(world);
        return new Location(w, x, y, z, yaw, pitch);
    }
}
