package me.kirug.flatfilestorage.api.data;

import org.bukkit.World;

/**
 * A safe reference to a Chunk that does not hold the actual Chunk object.
 * Safe to use on Async threads.
 */
public record ChunkReference(String worldName, int x, int z) {
    public long getChunkKey() {
        return (long) x & 0xffffffffL | ((long) z & 0xffffffffL) << 32;
    }
}
