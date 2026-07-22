package me.kirug.flatfilestorage.api;

import me.kirug.flatfilestorage.codec.Codec;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Asynchronous key/value store. Two ways to describe a value:
 * <ul>
 *   <li><b>Annotation:</b> extend {@link AutoSerializable} and annotate fields with
 *       {@link Serialize} - the engine derives a codec automatically.</li>
 *   <li><b>Explicit codec:</b> pass a {@link Codec} for full control (records, immutables, custom
 *       formats).</li>
 * </ul>
 *
 * <p><b>Threading:</b> a value is serialized to bytes on the calling thread (a fast, consistent
 * snapshot - and the correct region thread for reading Bukkit data on Folia), then the disk write
 * happens off-thread. Loads run off-thread and return snapshot types (no live world/entity access),
 * so convert to Bukkit objects on the region thread.
 */
public interface StorageAPI {

    // ---- annotation path ----

    /** Saves an {@link AutoSerializable}. Skipped if the object is not dirty. */
    CompletableFuture<Void> save(String id, AutoSerializable value);

    /** Loads an {@link AutoSerializable}; the factory supplies an empty instance (e.g. {@code Foo::new}). */
    <T extends AutoSerializable> CompletableFuture<T> load(String id, Supplier<T> factory);

    // ---- explicit codec path ----

    /** Saves any value using an explicit codec. */
    <T> CompletableFuture<Void> save(String id, T value, Codec<T> codec);

    /** Loads a value using an explicit codec. Completes with {@code null} if the id doesn't exist. */
    <T> CompletableFuture<T> load(String id, Codec<T> codec);

    // ---- lifecycle ----

    /** Deletes the stored value and removes it from cache. */
    CompletableFuture<Void> delete(String id);

    /** @return whether a value exists (checks cache, then disk). */
    CompletableFuture<Boolean> exists(String id);

    /** Removes an entry from the in-memory read cache (does not touch disk). */
    void invalidateCache(String id);

    /** Flushes pending writes and shuts down. Call on plugin disable. */
    void shutdown();
}
