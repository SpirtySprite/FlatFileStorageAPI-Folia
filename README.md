# FlatFileStorageAPI - Industrial Grade IO

**The robust, thread-safe, and NVMe-optimized storage solution for Folia, Paper, and Spigot.**  
Designed to survive crashes, minimize IO latency, and guarantee data integrity.

---

## � Installation

To use FlatFileStorageAPI, you can either **Shade** it into your plugin (Recommended) or use it as a **Provided** dependency if you install the plugin separately.

### Option 1: JitPack (Recommended for Developers)

Add the repository to your `pom.xml`:
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Add the dependency:
```xml
<dependencies>
    <dependency>
        <groupId>com.github.User</groupId> 
        <artifactId>FlatFileStorageAPI-Folia</artifactId>
        <version>Tag</version> <!-- Replace 'Tag' with the latest release, e.g., '1.0.0' or 'master-SNAPSHOT' -->
        <scope>compile</scope>
    </dependency>
</dependencies>
```

### Option 2: Shading (Compiling it into your custom plugin)
If you want your plugin to work without requiring the user to install `FlatFileStorageAPI.jar` separately, you must **shade** (bundle) it.

**Maven Shade Configuration:**
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.2.4</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <relocations>
                    <relocation>
                        <pattern>me.kirug.flatfilestorage</pattern>
                        <shadedPattern>your.package.storage</shadedPattern> <!-- REPLACE THIS! -->
                    </relocation>
                </relocations>
            </configuration>
        </execution>
    </executions>
</plugin>
```
> **Important:** Always **relocate** the package to avoid conflicts if multiple plugins use different versions of the API!

---

## �️ Usage Guide

### 1. Global Access
You don't need to depend on `Bukkit.getPluginManager()`. We provide a fast static accessor.

**In your `onEnable`:**
```java
// No setup needed if you are shading it! 
// If you are using it as a plugin, ensure 'FlatFileStorageAPI' is loaded before you.
```

**Anywhere in your code:**
```java
StorageAPI storage = FlatFileProvider.get();
```

---

### 2. Creating Data Objects
Your data classes should extend `AbstractSerializable`. This gives you free **Dirty Checking** (optimization) and **Versioning**.

```java
import me.kirug.flatfilestorage.api.AbstractSerializable;
import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;

public class PlayerData extends AbstractSerializable {
    private String username;
    private int coins;
    private List<String> friends;

    // 1. Factory Constructor (Required)
    public PlayerData() {
        this.friends = new ArrayList<>();
    }

    // 2. Define Version (For Migrations)
    @Override
    public int getVersion() {
        return 2;
    }

    // 3. Write Logic
    @Override
    public void write(VarOutputStream out) throws IOException {
        out.writeString(username);
        out.writeVarInt(coins);
        out.writeList(friends, (o, s) -> o.writeString(s)); // Optimized List Write
    }

    // 4. Read Logic (With Migration Support)
    @Override
    public void read(VarInputStream in, int version) throws IOException {
        this.username = in.readString();
        
        if (version >= 1) {
            this.coins = in.readVarInt();
        } else {
            this.coins = 0; // Default for old data
        }

        if (version >= 2) {
             this.friends = in.readList(i -> i.readString());
        }
    }

    // 5. Modifiers
    public void addCoin() {
        this.coins++;
        markDirty(); // VITAL: Tells the API this object changed!
    }
}
```

---

### 3. Loading & Saving (Async)
The API is fully asynchronous.

#### Load
```java
UUID playerId = player.getUniqueId();
// "PlayerData::new" is the factory specific to your class
FlatFileProvider.get().load(playerId.toString(), PlayerData::new).thenAccept(data -> {
    if (data == null) {
        data = new PlayerData(); // New player
    }
    
    // Do stuff...
    System.out.println("Loaded coins: " + data.getCoins());
});
```

#### Save
```java
// Automatically coalesces updates. 
// If you call this 50 times in 1 second, it only saves once!
FlatFileProvider.get().save(playerId.toString(), data);
```

---

### 4. Advanced Features

#### 🛡️ Crash Protection
The API guarantees **Atomic Writes**.
1.  Writes to `.tmp` file.
2.  Forces **Hardware Flush** (`fsync`) preventing kernel buffer loss on power failure.
3.  Moves `.tmp` -> `.var` atomically.
4.  Rotates old `.var` -> `.var.bak`.

If a file is corrupted (CRC32 mismatch), `load()` will **automatically recover** from `.bak`.

#### ⚡ Performance
-   **Local Interning**: Strings in your file are de-duplicated in memory during load.
-   **Striped Locking**: 128 concurrent locks mean `PlayerA` saving never lags `PlayerB` loading.
-   **Zero Reflection**: Uses `Supplier` factories for max speed.

---

## ❓ FAQ

**Q: Can I save ItemStacks?**  
A: Yes! Use `out.writeItemStack(item)` and `in.readItemStack()`. It uses NBT-safe serialization.

**Q: Is it thread-safe?**  
A: The API is 100% thread-safe. Your `PlayerData` object, however, is yours to manage. If you modify it from multiple threads, use `synchronized`.

**Q: What if the disk is full?**  
A: The API checks `getUsableSpace()` before every save. If < 4KB, it aborts immediately to prevent 0-byte file corruption.

**Q: How do I delete data?**  
A: `FlatFileProvider.get().delete("id");` (Deletes `.var`, `.tmp`, and clears cache).

---

**Build with Maven:**
```bash
mvn clean package
```
