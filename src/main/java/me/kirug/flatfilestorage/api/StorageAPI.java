package me.kirug.flatfilestorage.api;

import java.util.concurrent.CompletableFuture;

public interface StorageAPI {

    /**
     * Saves an object asynchronously.
     * Guaranteed to be atomic (no half-written files).
     * 
     * @param id Unique identifier (filename)
     * @param data The object to save
     * @return Future completing when data is safely on disk
     */
     CompletableFuture<Void> save(String id, SerializableObject data);

    /**
     * Loads an object asynchronously.
     * If cached, returns completed future immediately.
     * 
     * @param id Unique identifier
     * @param factory Supplier to create a new empty instance (e.g. UserStats::new)
     * @return Future containing the loaded object
     */
    <T extends SerializableObject> CompletableFuture<T> load(String id, java.util.function.Supplier<T> factory);

    /**
     * Deletes the file and cache entry.
     */
    CompletableFuture<Void> delete(String id);

    /**
     * Check if a file exists (checks cache first, then disk).
     */
    CompletableFuture<Boolean> exists(String id);
    
    /**
     * Manually removes an entry from the RAM cache.
     * Does not delete from disk.
     */
    void invalidateCache(String id);
    
    /**
     * Flushes all pending writes and shuts down the IO executor.
     * Call this on plugin disable.
     */
    void shutdown();
}
