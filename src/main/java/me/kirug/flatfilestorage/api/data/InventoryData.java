package me.kirug.flatfilestorage.api.data;

import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import java.util.Map;

/**
 * A safe representation of an Inventory.
 * Does not hold the Bukkit Inventory object.
 */
public record InventoryData(Component title, int size, Map<Integer, ItemStack> contents) {
    
    /**
     * Applies this data to a real Inventory on the main thread.
     */
    public void apply(org.bukkit.inventory.Inventory inventory) {
        inventory.clear();
        contents.forEach(inventory::setItem);
    }
}
