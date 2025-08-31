package me.luna.clearlite;

import com.palmergames.bukkit.towny.TownyAPI;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ClearTask implements Runnable {

    private final JavaPlugin plugin;

    public ClearTask(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        runWithCount();
    }

    public int runWithCount() {
        int removed = 0;
        List<String> whitelist = plugin.getConfig().getStringList("whitelist");
        boolean regionSafe = plugin.getConfig().getBoolean("region-safe", false);
        boolean townySafe = plugin.getConfig().getBoolean("towny-safe", false);

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (whitelist.contains(entity.getType().name())) continue;

                if (regionSafe && isInProtectedRegion(entity)) continue;
                if (townySafe && isInTownyTown(entity)) continue;

                entity.remove();
                removed++;
            }
        }

        return removed;
    }

    private boolean isInProtectedRegion(Entity entity) {
        try {
            WorldGuardPlugin wgPlugin = WorldGuardPlugin.inst();
            if (wgPlugin == null) return false;

            return !wgPlugin.getRegionManager(entity.getWorld())
                    .getApplicableRegions(entity.getLocation())
                    .getRegions().isEmpty();
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean isInTownyTown(Entity entity) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("Towny")) return false;
            return TownyAPI.getInstance().isTownyWorld(entity.getWorld())
                    && TownyAPI.getInstance().getTownBlock(entity.getLocation()) != null;
        } catch (Throwable e) {
            return false;
        }
    }
}
