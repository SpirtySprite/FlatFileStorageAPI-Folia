package me.kirug.flatfilestorage.codec;

import me.kirug.flatfilestorage.io.VarInputStream;

import java.io.IOException;

/**
 * The four on-wire shapes a field value can take. The wire type is what makes the format
 * self-describing: a reader that doesn't recognise a field id can still skip its value by knowing
 * only the wire type, so removed / renamed / reordered / unknown fields never corrupt the rest of
 * the record.
 *
 * <p>Each field is written as {@code [tag][value]} where {@code tag = (fieldId << 2) | wireType}.
 * Field id 0 is reserved as the end-of-object marker.
 */
public final class WireType {
    /** A single LEB128 varint (int, long, boolean-as-0/1, enum ordinal, epoch millis). */
    public static final int VARINT = 0;
    /** Exactly 4 bytes (float bits, fixed int). */
    public static final int I32 = 1;
    /** Exactly 8 bytes (double bits, fixed long). */
    public static final int I64 = 2;
    /** A byte-length-prefixed blob: {@code [varint byteLength][bytes]}. Anything variable-sized. */
    public static final int LEN = 3;

    /** Tag value that marks the end of an object's field stream. */
    static final int END_TAG = 0;

    private WireType() {
    }

    static int tag(int fieldId, int wireType) {
        return (fieldId << 2) | wireType;
    }

    static int fieldId(int tag) {
        return tag >>> 2;
    }

    static int wireType(int tag) {
        return tag & 0b11;
    }

    /** Skips a value of the given wire type without needing to know its concrete type. */
    static void skip(VarInputStream in, int wireType) throws IOException {
        switch (wireType) {
            case VARINT -> skipVarLong(in);
            case I32 -> in.skipNBytes(4);
            case I64 -> in.skipNBytes(8);
            case LEN -> in.skipNBytes(in.readVarInt());
            default -> throw new IOException("Unknown wire type: " + wireType);
        }
    }

    private static void skipVarLong(VarInputStream in) throws IOException {
        for (int i = 0; i < 10; i++) {
            if ((in.readByte() & 0x80) == 0) {
                return;
            }
        }
        throw new IOException("VarLong too long");
    }
}
