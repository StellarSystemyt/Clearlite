package me.luna.clearlite;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class Clearlite extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("Clearlite").setExecutor(new ClearCommand(this));
        getLogger().info("ClearLite enabled!");

        long interval = getConfig().getLong("tps-check-interval-ticks", 200);
        double tpsThreshold = getConfig().getDouble("tps-threshold", 18.0);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            double tps = Bukkit.getServer().getTPS()[0]; // 1-min TPS
            if (tps < tpsThreshold) {
                int removed = new ClearTask(this).runWithCount();
                Bukkit.getServer().broadcastMessage("§a[ClearLite] Cleared " + removed +
                        " entities due to low TPS (" + String.format("%.2f", tps) + ")");
            }
        }, 0L, interval);
    }

    @Override
    public void onDisable() {
        getLogger().info("ClearLite disabled!");
    }
}
