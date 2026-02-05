# FlatFileStorageAPI-Folia 🚀

![Version](https://img.shields.io/badge/version-1.1-blue) ![Folia](https://img.shields.io/badge/Folia-Supported-green) ![Java](https://img.shields.io/badge/Java-21+-orange)

**The heavy-duty, thread-safe storage engine for modern Minecraft servers.**  
Designed for **Folia**, **Paper**, and high-concurrency environments.

---

## ⚠️ CRITICAL IMPLEMENTATION NOTICE

**THIS IS A STANDALONE PLUGIN.**
> **DO NOT SHADE** this API into your plugin jar.

1.  **Download** `FlatFileStorageAPI.jar` and drop it into your server's `/plugins/` folder.
2.  Add `depend: [FlatFileStorageAPI]` to your `plugin.yml`.
3.  Add the dependency to your `pom.xml` with `<scope>provided</scope>`.

Shading this API will cause class conflicts, version mismatches, and **will break** the caching system.

---

## ✨ Features (v1.1.0)

*   **Folia-Native Thread Safety**: 
    *   No more `Thread failed main thread check` errors.
    *   IO operations use safe data snapshots (`ChunkReference`, `InventoryData`) instead of live Bukkit objects.
*   **Performance Beast**:
    *   **Virtual Threads (Project Loom)**: Handles thousands of concurrent IO operations without blocking OS threads.
    *   **Hardware Accel**: Uses CRC32C (Castagnoli) for ultra-fast data integrity checks.
    *   **Primitive Arrays**: Native optimization for `int[]`, `byte[]`, and `long[]` (no boxing).
*   **AutoSerializable Magic**:
    *   Annotate your fields with `@Serialize`.
    *   No manual `read()` / `write()` methods required.
    *   Supports **Versioning** (`start=2`) for safe updates.
    *   Supports **Recursive** objects, **Collections**, **Vectors**, and **Adventure Components**.

---

## 🛠️ Usage

### 1. Create your Data Class
Extend `AutoSerializable` and add `@Serialize` annotations.

```java
import me.kirug.flatfilestorage.api.AutoSerializable;
import me.kirug.flatfilestorage.api.Serialize;
import org.bukkit.util.Vector;
import java.util.List;

public class PlayerProfile extends AutoSerializable {

    @Serialize(order = 1) 
    private String username;

    @Serialize(order = 2) 
    private double balance;

    @Serialize(order = 3) 
    private List<String> permissions;

    @Serialize(order = 4) 
    private Vector pendingTeleport;

    @Serialize(order = 5, since = 2) 
    private byte[] customData; // Efficient primitive array

    // MUST have an empty constructor
    public PlayerProfile() {}

    public PlayerProfile(String name) {
        this.username = name;
        this.balance = 500.0;
    }
}
```

### 2. Save & Load
Access the API via the singleton instance.

```java
import me.kirug.flatfilestorage.api.StorageAPI;

public class MyPlugin {
    
    private final StorageAPI storage = StorageAPI.getInstance();

    public void saveProfile(String uuid, PlayerProfile profile) {
        // Returns CompletableFuture<Void> - Non-blocking
        storage.save(uuid, profile).thenRun(() -> {
            getLogger().info("Saved profile for " + uuid);
        });
    }

    public void loadProfile(String uuid) {
        // Returns CompletableFuture<PlayerProfile>
        storage.load(uuid, PlayerProfile::new).thenAccept(profile -> {
            if (profile == null) {
                // First time user
                profile = new PlayerProfile("NewUser");
            }
            // Use profile...
        });
    }
}
```

---

## ⚙️ Configuration (`config.yml`)

The API requires minimal configuration.

```yaml
# Enable Debug Logging (Verbose output)
debug: false
```

*Note: The engine selection (RocksDB) has been removed in v2.0. The plugin now exclusively uses the optimized FlatFile engine.*

---

## 🔒 Thread Safety Rules (Folia)
When using this API on **Folia**:

1.  **NEVER** modify `AutoSerializable` objects that are currently potentially being saved on another thread without synchronization (though the API snapshots data as fast as possible).
2.  **Inventory**: Use the internal `InventoryData` type for storage. Convert to live Bukkit `Inventory` only when showing to a player on the main thread.
3.  **Worlds**: The API stores World UIDs. Do NOT call `Bukkit.getWorld(uid)` inside an async chain. Do it on the Region Thread.

---

## 📦 Maven

```xml
<dependency>
    <groupId>me.kirug</groupId>
    <artifactId>flatfilestorage-api</artifactId>
    <version>2.0.0</version>
    <scope>provided</scope>
</dependency>
```
