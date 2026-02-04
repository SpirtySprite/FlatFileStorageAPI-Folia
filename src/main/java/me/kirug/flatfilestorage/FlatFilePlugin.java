package me.kirug.flatfilestorage;

import me.kirug.flatfilestorage.api.StorageAPI;
import me.kirug.flatfilestorage.command.TestCommand;
import me.kirug.flatfilestorage.impl.FlatFileStorage;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class FlatFilePlugin extends JavaPlugin {

    private StorageAPI storageAPI;

    @Override
    public void onEnable() {
        // Init Storage in plugin folder/data
        File dataDir = new File(getDataFolder(), "data");
        this.storageAPI = new FlatFileStorage(dataDir.toPath(), getLogger());
        
        // Register Command
        getCommand("storage").setExecutor(new TestCommand(this, storageAPI));
        
        // Register Provider
        me.kirug.flatfilestorage.api.FlatFileProvider.register(storageAPI);
        
        getLogger().info("FlatFileStorageAPI (Industrial-Grade) enabled!");
    }

    @Override
    public void onDisable() {
        if (storageAPI != null) {
            getLogger().info("Flushing storage queues...");
            // Trigger the internal flush and executor shutdown
            storageAPI.shutdown();
            
            // Clean up the provider to prevent memory leaks or stale references
            me.kirug.flatfilestorage.api.FlatFileProvider.unregister();
        }
        getLogger().info("FlatFileStorageAPI disabled.");
    }
    
    public StorageAPI getStorageAPI() {
        return storageAPI;
    }
}
