package me.kirug.flatfilestorage.impl;

import me.kirug.flatfilestorage.api.SerializableObject;
import me.kirug.flatfilestorage.api.StorageAPI;
import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.luben.zstd.Zstd;

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
    
    // RAM Cache: Caffeine for smart eviction
    private final Cache<String, SerializableObject> cache;
    
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
        
        // Initialize Smart Cache
        this.cache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(15, java.util.concurrent.TimeUnit.MINUTES)
                .removalListener((String key, SerializableObject value, RemovalCause cause) -> {
                    if (value instanceof me.kirug.flatfilestorage.api.AbstractSerializable) {
                         if (((me.kirug.flatfilestorage.api.AbstractSerializable) value).isDirty()) {
                             this.save(key, value); // Auto-flush on eviction
                         }
                    }
                })
                .build();
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
                    SerializableObject currentData = cache.getIfPresent(id);
                    if (currentData == null) { 
                        future.complete(null); 
                        return;
                    }

                    if (rootDir.toFile().getUsableSpace() < 4096) {
                        throw new IOException("Disk Full! Aborting save for " + id);
                    }

                    Path tmpFile = rootDir.resolve(id + "." + java.util.UUID.randomUUID() + ".tmp");
                    
                    // Buffer to memory first to determine size & compression
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
                    try (VarOutputStream bufOut = new VarOutputStream(buffer)) {
                        bufOut.writeVarInt(currentData.getVersion());
                        currentData.write(bufOut);
                    }
                    
                    byte[] rawData = buffer.toByteArray();
                    boolean compress = rawData.length > 512;
                    byte[] payload;
                    
                    if (compress) {
                        payload = Zstd.compress(rawData);
                    } else {
                        payload = rawData;
                    }
                    
                    try {
                        try (FileOutputStream fos = new FileOutputStream(tmpFile.toFile());
                             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                             
                            DataOutputStream rawDos = new DataOutputStream(bos);
                            // 1. Write Magic
                            rawDos.writeInt(MAGIC_HEADER);
                            
                            // 2. Prepare CRC (Calculated on Flag + Payload)
                            java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
                            
                            // 3. Write Flag
                            int flag = compress ? 1 : 0;
                            rawDos.writeByte(flag);
                            crc.update(flag);
                            
                            // 4. Write Payload
                            rawDos.write(payload);
                            crc.update(payload);
                            
                            // 5. Write CRC
                            rawDos.writeLong(crc.getValue());
                            
                            rawDos.flush();
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
        SerializableObject cached = cache.getIfPresent(id);
        if (cached != null) {
            try {
                T dummy = factory.get();
                if (dummy.getClass().isInstance(cached)) {
                    return CompletableFuture.completedFuture((T) cached);
                } else {
                     logger.warning("Cache mismatch for " + id + ". Expected " + dummy.getClass().getSimpleName() + " but found " + cached.getClass().getSimpleName());
                     cache.invalidate(id);
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
                    cache.put(id, result);
                }
                
                return result; 
                
            } finally {
                lock.readLock().unlock();
            }
        }, ioExecutor);
    }

    private static final int MAGIC_HEADER = 0x46465341; // "FFSA"

    // Extraction helper for loading logic
    private <T extends SerializableObject> T loadFromFile(Path path, String id, Supplier<T> factory) throws IOException {
        if (!Files.exists(path) || Files.size(path) < 13) return null; // Min 4 magic + 1 flag + 4 crc(partial) -> Actually 4+1+Data+8. Empty data?
        
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
             long size = channel.size();
             
             // Use MappedByteBuffer for ultra-fast access
             java.nio.MappedByteBuffer buf = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, size);
             
             // 1. Check Magic Header
             int magic = buf.getInt();
             if (magic != MAGIC_HEADER) {
                 logger.warning("Invalid File Header for " + id + ": " + Integer.toHexString(magic));
                 return null;
             }
             
             // Structure: [MAGIC 4] [FLAG 1] [PAYLOAD N] [CRC 8]
             // CRC covers [FLAG] + [PAYLOAD]
             
             long payloadEnd = size - 8;
             long dataStart = 4;
             
             // Verify CRC
             java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
             
             buf.position((int)dataStart); 
             buf.limit((int)payloadEnd); 
             crc.update(buf);
             
             long calculatedCrc = crc.getValue();
             
             buf.limit((int)size);
             buf.position((int)payloadEnd);
             long storedCrc = buf.getLong();
             
             if (calculatedCrc != storedCrc) {
                 throw new IOException("CRC Checksum Mismatch for " + id);
             }
             
             // Read Data
             buf.position(4); // Back to Flag
             byte flag = buf.get();
             boolean compressed = (flag == 1);
             
             int payloadLen = (int)(payloadEnd - 5); // Total - Magic(4) - Flag(1) - CRC(8)
             byte[] payload = new byte[payloadLen];
             buf.get(payload); // Read status is fine because we reset position
             
             byte[] finalData;
             if (compressed) {
                 long decompressedSize = Zstd.decompressedSize(payload);
                 finalData = Zstd.decompress(payload, (int)decompressedSize);
             } else {
                 finalData = payload;
             }
             
             // Deserialize
             try (ByteArrayInputStream bis = new ByteArrayInputStream(finalData);
                  VarInputStream in = new VarInputStream(bis)) {
                  
                 int version = in.readVarInt();
                 T instance = factory.get();
                 instance.read(in, version);
                 
                 if (instance instanceof me.kirug.flatfilestorage.api.AbstractSerializable) {
                    ((me.kirug.flatfilestorage.api.AbstractSerializable) instance).markSaved();
                 }
                 return instance;
             }
        }
    }
    
    // Simple adapter (Removed, using ByteArrayInputStream for finalData)

    @Override
    public CompletableFuture<Void> delete(String id) {
        cache.invalidate(id); // Caffeine method
        
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
        if (cache.getIfPresent(id) != null) return CompletableFuture.completedFuture(true);
        return CompletableFuture.supplyAsync(() -> Files.exists(rootDir.resolve(id + ".var")), ioExecutor);
    }
    
    @Override
    public void invalidateCache(String id) {
        cache.invalidate(id);
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
