package me.kirug.flatfilestorage.codec;

import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Codec for a structured object, written as a stream of tagged fields terminated by an end marker.
 *
 * <p>This is where schema evolution comes from. On read, each field's id is looked up:
 * <ul>
 *   <li>known id, matching wire type -> decode and assign;</li>
 *   <li>unknown id (a field that was removed, or is newer than this code) -> skip by wire type;</li>
 *   <li>known id, different wire type (the field's type changed) -> skip, leaving the default.</li>
 * </ul>
 * So fields can be added, removed, reordered, or retyped without corrupting the record, as long as a
 * field id is never reused for a different meaning.
 *
 * @param <T> the object type
 */
public final class ObjectCodec<T> implements Codec<T> {

    /** One field: a stable id, a value codec, and accessors. */
    public record Field<T>(int id, Codec<Object> codec, Function<T, Object> getter, BiConsumer<T, Object> setter) {
    }

    private final Supplier<T> factory;
    private final List<Field<T>> ordered;      // by id, for deterministic writes
    private final Map<Integer, Field<T>> byId;

    public ObjectCodec(Supplier<T> factory, List<Field<T>> fields) {
        this.factory = factory;
        this.ordered = new ArrayList<>(fields);
        this.ordered.sort(Comparator.comparingInt(Field::id));
        this.byId = new HashMap<>();
        for (Field<T> f : fields) {
            if (f.id() <= 0) {
                throw new IllegalArgumentException("Field id must be >= 1 (0 is reserved), got " + f.id());
            }
            if (byId.put(f.id(), f) != null) {
                throw new IllegalArgumentException("Duplicate field id " + f.id());
            }
        }
    }

    @Override
    public int wireType() {
        return WireType.LEN;
    }

    @Override
    public void write(VarOutputStream out, T value) throws IOException {
        for (Field<T> f : ordered) {
            writeField(out, f.id(), f.codec(), f.getter().apply(value));
        }
        out.writeVarInt(WireType.END_TAG);
    }

    @Override
    public T read(VarInputStream in) throws IOException {
        T instance = factory.get();
        while (true) {
            int tag = in.readVarInt();
            if (tag == WireType.END_TAG) {
                break;
            }
            int id = WireType.fieldId(tag);
            int wire = WireType.wireType(tag);
            Field<T> field = byId.get(id);
            if (field != null && field.codec().wireType() == wire) {
                field.setter().accept(instance, readFieldValue(in, field.codec(), wire));
            } else {
                WireType.skip(in, wire);
            }
        }
        return instance;
    }

    /** Writes {@code [tag][value]}, length-framing LEN values so unknown readers can skip them. */
    static void writeField(VarOutputStream out, int id, Codec<Object> codec, Object value) throws IOException {
        int wire = codec.wireType();
        out.writeVarInt(WireType.tag(id, wire));
        if (wire == WireType.LEN) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
            VarOutputStream sub = new VarOutputStream(baos);
            codec.write(sub, value);
            sub.flush();
            byte[] bytes = baos.toByteArray();
            out.writeVarInt(bytes.length);
            out.write(bytes);
        } else {
            codec.write(out, value);
        }
    }

    /** Reads a known field's value; LEN values decode from an isolated slice so a bad field can't over-read. */
    static Object readFieldValue(VarInputStream in, Codec<Object> codec, int wire) throws IOException {
        if (wire == WireType.LEN) {
            int len = in.readVarInt();
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            return codec.read(new VarInputStream(new ByteArrayInputStream(bytes)));
        }
        return codec.read(in);
    }
}
