package me.kirug.flatfilestorage.command;

import me.kirug.flatfilestorage.api.SerializableObject;
import me.kirug.flatfilestorage.io.VarInputStream;
import me.kirug.flatfilestorage.io.VarOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserStats extends me.kirug.flatfilestorage.api.AutoSerializable {
    
    @me.kirug.flatfilestorage.api.Serialize(order = 1)
    private String username;
    
    @me.kirug.flatfilestorage.api.Serialize(order = 2)
    private int kills;
    
    @me.kirug.flatfilestorage.api.Serialize(order = 3)
    private BigInteger balance;
    
    @me.kirug.flatfilestorage.api.Serialize(order = 4)
    private List<String> friends;
    
    @me.kirug.flatfilestorage.api.Serialize(order = 5)
    private Map<String, Location> homes;
    
    @me.kirug.flatfilestorage.api.Serialize(order = 6)
    private me.kirug.flatfilestorage.api.data.InventoryData enderChest; // Safe data, not live Inventory

    public UserStats() {}

    public UserStats(String username, int kills) {
        this.username = username;
        this.kills = kills;
        this.balance = new BigInteger("100000000000000000000"); // Huge number
        this.friends = new ArrayList<>();
        this.friends.add("Notch");
        this.friends.add("Jeb_");
        
        this.homes = new HashMap<>();
        this.homes.put("base", new Location(Bukkit.getWorlds().get(0), 100, 64, 100));
        
        // Convert initial inventory - ideally we use real inventory only when needed
        Inventory inv = Bukkit.createInventory(null, 9, net.kyori.adventure.text.Component.text("EnderChest"));
        inv.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        
        // Convert to data
        Map<Integer, ItemStack> contents = new HashMap<>();
        for (int i=0; i<inv.getSize(); i++) {
            if (inv.getItem(i) != null) contents.put(i, inv.getItem(i));
        }
        this.enderChest = new me.kirug.flatfilestorage.api.data.InventoryData(
            net.kyori.adventure.text.Component.text("EnderChest"), 9, contents
        );
    }
    
    // No Read/Write methods! Auto-magically handled.

    @Override
    public String toString() {
        return "UserStats{name=" + username + 
               ", balance=" + balance + 
               ", friends=" + friends + 
               ", homes=" + homes.keySet() + 
               ", inventorySize=" + (enderChest == null ? 0 : enderChest.size()) + "}";
    }
}
