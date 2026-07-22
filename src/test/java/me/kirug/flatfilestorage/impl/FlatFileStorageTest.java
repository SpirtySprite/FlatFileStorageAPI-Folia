package me.kirug.flatfilestorage.impl;

import me.kirug.flatfilestorage.api.AutoSerializable;
import me.kirug.flatfilestorage.api.Serialize;
import me.kirug.flatfilestorage.codec.Codec;
import me.kirug.flatfilestorage.codec.Codecs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlatFileStorageTest {

    @TempDir
    Path dir;
    private FlatFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FlatFileStorage(dir, Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        storage.shutdown();
    }

    // --- a simple explicit-codec value ---
    static final class Box {
        int v;
    }

    private static final Codec<Box> BOX = Codecs.object(Box::new)
            .field(1, Codecs.INT, o -> o.v, (o, v) -> o.v = v)
            .build();

    @Test
    void savesAndLoadsWithExplicitCodec() {
        Box b = new Box();
        b.v = 123;
        storage.save("k", b, BOX).join();
        storage.invalidateCache("k");
        assertEquals(123, storage.load("k", BOX).join().v);
    }

    @Test
    void rapidSavesNeverLoseTheLatestValue() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            Box b = new Box();
            b.v = i;
            futures.add(storage.save("hot", b, BOX));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        storage.invalidateCache("hot");
        assertEquals(300, storage.load("hot", BOX).join().v, "coalescing must persist the final write");
    }

    @Test
    void corruptMainFileRecoversFromBackup() throws IOException {
        Box v1 = new Box();
        v1.v = 11;
        storage.save("c", v1, BOX).join();
        Box v2 = new Box();
        v2.v = 22;
        storage.save("c", v2, BOX).join(); // v1 becomes the .bak, v2 is the main file

        flipAByte(mainFile());
        storage.invalidateCache("c");

        Box loaded = storage.load("c", BOX).join();
        assertEquals(11, loaded.v, "must fall back to the previous good backup");
    }

    @Test
    void corruptMainAndBackupYieldsNull() throws IOException {
        Box v1 = new Box();
        v1.v = 1;
        storage.save("c", v1, BOX).join();
        Box v2 = new Box();
        v2.v = 2;
        storage.save("c", v2, BOX).join();

        for (Path p : allFiles()) {
            flipAByte(p);
        }
        storage.invalidateCache("c");
        assertNull(storage.load("c", BOX).join());
    }

    @Test
    void deleteRemovesTheValue() {
        Box b = new Box();
        b.v = 5;
        storage.save("d", b, BOX).join();
        assertTrue(storage.exists("d").join());
        storage.delete("d").join();
        storage.invalidateCache("d");
        assertFalse(storage.exists("d").join());
        assertNull(storage.load("d", BOX).join());
    }

    // --- annotation path (reflective codec) ---
    public static final class Profile extends AutoSerializable {
        @Serialize(order = 1)
        private String name;
        @Serialize(order = 2)
        private int level;
        @Serialize(order = 3)
        private List<String> perms;

        public Profile() {
        }

        public Profile(String name, int level) {
            this.name = name;
            this.level = level;
            this.perms = new ArrayList<>(List.of("build", "fly"));
        }
    }

    @Test
    void annotationPathRoundTrips() {
        storage.save("p", new Profile("Steve", 42)).join();
        storage.invalidateCache("p");
        Profile loaded = storage.load("p", Profile::new).join();
        assertEquals("Steve", loaded.name);
        assertEquals(42, loaded.level);
        assertEquals(List.of("build", "fly"), loaded.perms);
    }

    // --- helpers ---

    private Path mainFile() throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".dat")).findFirst().orElseThrow();
        }
    }

    private List<Path> allFiles() throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }

    private static void flipAByte(Path file) throws IOException {
        byte[] b = Files.readAllBytes(file);
        int idx = b.length / 2;
        b[idx] = (byte) (b[idx] ^ 0xFF);
        Files.write(file, b);
    }
}
