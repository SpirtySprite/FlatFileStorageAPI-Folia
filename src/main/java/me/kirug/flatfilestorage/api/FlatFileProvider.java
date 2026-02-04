package me.kirug.flatfilestorage.api;

/**
 * Global accessor for the Storage API.
 * This effectively acts as the Service Manager entry point.
 */
public class FlatFileProvider {
    
    private static StorageAPI instance = null;

    /**
     * @return The active StorageAPI instance.
     * @throws IllegalStateException if API is not initialized.
     */
    public static StorageAPI get() {
        if (instance == null) {
            throw new IllegalStateException("StorageAPI is not initialized! Ensure the plugin is enabled.");
        }
        return instance;
    }

    /**
     * Internal use only.
     */
    public static void register(StorageAPI api) {
        if (instance != null) {
            throw new IllegalStateException("StorageAPI is already registered.");
        }
        instance = api;
    }

    /**
     * Internal use only.
     */
    public static void unregister() {
        instance = null;
    }
}
