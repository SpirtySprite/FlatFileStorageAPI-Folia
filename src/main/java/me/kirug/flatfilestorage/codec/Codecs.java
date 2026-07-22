package me.kirug.flatfilestorage.codec;

import me.kirug.flatfilestorage.api.data.ChunkReference;
import me.kirug.flatfilestorage.api.data.InventoryData;
import me.kirug.flatfilestorage.api.data.StoredLocation;
import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;
import net.kyori.adventure.text.Component;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Built-in codecs and combinators. Most delegate to the {@code Var*} stream primitives; the value is
 * that they carry a {@link WireType} so the field framer knows how to skip them.
 *
 * <p>Nulls: a {@code null} object field is simply omitted from the record (it reads back as the
 * constructor default), so top-level codecs never see null. Inside collections, wrap an element codec
 * with {@link #nullable(Codec)} if null elements are possible.
 */
public final class Codecs {
    private Codecs() {
    }

    @FunctionalInterface
    private interface W<T> {
        void write(VarOutputStream out, T value) throws IOException;
    }

    @FunctionalInterface
    private interface R<T> {
        T read(VarInputStream in) throws IOException;
    }

    private static <T> Codec<T> make(int wireType, W<T> writer, R<T> reader) {
        return new Codec<>() {
            @Override
            public int wireType() {
                return wireType;
            }

            @Override
            public void write(VarOutputStream out, T value) throws IOException {
                writer.write(out, value);
            }

            @Override
            public T read(VarInputStream in) throws IOException {
                return reader.read(in);
            }
        };
    }

    // ---- primitives ----
    public static final Codec<Integer> INT = make(WireType.VARINT, VarOutputStream::writeVarInt, VarInputStream::readVarInt);
    public static final Codec<Long> LONG = make(WireType.VARINT, VarOutputStream::writeVarLong, VarInputStream::readVarLong);
    public static final Codec<Boolean> BOOLEAN = make(WireType.VARINT, (o, v) -> o.writeVarInt(v ? 1 : 0), i -> i.readVarInt() != 0);
    public static final Codec<Float> FLOAT = make(WireType.I32, VarOutputStream::writeFloat, VarInputStream::readFloat);
    public static final Codec<Double> DOUBLE = make(WireType.I64, VarOutputStream::writeDouble, VarInputStream::readDouble);

    // ---- length-delimited scalars ----
    public static final Codec<String> STRING = make(WireType.LEN, VarOutputStream::writeString, VarInputStream::readString);
    public static final Codec<UUID> UUID = make(WireType.LEN, VarOutputStream::writeUUID, VarInputStream::readUUID);
    public static final Codec<byte[]> BYTE_ARRAY = make(WireType.LEN, VarOutputStream::writeByteArray, VarInputStream::readByteArray);
    public static final Codec<int[]> INT_ARRAY = make(WireType.LEN, VarOutputStream::writeIntArray, VarInputStream::readIntArray);
    public static final Codec<long[]> LONG_ARRAY = make(WireType.LEN, VarOutputStream::writeLongArray, VarInputStream::readLongArray);
    public static final Codec<BigInteger> BIG_INTEGER = make(WireType.LEN, VarOutputStream::writeBigInteger, VarInputStream::readBigInteger);
    public static final Codec<Instant> INSTANT = make(WireType.VARINT, (o, v) -> o.writeVarLong(v.toEpochMilli()), i -> Instant.ofEpochMilli(i.readVarLong()));
    public static final Codec<LocalDateTime> LOCAL_DATE_TIME = make(WireType.LEN, (o, v) -> o.writeString(v.toString()), i -> {
        String s = i.readString();
        return s.isEmpty() ? null : LocalDateTime.parse(s);
    });

    // ---- Minecraft ----
    public static final Codec<Component> COMPONENT = make(WireType.LEN, VarOutputStream::writeComponent, VarInputStream::readComponent);
    public static final Codec<Vector> VECTOR = make(WireType.LEN, VarOutputStream::writeVector, VarInputStream::readVector);
    public static final Codec<ChunkReference> CHUNK = make(WireType.LEN, VarOutputStream::writeChunk, VarInputStream::readChunk);
    public static final Codec<InventoryData> INVENTORY = make(WireType.LEN, VarOutputStream::writeInventory, VarInputStream::readInventory);
    public static final Codec<StoredLocation> LOCATION = make(WireType.LEN, (o, v) -> {
        o.writeUUID(v.world());
        o.writeDouble(v.x());
        o.writeDouble(v.y());
        o.writeDouble(v.z());
        o.writeFloat(v.yaw());
        o.writeFloat(v.pitch());
    }, i -> new StoredLocation(i.readUUID(), i.readDouble(), i.readDouble(), i.readDouble(), i.readFloat(), i.readFloat()));

    // ---- combinators ----

    public static <E extends Enum<E>> Codec<E> enumOf(Class<E> type) {
        E[] constants = type.getEnumConstants();
        return make(WireType.VARINT, (o, v) -> o.writeVarInt(v.ordinal()), i -> {
            int ord = i.readVarInt();
            if (ord < 0 || ord >= constants.length) {
                throw new IOException("Invalid ordinal " + ord + " for enum " + type.getSimpleName());
            }
            return constants[ord];
        });
    }

    public static <T> Codec<List<T>> list(Codec<T> element) {
        return make(WireType.LEN, (o, list) -> {
            o.writeVarInt(list.size());
            for (T e : list) {
                if (e == null) {
                    throw new IOException("null list element; wrap the element codec with Codecs.nullable(...)");
                }
                element.write(o, e);
            }
        }, i -> {
            int n = i.readVarInt();
            List<T> list = new ArrayList<>(Math.max(0, n));
            for (int k = 0; k < n; k++) {
                list.add(element.read(i));
            }
            return list;
        });
    }

    public static <K, V> Codec<Map<K, V>> map(Codec<K> keyCodec, Codec<V> valueCodec) {
        return make(WireType.LEN, (o, m) -> {
            o.writeVarInt(m.size());
            for (Map.Entry<K, V> e : m.entrySet()) {
                keyCodec.write(o, e.getKey());
                valueCodec.write(o, e.getValue());
            }
        }, i -> {
            int n = i.readVarInt();
            Map<K, V> m = new HashMap<>(Math.max(4, n * 2));
            for (int k = 0; k < n; k++) {
                K key = keyCodec.read(i);
                m.put(key, valueCodec.read(i));
            }
            return m;
        });
    }

    public static <T> Codec<Optional<T>> optional(Codec<T> codec) {
        return make(WireType.LEN, (o, opt) -> {
            if (opt.isPresent()) {
                o.writeVarInt(1);
                codec.write(o, opt.get());
            } else {
                o.writeVarInt(0);
            }
        }, i -> i.readVarInt() == 1 ? Optional.ofNullable(codec.read(i)) : Optional.empty());
    }

    /** Wraps a codec so it can encode {@code null} (a presence byte + optional value). */
    public static <T> Codec<T> nullable(Codec<T> codec) {
        return make(WireType.LEN, (o, v) -> {
            if (v == null) {
                o.writeVarInt(0);
            } else {
                o.writeVarInt(1);
                codec.write(o, v);
            }
        }, i -> i.readVarInt() == 0 ? null : codec.read(i));
    }

    /** Starts building an {@link ObjectCodec} for a structured type. */
    public static <T> ObjectBuilder<T> object(Supplier<T> factory) {
        return new ObjectBuilder<>(factory);
    }

    /** Fluent builder for an {@link ObjectCodec}; each field needs a stable id, a codec, and accessors. */
    public static final class ObjectBuilder<T> {
        private final Supplier<T> factory;
        private final List<ObjectCodec.Field<T>> fields = new ArrayList<>();

        private ObjectBuilder(Supplier<T> factory) {
            this.factory = factory;
        }

        @SuppressWarnings("unchecked")
        public <V> ObjectBuilder<T> field(int id, Codec<V> codec, Function<T, V> getter, BiConsumer<T, V> setter) {
            fields.add(new ObjectCodec.Field<>(id, (Codec<Object>) codec,
                    t -> getter.apply(t), (t, v) -> setter.accept(t, (V) v)));
            return this;
        }

        public ObjectCodec<T> build() {
            return new ObjectCodec<>(factory, fields);
        }
    }
}
