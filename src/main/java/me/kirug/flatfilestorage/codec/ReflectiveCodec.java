package me.kirug.flatfilestorage.codec;

import me.kirug.flatfilestorage.api.AutoSerializable;
import me.kirug.flatfilestorage.api.Serialize;
import me.kirug.flatfilestorage.api.data.StoredLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builds an {@link ObjectCodec} for a class from its {@link Serialize @Serialize}-annotated fields.
 * The annotation's {@code order} is treated as a <b>permanent field id</b> (like a protobuf field
 * number): never reuse a number for a different field, and you can add, remove, or reorder fields
 * freely. Access uses cached {@link MethodHandle}s.
 */
public final class ReflectiveCodec {
    private static final Map<Class<?>, ObjectCodec<?>> CACHE = new ConcurrentHashMap<>();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private ReflectiveCodec() {
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectCodec<T> of(Class<T> type) {
        return (ObjectCodec<T>) CACHE.computeIfAbsent(type, ReflectiveCodec::build);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectCodec<T> build(Class<?> raw) {
        Class<T> type = (Class<T>) raw;
        Supplier<T> factory = factoryFor(type);

        List<ObjectCodec.Field<T>> fields = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            Serialize ann = field.getAnnotation(Serialize.class);
            if (ann == null) {
                continue;
            }
            field.setAccessible(true);
            Codec<Object> codec = resolve(field.getGenericType());
            fields.add(new ObjectCodec.Field<>(ann.order(), codec, getter(field), setter(field)));
        }
        if (fields.isEmpty()) {
            throw new IllegalArgumentException(type.getName() + " has no @Serialize fields");
        }
        return new ObjectCodec<>(factory, fields);
    }

    private static <T> Supplier<T> factoryFor(Class<T> type) {
        try {
            Constructor<T> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            MethodHandle mh = LOOKUP.unreflectConstructor(ctor);
            return () -> {
                try {
                    //noinspection unchecked
                    return (T) mh.invoke();
                } catch (Throwable e) {
                    throw new RuntimeException("Failed to construct " + type.getName(), e);
                }
            };
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(type.getName()
                    + " needs a public no-args constructor to be auto-serialized", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> Function<T, Object> getter(Field field) {
        try {
            MethodHandle g = LOOKUP.unreflectGetter(field);
            return instance -> {
                try {
                    return g.invoke(instance);
                } catch (Throwable e) {
                    throw new RuntimeException("Failed reading field " + field.getName(), e);
                }
            };
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> BiConsumer<T, Object> setter(Field field) {
        try {
            MethodHandle s = LOOKUP.unreflectSetter(field);
            return (instance, value) -> {
                try {
                    s.invoke(instance, value);
                } catch (Throwable e) {
                    throw new RuntimeException("Failed writing field " + field.getName(), e);
                }
            };
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- type -> codec ----

    @SuppressWarnings("unchecked")
    private static Codec<Object> resolve(Type type) {
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            Type[] args = pt.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw)) {
                return (Codec<Object>) (Codec<?>) Codecs.list(resolve(args[0]));
            }
            if (Map.class.isAssignableFrom(raw)) {
                return (Codec<Object>) (Codec<?>) Codecs.map(resolve(args[0]), resolve(args[1]));
            }
            if (Optional.class.isAssignableFrom(raw)) {
                return (Codec<Object>) (Codec<?>) Codecs.optional(resolve(args[0]));
            }
            return resolveClass(raw);
        }
        if (type instanceof Class<?> c) {
            return resolveClass(c);
        }
        throw new IllegalArgumentException("Unsupported field type: " + type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Codec<Object> resolveClass(Class<?> c) {
        Codec<?> codec = builtin(c);
        if (codec != null) {
            return (Codec<Object>) codec;
        }
        if (c.isEnum()) {
            return (Codec<Object>) (Codec<?>) Codecs.enumOf((Class<? extends Enum>) c);
        }
        if (AutoSerializable.class.isAssignableFrom(c)) {
            return (Codec<Object>) (Codec<?>) ReflectiveCodec.of(c);
        }
        if (org.bukkit.Location.class.isAssignableFrom(c)) {
            // Supported for convenience; StoredLocation is preferred (no world lookup on read).
            return (Codec<Object>) (Codec<?>) Codecs.LOCATION.map(StoredLocation::toLocation, StoredLocation::of);
        }
        throw new IllegalArgumentException("No codec for field type " + c.getName()
                + " (use StoredLocation instead of Location, or provide an explicit Codec)");
    }

    private static Codec<?> builtin(Class<?> c) {
        if (c == int.class || c == Integer.class) return Codecs.INT;
        if (c == long.class || c == Long.class) return Codecs.LONG;
        if (c == boolean.class || c == Boolean.class) return Codecs.BOOLEAN;
        if (c == float.class || c == Float.class) return Codecs.FLOAT;
        if (c == double.class || c == Double.class) return Codecs.DOUBLE;
        if (c == String.class) return Codecs.STRING;
        if (c == java.util.UUID.class) return Codecs.UUID;
        if (c == byte[].class) return Codecs.BYTE_ARRAY;
        if (c == int[].class) return Codecs.INT_ARRAY;
        if (c == long[].class) return Codecs.LONG_ARRAY;
        if (c == java.math.BigInteger.class) return Codecs.BIG_INTEGER;
        if (c == java.time.Instant.class) return Codecs.INSTANT;
        if (c == java.time.LocalDateTime.class) return Codecs.LOCAL_DATE_TIME;
        if (c == StoredLocation.class) return Codecs.LOCATION;
        if (c == org.bukkit.util.Vector.class) return Codecs.VECTOR;
        if (c == me.kirug.flatfilestorage.api.data.ChunkReference.class) return Codecs.CHUNK;
        if (c == me.kirug.flatfilestorage.api.data.InventoryData.class) return Codecs.INVENTORY;
        if (net.kyori.adventure.text.Component.class.isAssignableFrom(c)) return Codecs.COMPONENT;
        return null;
    }
}
