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

public class UserStats implements SerializableObject {
    
    private String username;
    private int kills;
    private BigInteger balance;
    private List<String> friends;
    private Map<String, Location> homes;
    private Inventory enderChest;

    public UserStats() {}

    public UserStats(String username, int kills) {
        this.username = username;
        this.kills = kills;
        this.balance = new BigInteger("100000000000000000000"); // Huge number
        this.friends = new ArrayList<>();
        this.friends.add("Notch");
        this.friends.add("Jeb_");
        
        this.homes = new HashMap<>();
        this.homes.put("base", new Location(Bukkit.getWorlds().get(0), 100, 64, 100)); // Assuming world exists
        
        this.enderChest = Bukkit.createInventory(null, 9, "EnderChest");
        this.enderChest.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public void write(VarOutputStream out) throws IOException {
        out.writeString(username);
        out.writeVarInt(kills);
        out.writeBigInteger(balance);
        
        // Write List using Lambda
        out.writeList(friends, (o, s) -> o.writeString(s));
        
        // Write Map using Lambdas
        out.writeMap(homes, (o, k) -> o.writeString(k), (o, v) -> o.writeLocation(v));
        
        out.writeInventory(enderChest);
    }

    @Override
    public void read(VarInputStream in, int version) throws IOException {
        // Version checking allows us to change fields later
        if (version >= 1) {
            this.username = in.readString();
            this.kills = in.readVarInt();
            this.balance = in.readBigInteger();
            this.friends = in.readList((i) -> i.readString());
            this.homes = in.readMap((i) -> i.readString(), (i) -> i.readLocation());
            this.enderChest = in.readInventory("EnderChest");
        }
    }

    @Override
    public String toString() {
        return "UserStats{name=" + username + 
               ", balance=" + balance + 
               ", friends=" + friends + 
               ", homes=" + homes.keySet() + 
               ", inventorySize=" + (enderChest == null ? 0 : enderChest.getSize()) + "}";
    }
}
