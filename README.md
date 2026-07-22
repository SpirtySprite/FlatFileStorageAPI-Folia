# FlatFileStorageAPI

![Folia](https://img.shields.io/badge/Folia-supported-green) ![Java](https://img.shields.io/badge/Java-21+-orange)

A fast, thread-safe key/value store for Paper and Folia. Data is written in a compact, **self-describing
binary format**, so you can evolve your data classes over time (add, remove, reorder fields) without
breaking old files or writing migration code.

## Install

This is a standalone plugin. Do **not** shade it.

1. Drop `FlatFileStorageAPI.jar` into `plugins/`.
2. In your plugin: `depend: [FlatFileStorageAPI]` in `plugin.yml`.
3. In your `pom.xml`, add the API with `provided` scope:

```xml
<dependency>
  <groupId>me.kirug</groupId>
  <artifactId>FlatFileStorageAPI</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

Get the API with `FlatFileProvider.get()`.

## Defining data

Two ways, use whichever fits.

### Annotation (simplest)

Extend `AutoSerializable`, annotate fields, add a no-args constructor. No read/write code.

```java
public class Profile extends AutoSerializable {

    @Serialize(order = 1) private String name;
    @Serialize(order = 2) private int level;
    @Serialize(order = 3) private List<String> friends;
    @Serialize(order = 4) private StoredLocation home;   // Folia-safe location snapshot

    public Profile() {}                                  // required

    public Profile(String name) {
        this.name = name;
        this.friends = new ArrayList<>();
    }
}
```

**The `order` number is a permanent field id** (think protobuf field numbers). The rules:

- Pick a number per field and **never reuse it** for a different field.
- You can **add** fields (new id), **remove** fields (retire the id), or **reorder** them freely.
  Old files still load; new files still load on old code.
- You do **not** need to bump a version when adding or removing fields.

Supported field types: primitives and their boxes, `String`, `UUID`, `BigInteger`, `Instant`,
`LocalDateTime`, enums, `byte[]/int[]/long[]`, `List`, `Map`, `Optional`, Adventure `Component`,
`Vector`, `StoredLocation`, `ChunkReference`, `InventoryData`, and nested `AutoSerializable`s.

### Explicit codec (full control)

For records, immutables, or custom encodings, build a `Codec` yourself. Same field-id rules.

```java
public static final Codec<Profile> CODEC = Codecs.object(Profile::new)
    .field(1, Codecs.STRING, Profile::getName, Profile::setName)
    .field(2, Codecs.INT,    Profile::getLevel, Profile::setLevel)
    .field(3, Codecs.list(Codecs.STRING), Profile::getFriends, Profile::setFriends)
    .build();
```

## Saving and loading

Everything is async and returns a `CompletableFuture`.

```java
StorageAPI storage = FlatFileProvider.get();

// annotation path
storage.save(uuid, profile);
storage.load(uuid, Profile::new).thenAccept(p -> { /* p is null if not found */ });

// explicit-codec path
storage.save(uuid, profile, Profile.CODEC);
storage.load(uuid, Profile.CODEC).thenAccept(p -> { ... });

storage.exists(uuid);
storage.delete(uuid);
```

## Threading (Folia)

- **`save(...)` serializes on the calling thread**, then writes to disk in the background. That means
  two things: the write is a consistent point-in-time snapshot (a concurrent mutation can't tear it),
  and any Bukkit data your object reads is read on the correct region thread. Call `save` from the
  region thread that owns the data (e.g. the player's thread).
- **Loads return snapshot types** (`StoredLocation`, `ChunkReference`, `InventoryData`, world UUIDs),
  never live world/entity objects, so decoding is safe off-thread. Convert to live Bukkit objects on
  the region thread: `storedLocation.toLocation()`, `inventoryData.apply(inv)`.

## Durability

- Every write goes to a temp file, is fsync'd, its checksum (CRC32C) is **verified**, and only then
  atomically promoted. The previous file is kept as a `.bak` and used automatically if the main file
  is ever unreadable.
- **No lost writes.** Rapid saves for the same key are coalesced by a single-flight pipeline that
  always writes the newest bytes; a save issued while another is in flight is never dropped.
- Files are compressed with Zstd when large, and keys are sanitized and sharded into subdirectories,
  so an id containing slashes or odd characters can't escape the data folder.

## Configuration (`config.yml`)

```yaml
debug: false
```

## Migrations (rare)

Because the format is tagged, add/remove/reorder needs no migration. Only override
`AutoSerializable#dataVersion()` when the *meaning* of a stored value changes (e.g. a unit change);
the stored version is available at load time for future hooks.
