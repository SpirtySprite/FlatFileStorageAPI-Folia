package me.kirug.flatfilestorage.impl;

import me.kirug.flatfilestorage.api.SerializableObject;
import me.kirug.flatfilestorage.api.StorageAPI;
import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Industrial-Grade Storage Implementation.
 */
public class FlatFileStorage implements StorageAPI {

    private final Path rootDir;
    private final Logger logger;
    
    // RAM Cache: Instant reads
    private final Map<String, SerializableObject> cache = new ConcurrentHashMap<>();
    
    // Write Coalescing: Tracking pending saves
    private final Map<String, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();

    // Striped Locks: 128 for high concurrency
    private final ReadWriteLock[] locks;
    private static final int STRIPE_COUNT = 128;

    // IO Pool: Fixed size for stability
    private final ExecutorService ioExecutor;

    public FlatFileStorage(Path rootDir, Logger logger) {
        this.rootDir = rootDir;
        this.logger = logger;
        
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        this.locks = new ReadWriteLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            locks[i] = new ReentrantReadWriteLock();
        }
    }
    
    private ReadWriteLock getLock(String id) {
        return locks[Math.abs(id.hashCode()) % STRIPE_COUNT];
    }

    @Override
    public CompletableFuture<Void> save(final String id, final SerializableObject data) {
        if (data instanceof me.kirug.flatfilestorage.api.AbstractSerializable) {
            me.kirug.flatfilestorage.api.AbstractSerializable abs = (me.kirug.flatfilestorage.api.AbstractSerializable) data;
            if (!abs.isDirty()) {
                return CompletableFuture.completedFuture(null);
            }
        }
        
        cache.put(id, data);
        
        return pendingSaves.computeIfAbsent(id, k -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            
            ioExecutor.submit(() -> {
                try {
                    SerializableObject currentData = cache.get(id);
                    if (currentData == null) { 
                        future.complete(null); 
                        return;
                    }

                    if (rootDir.toFile().getUsableSpace() < 4096) {
                        throw new IOException("Disk Full! Aborting save for " + id);
                    }

                    Path tmpFile = rootDir.resolve(id + "." + java.util.UUID.randomUUID() + ".tmp");
                    
                    try {
                        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
                        
                        try (FileOutputStream fos = new FileOutputStream(tmpFile.toFile());
                             BufferedOutputStream bos = new BufferedOutputStream(fos);
                             java.util.zip.CheckedOutputStream checkedOut = new java.util.zip.CheckedOutputStream(bos, crc);
                             VarOutputStream out = new VarOutputStream(checkedOut)) {
                            
                            out.writeVarInt(currentData.getVersion());
                            currentData.write(out);
                            
                            long val = crc.getValue();
                            out.writeLong(val); 
                             
                            out.flush();
                            fos.getFD().sync();
                        }
                        
                        ReadWriteLock lock = getLock(id);
                        lock.writeLock().lock();
                        try {
                            Path file = rootDir.resolve(id + ".var");
                            Path backup = rootDir.resolve(id + ".var.bak");

                            if (Files.exists(file)) {
                                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                            }
                            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } finally {
                            lock.writeLock().unlock();
                        }
                        
                    } finally {
                        Files.deleteIfExists(tmpFile);
                    }
                    
                    if (currentData instanceof me.kirug.flatfilestorage.api.AbstractSerializable) {
                        ((me.kirug.flatfilestorage.api.AbstractSerializable) currentData).markSaved();
                    }
                    future.complete(null);
                    
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to save " + id, e);
                    future.completeExceptionally(e);
                } finally {
                    pendingSaves.remove(id);
                }
            });
            return future;
        });
    }

    @Override
    public <T extends SerializableObject> CompletableFuture<T> load(String id, Supplier<T> factory) {
        if (cache.containsKey(id)) {
            SerializableObject cached = cache.get(id);
            try {
                T dummy = factory.get();
                if (dummy.getClass().isInstance(cached)) {
                    return CompletableFuture.completedFuture((T) cached);
                } else {
                     logger.warning("Cache mismatch for " + id + ". Expected " + dummy.getClass().getSimpleName() + " but found " + cached.getClass().getSimpleName());
                     cache.remove(id);
                }
            } catch (Exception e) {
                 return CompletableFuture.failedFuture(e);
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = getLock(id);
            lock.readLock().lock();
            try {
                Path file = rootDir.resolve(id + ".var");
                T result = null;
                
                try {
                    result = loadFromFile(file, id, factory);
                } catch (Exception e) {
                    logger.warning("Failed to load main file for " + id + ": " + e.getMessage());
                }
                
                if (result == null) {
                    Path backup = rootDir.resolve(id + ".var.bak");
                    if (Files.exists(backup)) {
                        logger.info("Attempting to load backup for " + id + "...");
                        try {
                            result = loadFromFile(backup, id, factory);
                            logger.info("Backup restored successfully for " + id);
                        } catch (Exception ex) {
                             logger.severe("Backup also corrupt for " + id + ": " + ex.getMessage());
                        }
                    }
                }
                
                if (result != null) {
                    cache.putIfAbsent(id, result);
                }
                
                return result; 
                
            } finally {
                lock.readLock().unlock();
            }
        }, ioExecutor);
    }

    // Extraction helper for loading logic
    private <T extends SerializableObject> T loadFromFile(Path path, String id, Supplier<T> factory) throws IOException {
        if (!Files.exists(path) || Files.size(path) == 0) return null;
        
        byte[] allBytes = Files.readAllBytes(path);
        if (allBytes.length < 8) throw new IOException("File too short");
        
        int dataLen = allBytes.length - 8;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(allBytes, 0, dataLen);
        
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(allBytes, dataLen, 8);
        long storedChecksum = buffer.getLong();
        
        if (crc.getValue() != storedChecksum) throw new IOException("CRC Checksum Mismatch");

        T instance = factory.get();
        try (ByteArrayInputStream bis = new ByteArrayInputStream(allBytes, 0, dataLen);
             VarInputStream in = new VarInputStream(bis)) {
            int version = in.readVarInt();
            instance.read(in, version);
        }
        
        if (instance instanceof me.kirug.flatfilestorage.api.AbstractSerializable) {
            ((me.kirug.flatfilestorage.api.AbstractSerializable) instance).markSaved();
        }
        return instance;
    }

    @Override
    public CompletableFuture<Void> delete(String id) {
        cache.remove(id);
        
        return CompletableFuture.runAsync(() -> {
            ReadWriteLock lock = getLock(id);
            lock.writeLock().lock();
            try {
                Files.deleteIfExists(rootDir.resolve(id + ".var"));
                Files.deleteIfExists(rootDir.resolve(id + ".var.tmp"));
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                lock.writeLock().unlock();
            }
        }, ioExecutor);
    }
    
    @Override
    public CompletableFuture<Boolean> exists(String id) {
        if (cache.containsKey(id)) return CompletableFuture.completedFuture(true);
        return CompletableFuture.supplyAsync(() -> Files.exists(rootDir.resolve(id + ".var")), ioExecutor);
    }
    
    @Override
    public void invalidateCache(String id) {
        cache.remove(id);
    }

    @Override
    public void shutdown() {
        logger.info("Storage Shutdown: Flushing pending writes...");
        
        // FLUSH LOGIC: Wait for all currently known pending saves
        try {
            CompletableFuture.allOf(pendingSaves.values().toArray(new CompletableFuture[0]))
                .get(10, TimeUnit.SECONDS); 
            logger.info("All pending writes flushed.");
        } catch (Exception e) {
             logger.warning("Timed out or failed while flushing writes: " + e.getMessage());
        }

        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
        }
    }
}
