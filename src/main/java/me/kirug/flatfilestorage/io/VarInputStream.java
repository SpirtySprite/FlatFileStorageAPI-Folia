package me.kirug.flatfilestorage.io;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Optimized Input Stream matching VarOutputStream.
 * Features String Interning to deduplicate strings in memory.
 */
public class VarInputStream extends DataInputStream {
    
    public VarInputStream(InputStream in) {
        super(in);
    }
    
    private static final net.kyori.adventure.text.serializer.gson.GsonComponentSerializer GSON = 
        net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson();

    public int readVarInt() throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = readByte();
            value |= (currentByte & 127) << position;

            if ((currentByte & 128) == 0) break;

            position += 7;

            if (position >= 32) {
                throw new IOException("VarInt is too big");
            }
        }
        return value;
    }

    public long readVarLong() throws IOException {
        long value = 0;
        int position = 0;
        byte currentByte;

        while (true) {
            currentByte = readByte();
            value |= (long) (currentByte & 127) << position;

            if ((currentByte & 128) == 0) break;

            position += 7;

            if (position >= 64) {
                throw new IOException("VarLong is too big");
            }
        }
        return value;
    }

    /**
     * Local Intern Map: Deduplicates strings ONLY within this stream's lifecycle.
     */
    private final java.util.Map<String, String> localIntern = new java.util.HashMap<>();

    /**
     * Reads a UTF-8 string with a VarInt length prefix.
     * Uses local interning to reduce memory usage for repetitive strings.
     * @return The read string.
     * @throws IOException If an IO error occurs.
     */
    public String readString() throws IOException {
        int length = readVarInt();
        if (length == 0) return "";
        
        byte[] bytes = new byte[length];
        readFully(bytes);
        
        String s = new String(bytes, StandardCharsets.UTF_8);
        return localIntern.computeIfAbsent(s, k -> k);
    }
    
    // Helper to read Enums
    public <E extends Enum<E>> E readEnum(Class<E> clazz) throws IOException {
        int ordinal = readVarInt();
        if (ordinal == -1) return null;
        E[] constants = clazz.getEnumConstants();
        if (ordinal < 0 || ordinal >= constants.length) {
            throw new IOException("Invalid enum ordinal: " + ordinal);
        }
        return constants[ordinal];
    }
    
    // ==========================================
    //              MATH TYPES
    // ==========================================

    public java.util.UUID readUUID() throws IOException {
        if (!readBoolean()) return null;
        return new java.util.UUID(readLong(), readLong());
    }

    public java.math.BigInteger readBigInteger() throws IOException {
        int len = readVarInt();
        if (len == 0) return null;
        byte[] bytes = new byte[len];
        readFully(bytes);
        return new java.math.BigInteger(bytes);
    }

    public java.math.BigDecimal readBigDecimal() throws IOException {
        if (!readBoolean()) return null;
        java.math.BigInteger unscaled = readBigInteger();
        int scale = readVarInt();
        return new java.math.BigDecimal(unscaled, scale);
    }

    // ==========================================
    //              COLLECTIONS
    // ==========================================

    public <T> java.util.List<T> readList(VarOutputStream.IOReader<T> reader) throws IOException {
        int size = readVarInt();
        if (size == -1) return null;
        java.util.List<T> list = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(reader.read(this));
        }
        return list;
    }

    public <K, V> java.util.Map<K, V> readMap(VarOutputStream.IOReader<K> keyReader, VarOutputStream.IOReader<V> valReader) throws IOException {
        int size = readVarInt();
        if (size == -1) return null;
        java.util.Map<K, V> map = new java.util.HashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(keyReader.read(this), valReader.read(this));
        }
        return map;
    }

    // ==========================================
    //              PRIMITIVE ARRAYS
    // ==========================================

    public byte[] readByteArray() throws IOException {
        int len = readVarInt();
        if (len == -1) return null;
        byte[] array = new byte[len];
        readFully(array);
        return array;
    }

    public int[] readIntArray() throws IOException {
        int len = readVarInt();
        if (len == -1) return null;
        int[] array = new int[len];
        for (int i = 0; i < len; i++) array[i] = readVarInt();
        return array;
    }

    public long[] readLongArray() throws IOException {
        int len = readVarInt();
        if (len == -1) return null;
        long[] array = new long[len];
        for (int i = 0; i < len; i++) array[i] = readVarLong();
        return array;
    }

    public net.kyori.adventure.text.Component readComponent() throws IOException {
        String json = readString();
        if (json == null || json.isEmpty()) return null;
        return GSON.deserialize(json);
    }
    
    // ==========================================
    //              BUKKIT TYPES
    // ==========================================

    public org.bukkit.util.Vector readVector() throws IOException {
        if (!readBoolean()) return null;
        return new org.bukkit.util.Vector(readDouble(), readDouble(), readDouble());
    }


    /**
     * Reads a World UID. Does not return a World object to avoid main-thread calls.
     */
    public java.util.UUID readWorldId() throws IOException {
        return readUUID();
    }
    
    @Deprecated
    public org.bukkit.World readWorld() throws IOException {
         // Still needed for legacy or if user knows what they are doing, 
         // but preferably use readWorldId()
         java.util.UUID uid = readUUID();
         if (uid == null) return null;
         return org.bukkit.Bukkit.getWorld(uid);
    }

    public org.bukkit.Location readLocation() throws IOException {
        if (!readBoolean()) return null;
        // WARNING: Thread unsafe if used blindly.
        // We construct it, but getting World async is risky if world isn't loaded?
        // Actually Bukkit.getWorld(UUID) is usually safe for lookup.
        org.bukkit.World world = readWorld();
        double x = readDouble();
        double y = readDouble();
        double z = readDouble();
        float yaw = readFloat();
        float pitch = readFloat();
        return new org.bukkit.Location(world, x, y, z, yaw, pitch);
    }

    public me.kirug.flatfilestorage.api.data.ChunkReference readChunk() throws IOException {
        if (!readBoolean()) return null;
        String worldName = readString();
        int x = readVarInt();
        int z = readVarInt();
        return new me.kirug.flatfilestorage.api.data.ChunkReference(worldName, x, z);
    }

    public org.bukkit.inventory.ItemStack readItemStack() throws IOException {
        if (!readBoolean()) return null;
        int len = readVarInt();
        if (len == 0) return null; // Should not happen if boolean true
        byte[] bytes = new byte[len];
        readFully(bytes);
        return org.bukkit.inventory.ItemStack.deserializeBytes(bytes);
    }

    public me.kirug.flatfilestorage.api.data.InventoryData readInventory() throws IOException {
        int size = readVarInt();
        if (size == -1) return null;
        
        net.kyori.adventure.text.Component title = readComponent();
        int contentSize = readVarInt();
        
        java.util.Map<Integer, org.bukkit.inventory.ItemStack> contents = new java.util.HashMap<>(contentSize);
        for (int i = 0; i < contentSize; i++) {
            int slot = readVarInt();
            org.bukkit.inventory.ItemStack item = readItemStack();
            if (item != null) contents.put(slot, item);
        }
        
        return new me.kirug.flatfilestorage.api.data.InventoryData(title, size, contents);
    }
}
