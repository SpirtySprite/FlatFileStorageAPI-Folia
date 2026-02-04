package me.kirug.flatfilestorage.command;

import me.kirug.flatfilestorage.FlatFilePlugin;
import me.kirug.flatfilestorage.api.StorageAPI;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.concurrent.ThreadLocalRandom;

public class TestCommand implements CommandExecutor {

    private final FlatFilePlugin plugin;
    private final StorageAPI storage;

    public TestCommand(FlatFilePlugin plugin, StorageAPI storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;

        String sub = args[0];
        
        if (sub.equalsIgnoreCase("save")) {
            String key = args.length > 1 ? args[1] : "user_1";
            UserStats stats = new UserStats("Player_" + key, 
                    ThreadLocalRandom.current().nextInt(1000));
            
            long start = System.nanoTime();
            storage.save(key, stats).thenRun(() -> {
               sender.sendMessage("Saved " + key + " in " + (System.nanoTime() - start) + "ns (Async completion time)"); 
            });
            sender.sendMessage("Save request sent.");
        }
        
        if (sub.equalsIgnoreCase("load")) {
            String key = args.length > 1 ? args[1] : "user_1";
            long start = System.nanoTime();
            storage.load(key, UserStats::new).thenAccept(stats -> {
                if (stats == null) {
                    sender.sendMessage("Not found.");
                } else {
                    sender.sendMessage("Loaded: " + stats.toString() + " in " + (System.nanoTime() - start) + "ns");
                }
            });
        }
        
        if (sub.equalsIgnoreCase("benchmark")) {
            sender.sendMessage("Starting benchmark of 10,000 saves...");
            long start = System.currentTimeMillis();
            for (int i = 0; i < 10000; i++) {
                storage.save("bench_" + i, new UserStats("User" + i, i));
            }
            sender.sendMessage("Queued 10k saves in " + (System.currentTimeMillis() - start) + "ms (Main Thread Latency)");
        }

        return true;
    }
}
