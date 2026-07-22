package me.kirug.flatfilestorage.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.luben.zstd.Zstd;
import me.kirug.flatfilestorage.api.AutoSerializable;
import me.kirug.flatfilestorage.api.StorageAPI;
import me.kirug.flatfilestorage.codec.Codec;
import me.kirug.flatfilestorage.codec.ReflectiveCodec;
import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32C;

/**
 * Flat-file store. Correctness properties:
 * <ul>
 *   <li><b>Consistent snapshot:</b> the value is serialized to bytes on the calling thread, so a
 *       concurrent mutation can never produce a torn write (and Bukkit data is read on the right
 *       region thread).</li>
 *   <li><b>No lost writes:</b> a single-flight pipeline per id always writes the latest bytes and
 *       never drops an update, even under rapid saves.</li>
 *   <li><b>Atomic + verified:</b> write to a temp file, fsync, verify its checksum, then atomically
 *       promote; the previous file is kept as a {@code .bak} fallback.</li>
 * </ul>
 */
public final class FlatFileStorage implements StorageAPI {

    private static final int MAGIC = 0x46465332;   // "FFS2"
    private static final byte FORMAT_VERSION = 1;
    private static final int COMPRESS_THRESHOLD = 512;
    private static final int STRIPES = 128;

    private final Path rootDir;
    private final Logger logger;
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterAccess(15, TimeUnit.MINUTES)
            .build();
    private final Map<String, WriteState> writeStates = new ConcurrentHashMap<>();
    private final ReadWriteLock[] locks = new ReadWriteLock[STRIPES];

    public FlatFileStorage(Path rootDir, Logger logger) {
        this.rootDir = rootDir;
        this.logger = logger;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantReadWriteLock();
        }
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not create storage dir " + rootDir, e);
        }
    }

    // ---- save ----

    @Override
    public CompletableFuture<Void> save(String id, AutoSerializable value) {
        if (!value.isDirty()) {
            return CompletableFuture.completedFuture(null);
        }
        @SuppressWarnings("unchecked")
        Codec<AutoSerializable> codec = (Codec<AutoSerializable>) (Codec<?>) ReflectiveCodec.of(value.getClass());
        byte[] bytes;
        try {
            bytes = encode(value, codec, value.dataVersion());
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        cache.put(id, value);
        CompletableFuture<Void> future = enqueue(id, bytes);
        future.thenRun(value::markSaved);
        return future;
    }

    @Override
    public <T> CompletableFuture<Void> save(String id, T value, Codec<T> codec) {
        byte[] bytes;
        try {
            bytes = encode(value, codec, 1);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        cache.put(id, value);
        return enqueue(id, bytes);
    }

    /** Serializes to the full file byte array on the CALLING thread (snapshot + Bukkit-safe). */
    private <T> byte[] encode(T value, Codec<T> codec, int dataVersion) throws IOException {
        ByteArrayOutputStream payloadBuf = new ByteArrayOutputStream(256);
        try (VarOutputStream out = new VarOutputStream(payloadBuf)) {
            out.writeVarInt(dataVersion);
            codec.write(out, value);
        }
        byte[] payload = payloadBuf.toByteArray();

        boolean compress = payload.length > COMPRESS_THRESHOLD;
        byte[] stored = compress ? Zstd.compress(payload) : payload;
        byte flag = (byte) (compress ? 1 : 0);

        ByteArrayOutputStream fileBuf = new ByteArrayOutputStream(stored.length + 16);
        try (VarOutputStream out = new VarOutputStream(fileBuf)) {
            out.writeInt(MAGIC);
            CRC32C crc = new CRC32C();
            out.writeByte(FORMAT_VERSION);
            crc.update(FORMAT_VERSION);
            out.writeByte(flag);
            crc.update(flag);
            out.write(stored);
            crc.update(stored);
            out.writeLong(crc.getValue());
        }
        return fileBuf.toByteArray();
    }

    // ---- single-flight write pipeline (coalescing, never drops the latest bytes) ----

    private static final class WriteState {
        byte[] pending;
        CompletableFuture<Void> future;
        boolean running;
    }

    private CompletableFuture<Void> enqueue(String id, byte[] bytes) {
        WriteState st = writeStates.computeIfAbsent(id, k -> new WriteState());
        synchronized (st) {
            st.pending = bytes;
            if (st.future == null) {
                st.future = new CompletableFuture<>();
            }
            CompletableFuture<Void> f = st.future;
            if (!st.running) {
                st.running = true;
                io.submit(() -> drain(id, st));
            }
            return f;
        }
    }

    private void drain(String id, WriteState st) {
        while (true) {
            byte[] bytes;
            CompletableFuture<Void> f;
            synchronized (st) {
                if (st.pending == null) {
                    st.running = false;
                    return;
                }
                bytes = st.pending;
                st.pending = null;
                f = st.future;
                st.future = null;
            }
            try {
                writeAtomic(id, bytes);
                f.complete(null);
            } catch (Throwable e) {
                logger.log(Level.SEVERE, "Failed to write " + id, e);
                f.completeExceptionally(e);
            }
        }
    }

    private void writeAtomic(String id, byte[] bytes) throws IOException {
        Path file = pathFor(id);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");

        try (var ch = java.nio.channels.FileChannel.open(tmp,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true); // fsync
        }
        // Verify the bytes we just wrote actually decode before trusting them.
        try {
            decode(Files.readAllBytes(tmp));
        } catch (IOException bad) {
            Files.deleteIfExists(tmp);
            throw new IOException("Refusing to promote a corrupt temp file for " + id, bad);
        }

        ReadWriteLock lock = lockFor(id);
        lock.writeLock().lock();
        try {
            Path backup = file.resolveSibling(file.getFileName() + ".bak");
            if (Files.exists(file)) {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            lock.writeLock().unlock();
            Files.deleteIfExists(tmp);
        }
    }

    // ---- load ----

    @Override
    @SuppressWarnings("unchecked")
    public <T extends AutoSerializable> CompletableFuture<T> load(String id, Supplier<T> factory) {
        Object cached = cache.getIfPresent(id);
        T probe = factory.get();
        Class<T> type = (Class<T>) probe.getClass();
        if (type.isInstance(cached)) {
            return CompletableFuture.completedFuture((T) cached);
        }
        Codec<T> codec = (Codec<T>) (Codec<?>) ReflectiveCodec.of(type);
        return loadWith(id, codec).thenApply(value -> {
            if (value instanceof AutoSerializable a) {
                a.markSaved();
            }
            return value;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> load(String id, Codec<T> codec) {
        Object cached = cache.getIfPresent(id);
        if (cached != null) {
            try {
                return CompletableFuture.completedFuture((T) cached);
            } catch (ClassCastException mismatch) {
                cache.invalidate(id);
            }
        }
        return loadWith(id, codec);
    }

    private <T> CompletableFuture<T> loadWith(String id, Codec<T> codec) {
        return CompletableFuture.supplyAsync(() -> {
            ReadWriteLock lock = lockFor(id);
            lock.readLock().lock();
            try {
                Path file = pathFor(id);
                T value = decodeFile(file, codec, id);
                if (value == null) {
                    Path backup = file.resolveSibling(file.getFileName() + ".bak");
                    if (Files.exists(backup)) {
                        logger.warning("Main file unreadable for " + id + ", trying backup");
                        value = decodeFile(backup, codec, id);
                        if (value != null) {
                            logger.info("Recovered " + id + " from backup");
                        }
                    }
                }
                if (value != null) {
                    cache.put(id, value);
                }
                return value;
            } finally {
                lock.readLock().unlock();
            }
        }, io);
    }

    private <T> T decodeFile(Path file, Codec<T> codec, String id) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            byte[] payload = decode(Files.readAllBytes(file));
            try (VarInputStream in = new VarInputStream(new ByteArrayInputStream(payload))) {
                in.readVarInt(); // data version (available for future migrations)
                return codec.read(in);
            }
        } catch (Exception e) {
            logger.warning("Could not read " + id + " from " + file.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Validates the envelope + CRC and returns the (decompressed) payload: {@code [version][object]}. */
    private static byte[] decode(byte[] fileBytes) throws IOException {
        if (fileBytes.length < 4 + 1 + 1 + 8) {
            throw new IOException("File too small");
        }
        try (VarInputStream in = new VarInputStream(new ByteArrayInputStream(fileBytes))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Bad magic header");
            }
            byte formatVersion = in.readByte();
            byte flag = in.readByte();
            int storedLen = fileBytes.length - 4 - 1 - 1 - 8;
            byte[] stored = new byte[storedLen];
            in.readFully(stored);
            long storedCrc = in.readLong();

            CRC32C crc = new CRC32C();
            crc.update(formatVersion);
            crc.update(flag);
            crc.update(stored);
            if (crc.getValue() != storedCrc) {
                throw new IOException("CRC mismatch (corrupt file)");
            }

            if ((flag & 1) != 0) {
                long size = Zstd.decompressedSize(stored);
                return Zstd.decompress(stored, (int) size);
            }
            return stored;
        }
    }

    // ---- lifecycle ----

    @Override
    public CompletableFuture<Void> delete(String id) {
        cache.invalidate(id);
        return CompletableFuture.runAsync(() -> {
            ReadWriteLock lock = lockFor(id);
            lock.writeLock().lock();
            try {
                Path file = pathFor(id);
                Files.deleteIfExists(file);
                Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".bak"));
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            } finally {
                lock.writeLock().unlock();
            }
        }, io);
    }

    @Override
    public CompletableFuture<Boolean> exists(String id) {
        if (cache.getIfPresent(id) != null) {
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.supplyAsync(() -> Files.exists(pathFor(id)), io);
    }

    @Override
    public void invalidateCache(String id) {
        cache.invalidate(id);
    }

    @Override
    public void shutdown() {
        logger.info("Storage: flushing pending writes...");
        io.shutdown();
        try {
            if (!io.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("Storage: timed out flushing writes");
                io.shutdownNow();
            }
        } catch (InterruptedException e) {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---- key -> path (safe + sharded) ----

    private Path pathFor(String id) {
        String encoded = encodeKey(id);
        String shard = shard(id);
        return rootDir.resolve(shard).resolve(encoded + ".dat");
    }

    /** Percent-encodes anything outside [A-Za-z0-9._-] so an id can never traverse or break paths. */
    private static String encodeKey(String id) {
        StringBuilder sb = new StringBuilder(id.length() + 8);
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
            }
        }
        return sb.toString();
    }

    private static String shard(String id) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return String.format("%02x", hash[0] & 0xFF);
        } catch (Exception e) {
            return "00";
        }
    }

    private ReadWriteLock lockFor(String id) {
        return locks[Math.floorMod(id.hashCode(), STRIPES)];
    }
}
