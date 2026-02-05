package me.kirug.flatfilestorage.io;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Optimized Output Stream using LEB128 for integers and UTF-8 for strings.
 * Extends DataOutputStream for convenience.
 */
public class VarOutputStream extends DataOutputStream {

    public VarOutputStream(OutputStream out) {
        super(out);
    }
    
    private static final net.kyori.adventure.text.serializer.gson.GsonComponentSerializer GSON = 
        net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();

    /**
     * Writes an exact number of bytes from the integer using LEB128 encoding.
     * Use this for array lengths or small integers to save space.
     */
    public void writeVarInt(int value) throws IOException {
        while ((value & -128) != 0) {
            writeByte(value & 127 | 128);
            value >>>= 7;
        }
        writeByte(value);
    }

    /**
     * Writes a long using LEB128 encoding.
     */
    public void writeVarLong(long value) throws IOException {
        while ((value & -128L) != 0L) {
            writeByte((int) (value & 127L) | 128);
            value >>>= 7;
        }
        writeByte((int) value);
    }

    /**
     * Writes a string as (VarInt Length + UTF-8 Bytes).
     */
    public void writeString(String s) throws IOException {
        if (s == null) {
            writeVarInt(0);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        write(bytes);
    }
    
    // Helper to write Enums efficiently using ordinal
    public <E extends Enum<E>> void writeEnum(E e) throws IOException {
        if (e == null) {
            writeVarInt(-1);
        } else {
            writeVarInt(e.ordinal());
        }
    }

    // ==========================================
    //              MATH TYPES
    // ==========================================

    public void writeUUID(java.util.UUID uuid) throws IOException {
        if (uuid == null) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            writeLong(uuid.getMostSignificantBits());
            writeLong(uuid.getLeastSignificantBits());
        }
    }

    public void writeBigInteger(java.math.BigInteger bigInt) throws IOException {
        if (bigInt == null) {
            writeVarInt(0);
        } else {
            byte[] bytes = bigInt.toByteArray();
            writeVarInt(bytes.length);
            write(bytes);
        }
    }

    public void writeBigDecimal(java.math.BigDecimal bigDec) throws IOException {
        if (bigDec == null) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            writeBigInteger(bigDec.unscaledValue());
            writeVarInt(bigDec.scale());
        }
    }

    // ==========================================
    //              COLLECTIONS
    // ==========================================

    @FunctionalInterface
    public interface IOWriter<T> {
        void write(VarOutputStream out, T value) throws IOException;
    }
    
    @FunctionalInterface 
    public interface IOReader<T> {
        T read(VarInputStream in) throws IOException;
    }

    public <T> void writeList(java.util.List<T> list, IOWriter<T> writer) throws IOException {
        if (list == null) {
            writeVarInt(-1);
            return;
        }
        writeVarInt(list.size());
        for (T item : list) {
            writer.write(this, item);
        }
    }

    public <K, V> void writeMap(java.util.Map<K, V> map, IOWriter<K> keyWriter, IOWriter<V> valueWriter) throws IOException {
        if (map == null) {
            writeVarInt(-1);
            return;
        }
        writeVarInt(map.size());
        for (java.util.Map.Entry<K, V> entry : map.entrySet()) {
            keyWriter.write(this, entry.getKey());
            valueWriter.write(this, entry.getValue());
        }
    }

    // ==========================================
    //              PRIMITIVE ARRAYS
    // ==========================================

    public void writeByteArray(byte[] array) throws IOException {
        if (array == null) {
            writeVarInt(-1);
        } else {
            writeVarInt(array.length);
            write(array);
        }
    }

    public void writeIntArray(int[] array) throws IOException {
        if (array == null) {
            writeVarInt(-1);
        } else {
            writeVarInt(array.length);
            for (int i : array) writeVarInt(i);
        }
    }

    public void writeLongArray(long[] array) throws IOException {
        if (array == null) {
            writeVarInt(-1);
        } else {
            writeVarInt(array.length);
            for (long l : array) writeVarLong(l);
        }
    }

    // ==========================================
    //              BUKKIT TYPES
    // ==========================================

    public void writeVector(org.bukkit.util.Vector vector) throws IOException {
        if (vector == null) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            writeDouble(vector.getX());
            writeDouble(vector.getY());
            writeDouble(vector.getZ());
        }
    }

    public void writeWorld(org.bukkit.World world) throws IOException {
        // We only store the UUID. 
        // WARNING: If world is deleted, this becomes invalid, but that is expected.
        writeUUID(world == null ? null : world.getUID());
    }

    public void writeLocation(org.bukkit.Location loc) throws IOException {
        if (loc == null) {
            writeBoolean(false);
            return;
        }
        writeBoolean(true);
        writeWorld(loc.getWorld());
        writeDouble(loc.getX());
        writeDouble(loc.getY());
        writeDouble(loc.getZ());
        writeFloat(loc.getYaw());
        writeFloat(loc.getPitch());
    }

    public void writeChunk(me.kirug.flatfilestorage.api.data.ChunkReference chunk) throws IOException {
        if (chunk == null) {
            writeBoolean(false);
            return;
        }
        writeBoolean(true);
        writeString(chunk.worldName());
        writeVarInt(chunk.x());
        writeVarInt(chunk.z());
    }
    
    /**
     * Writes an ItemStack effectively using Bukkit's internal serialization (YAML-based or Bytes).
     * For pure speed and 100% NBT support, we use the internal `serializeAsBytes` if available 
     * or standard ObjectOutputStream wrapper around Bukkit's configuration serialization.
     * 
     * Optimization: We convert the Map<String, Object> from serialize() to binary directly.
     */
    public void writeItemStack(org.bukkit.inventory.ItemStack item) throws IOException {
        if (item == null || item.getType() == org.bukkit.Material.AIR) {
            writeBoolean(false);
            return;
        }
        writeBoolean(true);
        byte[] bytes = item.serializeAsBytes(); 
        writeVarInt(bytes.length);
        write(bytes);
    }

    public void writeInventory(me.kirug.flatfilestorage.api.data.InventoryData inv) throws IOException {
        if (inv == null) {
            writeVarInt(-1);
            return;
        }
        writeVarInt(inv.size());
        writeComponent(inv.title());
        
        // Write content map
        writeVarInt(inv.contents().size());
        for (java.util.Map.Entry<Integer, org.bukkit.inventory.ItemStack> entry : inv.contents().entrySet()) {
            writeVarInt(entry.getKey());
            writeItemStack(entry.getValue());
        }
    }
}
