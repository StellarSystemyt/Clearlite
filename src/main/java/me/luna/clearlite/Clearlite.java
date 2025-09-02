package me.luna.clearlite;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class Clearlite extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // tasks
        ClearTask task = new ClearTask(this);
        // schedule as you already do…

        // listeners
        getServer().getPluginManager().registerEvents(new SpawnTagListener(this), this);
    
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

    /**
     * Merge any new keys from the jar's config.yml into the server's config.yml
     * without overwriting existing user-provided values.
     */
    public void mergeDefaultsIntoConfig() {
        try (var in = getResource("config.yml")) {
            if (in == null) {
                getLogger().warning("No default config.yml found in jar.");
                return;
            }
            var defaults = org.bukkit.configuration.file.YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));

            var cfg = getConfig();
            cfg.addDefaults(defaults);
            cfg.options().copyDefaults(true);
            saveConfig(); // writes any missing keys to disk
        } catch (Exception ex) {
            getLogger().warning("Failed merging defaults into config.yml: " + ex.getMessage());
        }
    }

    /**
     * Safe TPS read that works on Paper and (best-effort) Spigot.
     * @return 1m TPS or -1 if unavailable
     */
    private double readTpsSafe() {
        try {
            double[] tps = Bukkit.getServer().getTPS(); // Paper
            if (tps != null && tps.length > 0) return tps[0];
        } catch (Throwable ignored) {
            try {
                double[] tps = (double[]) Bukkit.getServer().getClass()
                        .getMethod("getTPS")
                        .invoke(Bukkit.getServer());
                if (tps != null && tps.length > 0) return tps[0];
            } catch (Throwable ignored2) {}
        }
        return -1.0;
    }
}
