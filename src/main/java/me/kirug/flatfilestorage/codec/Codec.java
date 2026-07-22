package me.kirug.flatfilestorage.codec;

import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;

import java.io.IOException;

/**
 * Reads and writes a single value type to the binary stream.
 *
 * <p>A codec writes a <b>self-delimiting</b> encoding of its value: it must read back exactly what
 * it wrote. {@link #wireType()} tells the field framer how to length-frame the value so unknown
 * fields can be skipped. Codecs compose: a list codec is built from an element codec, an object
 * codec from field codecs, and so on.
 *
 * @param <T> the value type
 */
public interface Codec<T> {

    /** One of {@link WireType}. Determines how the value is framed at the field level. */
    int wireType();

    /** Writes {@code value}; must be self-delimiting (readable without external length info). */
    void write(VarOutputStream out, T value) throws IOException;

    /** Reads a value previously written by {@link #write}. */
    T read(VarInputStream in) throws IOException;

    /** Maps this codec to another type via a pair of pure functions. */
    default <U> Codec<U> map(java.util.function.Function<T, U> to, java.util.function.Function<U, T> from) {
        Codec<T> self = this;
        return new Codec<>() {
            @Override
            public int wireType() {
                return self.wireType();
            }

            @Override
            public void write(VarOutputStream out, U value) throws IOException {
                self.write(out, from.apply(value));
            }

            @Override
            public U read(VarInputStream in) throws IOException {
                return to.apply(self.read(in));
            }
        };
    }
}
