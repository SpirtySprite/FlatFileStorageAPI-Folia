package me.kirug.flatfilestorage.codec;

import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codec round-trips and the schema-evolution guarantees that the old positional format could not
 * provide: adding, removing, reordering and skipping fields without corrupting the record.
 */
class CodecTest {

    private static <T> T roundTrip(Codec<T> codec, T value) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (VarOutputStream out = new VarOutputStream(baos)) {
            codec.write(out, value);
        }
        try (VarInputStream in = new VarInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            return codec.read(in);
        }
    }

    @Test
    void primitivesAndCollectionsRoundTrip() throws IOException {
        assertEquals(42, roundTrip(Codecs.INT, 42));
        assertEquals(-7L, roundTrip(Codecs.LONG, -7L));
        assertEquals(3.14, roundTrip(Codecs.DOUBLE, 3.14));
        assertEquals(1.5f, roundTrip(Codecs.FLOAT, 1.5f));
        assertTrue(roundTrip(Codecs.BOOLEAN, true));
        assertEquals("héllo €", roundTrip(Codecs.STRING, "héllo €"));
        UUID u = UUID.randomUUID();
        assertEquals(u, roundTrip(Codecs.UUID, u));
        assertEquals(List.of("a", "b", "c"), roundTrip(Codecs.list(Codecs.STRING), List.of("a", "b", "c")));
        assertEquals(Map.of("x", 1, "y", 2), roundTrip(Codecs.map(Codecs.STRING, Codecs.INT), Map.of("x", 1, "y", 2)));
        assertEquals(Optional.of(9), roundTrip(Codecs.optional(Codecs.INT), Optional.of(9)));
        assertEquals(Optional.empty(), roundTrip(Codecs.optional(Codecs.INT), Optional.empty()));
    }

    // --- schema evolution ---

    static final class V1 {
        int a;
        String b;
        long c;
    }

    static final class V2 {
        int a;       // id 1 (kept)
        long c;      // id 3 (kept, but declared before the new field)
        boolean d;   // id 4 (new)
    }

    private static Codec<V1> codecV1() {
        return Codecs.object(V1::new)
                .field(1, Codecs.INT, o -> o.a, (o, v) -> o.a = v)
                .field(2, Codecs.STRING, o -> o.b, (o, v) -> o.b = v)
                .field(3, Codecs.LONG, o -> o.c, (o, v) -> o.c = v)
                .build();
    }

    private static Codec<V2> codecV2() {
        return Codecs.object(V2::new)
                .field(1, Codecs.INT, o -> o.a, (o, v) -> o.a = v)
                .field(4, Codecs.BOOLEAN, o -> o.d, (o, v) -> o.d = v)  // added
                .field(3, Codecs.LONG, o -> o.c, (o, v) -> o.c = v)     // reordered in the builder
                .build();                                              // id 2 removed entirely
    }

    @Test
    void oldDataLoadsIntoNewSchema() throws IOException {
        V1 old = new V1();
        old.a = 5;
        old.b = "dropped";
        old.c = 999;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (VarOutputStream out = new VarOutputStream(baos)) {
            codecV1().write(out, old);
        }

        V2 loaded;
        try (VarInputStream in = new VarInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            loaded = codecV2().read(in);
        }

        assertEquals(5, loaded.a);       // kept field, mapped by id despite reorder
        assertEquals(999, loaded.c);     // kept field, read correctly after the removed one was skipped
        assertFalse(loaded.d);           // new field -> default, no crash
    }

    @Test
    void newDataLoadsIntoOldSchema() throws IOException {
        V2 fresh = new V2();
        fresh.a = 7;
        fresh.c = 123;
        fresh.d = true;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (VarOutputStream out = new VarOutputStream(baos)) {
            codecV2().write(out, fresh);
        }

        V1 loaded;
        try (VarInputStream in = new VarInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            loaded = codecV1().read(in);  // doesn't know id 4
        }

        assertEquals(7, loaded.a);
        assertEquals(123, loaded.c);
        assertNull(loaded.b);            // id 2 wasn't in the new data -> default
    }

    @Test
    void unknownFieldsOfEveryWireTypeAreSkipped() throws IOException {
        // Emit four fields (varint, i32, i64, len); the reader knows none of these ids.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (VarOutputStream out = new VarOutputStream(baos)) {
            ObjectCodec.writeField(out, 1, cast(Codecs.INT), 100);
            ObjectCodec.writeField(out, 2, cast(Codecs.FLOAT), 1.0f);
            ObjectCodec.writeField(out, 3, cast(Codecs.DOUBLE), 2.0);
            ObjectCodec.writeField(out, 4, cast(Codecs.STRING), "skip me");
            out.writeVarInt(WireType.END_TAG);
        }
        ObjectCodec<Object> reader = Codecs.object(Object::new)
                .field(9, cast(Codecs.INT), o -> 0, (o, v) -> {
                })
                .build();
        try (VarInputStream in = new VarInputStream(new ByteArrayInputStream(baos.toByteArray()))) {
            assertNotNull(reader.read(in)); // must consume ids 1-4 (all skipped) without error
        }
    }

    @SuppressWarnings("unchecked")
    private static Codec<Object> cast(Codec<?> c) {
        return (Codec<Object>) c;
    }
}
