package me.kirug.flatfilestorage.api;

import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-performance Automatic Serialization base class.
 * Extend this to strictly typed data classes using @Serialize annotations.
 */
public abstract class AutoSerializable implements SerializableObject {

    private static final Map<Class<?>, List<FieldHandler>> CACHE = new ConcurrentHashMap<>();

    private record FieldHandler(MethodHandle getter, MethodHandle setter, int order, int since, HandlerType type, Class<?> fieldClass, HandlerType genericK, HandlerType genericV) {}

    private enum HandlerType {
        INT, LONG, DOUBLE, FLOAT, BOOLEAN, STRING, UUID, 
        BIG_INTEGER, LOCATION, CHUNK_REF, INVENTORY_DATA, COMPONENT,
        BYTE_ARRAY, INT_ARRAY, LONG_ARRAY, VECTOR, BLOCK_FACE,
        OPTIONAL, INSTANT, LOCAL_DATE_TIME,
        LIST, MAP, ENUM, AUTO_SERIALIZABLE, UNKNOWN
    }

    public AutoSerializable() {
        // Ensure cache is populated for this class
        getHandlers(this.getClass());
    }

    private static List<FieldHandler> getHandlers(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, c -> {
            // DEVELOPER SAFETY: Check for no-args constructor
            try {
                c.getDeclaredConstructor();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("DEVELOPER ERROR: Class " + c.getName() + " extends AutoSerializable but is missing a public no-args constructor! It is required for loading.");
            }
            
            List<FieldHandler> handlers = new ArrayList<>();
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            for (Field field : c.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Serialize.class)) continue;
                Serialize ann = field.getAnnotation(Serialize.class);
                field.setAccessible(true);

                try {
                    MethodHandle getter = lookup.unreflectGetter(field);
                    MethodHandle setter = lookup.unreflectSetter(field);
                    HandlerType type = getType(field.getType());
                    
                    HandlerType genK = null;
                    HandlerType genV = null;
                    
                    if (type == HandlerType.LIST) {
                         genV = getType(getGenericArg(field, 0));
                    } else if (type == HandlerType.MAP) {
                         genK = getType(getGenericArg(field, 0));
                         genV = getType(getGenericArg(field, 1));
                    } else if (type == HandlerType.OPTIONAL) {
                         genV = getType(getGenericArg(field, 0));
                    }

                    handlers.add(new FieldHandler(getter, setter, ann.order(), ann.since(), type, field.getType(), genK, genV));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to access field " + field.getName(), e);
                }
            }
            handlers.sort(Comparator.comparingInt(FieldHandler::order));
            return handlers;
        });
    }

    private static HandlerType getType(Class<?> type) {
        if (type == int.class || type == Integer.class) return HandlerType.INT;
        if (type == long.class || type == Long.class) return HandlerType.LONG;
        if (type == double.class || type == Double.class) return HandlerType.DOUBLE;
        if (type == float.class || type == Float.class) return HandlerType.FLOAT;
        if (type == boolean.class || type == Boolean.class) return HandlerType.BOOLEAN;
        if (type == String.class) return HandlerType.STRING;
        if (type == java.util.UUID.class) return HandlerType.UUID;
        if (type == java.math.BigInteger.class) return HandlerType.BIG_INTEGER;
        if (type == org.bukkit.Location.class) return HandlerType.LOCATION;
        if (type == org.bukkit.util.Vector.class) return HandlerType.VECTOR;
        if (type == org.bukkit.block.BlockFace.class) return HandlerType.BLOCK_FACE;
        if (type == me.kirug.flatfilestorage.api.data.ChunkReference.class) return HandlerType.CHUNK_REF;
        if (type == me.kirug.flatfilestorage.api.data.InventoryData.class) return HandlerType.INVENTORY_DATA;
        if (type == byte[].class) return HandlerType.BYTE_ARRAY;
        if (type == int[].class) return HandlerType.INT_ARRAY;
        if (type == long[].class) return HandlerType.LONG_ARRAY;
        if (net.kyori.adventure.text.Component.class.isAssignableFrom(type)) return HandlerType.COMPONENT;
        if (java.time.Instant.class.isAssignableFrom(type)) return HandlerType.INSTANT;
        if (java.time.LocalDateTime.class.isAssignableFrom(type)) return HandlerType.LOCAL_DATE_TIME;
        if (Optional.class.isAssignableFrom(type)) return HandlerType.OPTIONAL;
        if (List.class.isAssignableFrom(type)) return HandlerType.LIST;
        if (Map.class.isAssignableFrom(type)) return HandlerType.MAP;
        if (Enum.class.isAssignableFrom(type)) return HandlerType.ENUM;
        if (AutoSerializable.class.isAssignableFrom(type)) return HandlerType.AUTO_SERIALIZABLE;
        return HandlerType.UNKNOWN; 
    }
    
    private static Class<?> getGenericArg(Field f, int index) {
         try {
             Type genericType = f.getGenericType();
             if (genericType instanceof ParameterizedType) {
                 Type[] args = ((ParameterizedType) genericType).getActualTypeArguments();
                 if (index < args.length) {
                     if (args[index] instanceof Class) {
                         return (Class<?>) args[index];
                     } else if (args[index] instanceof ParameterizedType) {
                         // Handle nested generics like List<List<String>> -> raw type List
                         return (Class<?>) ((ParameterizedType) args[index]).getRawType();
                     }
                 }
             }
         } catch (Exception ignored) {}
         return Object.class;
    }

    @Override
    public void write(VarOutputStream out) throws java.io.IOException {
        List<FieldHandler> handlers = getHandlers(this.getClass());
        for (FieldHandler h : handlers) {
            try {
                Object val = h.getter.invoke(this);
                writeVal(out, val, h.type, h.fieldClass, h.genericK, h.genericV);
            } catch (Throwable e) {
                throw new java.io.IOException("AutoSerialize Write Error", e);
            }
        }
    }
    
    @Override
    public void read(VarInputStream in, int version) throws java.io.IOException {
        List<FieldHandler> handlers = getHandlers(this.getClass());
        for (FieldHandler h : handlers) {
            if (version < h.since) continue; // Skip new fields when loading old data
            
            try {
                Object val = readVal(in, h.type, h.fieldClass, h.genericK, h.genericV, version);
                h.setter.invoke(this, val);
            } catch (Throwable e) {
                throw new java.io.IOException("AutoSerialize Read Error", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void writeVal(VarOutputStream out, Object va, HandlerType t, Class<?> clazz, HandlerType k, HandlerType v) throws java.io.IOException {
         // Null handling
         if (va == null && t != HandlerType.OPTIONAL) { // Optional handles null/empty itself
              // For other recursive types potentially?
              // Assuming primitives handled by writer, objects might need null check if not handled?
              // VarOutputStream typically needs explicit null check for objects unless specific method handles it.
              // We rely on specific handlers.
         }
         
         switch (t) {
             case INT -> out.writeVarInt((Integer) va);
             case LONG -> out.writeVarLong((Long) va);
             case STRING -> out.writeString((String) va);
             case BOOLEAN -> out.writeBoolean((Boolean) va);
             case FLOAT -> out.writeFloat((Float) va);
             case DOUBLE -> out.writeDouble((Double) va);
             case BIG_INTEGER -> out.writeBigInteger((java.math.BigInteger) va);
             case UUID -> out.writeUUID((java.util.UUID) va);
             case LOCATION -> out.writeLocation((org.bukkit.Location) va);
             case CHUNK_REF -> out.writeChunk((me.kirug.flatfilestorage.api.data.ChunkReference) va);
             case INVENTORY_DATA -> out.writeInventory((me.kirug.flatfilestorage.api.data.InventoryData) va);
             case COMPONENT -> out.writeComponent((net.kyori.adventure.text.Component) va);
             case BYTE_ARRAY -> out.writeByteArray((byte[]) va);
             case INT_ARRAY -> out.writeIntArray((int[]) va);
             case LONG_ARRAY -> out.writeLongArray((long[]) va);
             case VECTOR -> out.writeVector((org.bukkit.util.Vector) va);
             case BLOCK_FACE -> out.writeEnum((org.bukkit.block.BlockFace) va);
             case INSTANT -> {
                  if (va == null) out.writeLong(Long.MIN_VALUE); // Null marker
                  else out.writeLong(((java.time.Instant) va).toEpochMilli());
             }
             case LOCAL_DATE_TIME -> {
                  if (va == null) out.writeString(""); 
                  else out.writeString(((java.time.LocalDateTime) va).toString());
             }
             case OPTIONAL -> {
                  Optional<?> opt = (Optional<?>) va;
                  if (opt != null && opt.isPresent()) {
                      out.writeBoolean(true);
                      writeVal(out, opt.get(), v, null, null, null);
                  } else {
                      out.writeBoolean(false);
                  }
             }
             case AUTO_SERIALIZABLE -> {
                  if (va == null) {
                      out.writeBoolean(false);
                  } else {
                      out.writeBoolean(true);
                      ((AutoSerializable) va).write(out);
                  }
             }
             case LIST -> out.writeList((List) va, (o, item) -> writeVal(o, item, v, null, null, null));
             case MAP -> out.writeMap((Map) va, (o, key) -> writeVal(o, key, k, null, null, null), (o, val) -> writeVal(o, val, v, null, null, null));
             case ENUM -> out.writeEnum((Enum) va);
         }
    }
    
    @SuppressWarnings("unchecked")
    private Object readVal(VarInputStream in, HandlerType t, Class<?> clazz, HandlerType k, HandlerType v, int version) throws java.io.IOException {
        switch (t) {
             case INT -> { return in.readVarInt(); }
             case LONG -> { return in.readVarLong(); }
             case STRING -> { return in.readString(); }
             case BOOLEAN -> { return in.readBoolean(); }
             case FLOAT -> { return in.readFloat(); }
             case DOUBLE -> { return in.readDouble(); }
             case BIG_INTEGER -> { return in.readBigInteger(); }
             case UUID -> { return in.readUUID(); }
             case LOCATION -> { return in.readLocation(); }
             case CHUNK_REF -> { return in.readChunk(); }
             case INVENTORY_DATA -> { return in.readInventory(); }
             case COMPONENT -> { return in.readComponent(); }
             case BYTE_ARRAY -> { return in.readByteArray(); }
             case INT_ARRAY -> { return in.readIntArray(); }
             case LONG_ARRAY -> { return in.readLongArray(); }
             case VECTOR -> { return in.readVector(); }
             case BLOCK_FACE -> { return in.readEnum(org.bukkit.block.BlockFace.class); }
             case INSTANT -> {
                  long val = in.readVarLong();
                  if (val == Long.MIN_VALUE) return null;
                  return java.time.Instant.ofEpochMilli(val);
             }
             case LOCAL_DATE_TIME -> {
                  String val = in.readString();
                  if (val == null || val.isEmpty()) return null;
                  return java.time.LocalDateTime.parse(val);
             }
             case OPTIONAL -> {
                  if (in.readBoolean()) {
                      Object val = readVal(in, v, null, null, null, version);
                      return Optional.ofNullable(val);
                  } else {
                      return Optional.empty();
                  }
             }
             case AUTO_SERIALIZABLE -> {
                 if (!in.readBoolean()) return null;
                 try {
                     AutoSerializable obj = (AutoSerializable) clazz.getDeclaredConstructor().newInstance();
                     // Pass the parent version context down
                     obj.read(in, version);
                     return obj;
                 } catch (Exception e) {
                     throw new java.io.IOException("Failed to instantiate recursive AutoSerializable", e);
                 }
             }
             case LIST -> { return in.readList(i -> readVal(i, v, null, null, null, version)); }
             case MAP -> { return in.readMap(i -> readVal(i, k, null, null, null, version), i -> readVal(i, v, null, null, null, version)); }
             case ENUM -> { return in.readEnum((Class<? extends Enum>) clazz); }
        }
        return null;
    }
}
