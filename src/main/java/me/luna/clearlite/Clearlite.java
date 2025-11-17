package me.luna.clearlite;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

public class Clearlite extends JavaPlugin {

    private ClearTask task;

    @Override
    public void onEnable() {
        // Create config if missing
        saveDefaultConfig();
        // Ensure config picks up new defaults and handles versioned upgrades (semver-aware)
        updateConfigAuto();

        // Task + interval wiring (lets /cl status show "next run")
        task = new ClearTask(this);
        int interval = getConfig().getInt("tps-check-interval-ticks", 200);
        task.setIntervalTicks(interval);

        // Listeners
        getServer().getPluginManager().registerEvents(new SpawnTagListener(this), this);

        // Commands (exact name as in plugin.yml: "clearlite")
        MemberCommand memberCmd = new MemberCommand(this);
        ClCommand cl = new ClCommand(this, task, memberCmd);
        Objects.requireNonNull(getCommand("clearlite"), "Command 'clearlite' missing from plugin.yml")
                .setExecutor(cl);
        Objects.requireNonNull(getCommand("clearlite")).setTabCompleter(cl);

        getLogger().info("ClearLite enabled!");

        // Auto-clear on low TPS
        double tpsThreshold = getConfig().getDouble("tps-threshold", 18.0);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            double tps = readTpsSafe();
            if (tps >= 0 && tps < tpsThreshold) {
                int removed = task.runWithCount();
                Bukkit.getServer().broadcastMessage("§a[Server] ate " + removed + " entities.");
            }
        }, 0L, interval);
    }

    @Override
    public void onDisable() {
        getLogger().info("ClearLite disabled!");
    }

    // --------------------------------------------------------------------
    // Config auto-update / versioned upgrade (semver-aware)
    // --------------------------------------------------------------------

    /**
     * Ensure config has new defaults; if default config-version (semver string) is higher,
     * rebuild from defaults and overlay user values. Always sync human 'version' to the jar's config.yml.
     */
    public void updateConfigAuto() {
        try (InputStream in = getResource("config.yml");
             InputStreamReader reader = (in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8))) {

            if (reader == null) {
                getLogger().warning("No default config.yml found in jar.");
                return;
            }

            // Load defaults from the jar
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
            // Read as strings so 5, "5.1", "5.1.2" all work
            String newCfgVer   = String.valueOf(defaults.get("config-version", "1"));
            String newHumanVer = String.valueOf(defaults.get("version", getDescription().getVersion()));

            // Paths
            File dataDir = getDataFolder();
            if (!dataDir.exists()) dataDir.mkdirs();
            File configFile = new File(dataDir, "config.yml");

            // Current disk copy (even if Bukkit has in-memory)
            YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
            String curCfgVer   = String.valueOf(current.get("config-version", "0"));
            String curHumanVer = current.isSet("version") ? String.valueOf(current.get("version")) : null;

            // First run: write defaults fully (preserves comments/order)
            if (!configFile.exists()) {
                saveResource("config.yml", false);
                YamlConfiguration first = YamlConfiguration.loadConfiguration(configFile);
                first.set("config-version", newCfgVer);
                first.set("version", newHumanVer);
                first.save(configFile);
                reloadConfig();
                getLogger().info("Config created from defaults (config-version " + newCfgVer + ", version " + newHumanVer + ").");
                return;
            }

            // Compare semver-ish ("5" < "5.1" < "5.1.1")
            int cmp = compareSemVer(curCfgVer, newCfgVer);

            if (cmp < 0) {
                // Version bump: backup -> write fresh defaults -> overlay user values
                backupConfigFile(configFile, curCfgVer);
                saveResource("config.yml", true); // overwrite with new defaults (keeps comments)

                YamlConfiguration fresh = YamlConfiguration.loadConfiguration(configFile);

                // Overlay user values (user wins on same paths)
                deepMergeInto(fresh, current);

                // Force new versions from jar config
                fresh.set("config-version", newCfgVer);
                fresh.set("version", newHumanVer);

                // (Optional) harmonize old/alt keys you carried (keeps slabbo flags)
                if (!fresh.isSet("skip.slabbo") && current.isSet("skip.slabbo")) {
                    fresh.set("skip.slabbo", current.getBoolean("skip.slabbo", true));
                }
                if (!fresh.isSet("slabbo-safe") && current.isSet("slabbo-safe")) {
                    fresh.set("slabbo-safe", current.getBoolean("slabbo-safe", false));
                }

                fresh.save(configFile);
                reloadConfig();
                getLogger().info("Config upgraded to config-version " + newCfgVer + " (backup created).");
                return;
            }

            // No schema bump: non-destructive add of new keys + always sync human version & config-version
            FileConfiguration live = getConfig();
            live.addDefaults(defaults);
            live.options().copyDefaults(true);

            // Keep human-visible version in sync with jar config every load
            if (!Objects.equals(curHumanVer, newHumanVer)) {
                live.set("version", newHumanVer);
            }
            // Keep config-version string in sync too
            if (!Objects.equals(String.valueOf(live.get("config-version", "0")), newCfgVer)) {
                live.set("config-version", newCfgVer);
            }

            // Harmonize slabbo keys (back-compat)
            if (!live.isSet("skip.slabbo") && current.isSet("skip.slabbo")) {
                live.set("skip.slabbo", current.getBoolean("skip.slabbo", true));
            }
            if (!live.isSet("slabbo-safe") && current.isSet("slabbo-safe")) {
                live.set("slabbo-safe", current.getBoolean("slabbo-safe", false));
            }

            saveConfig();
            getLogger().info("Config checked and updated with any new defaults (config-version " + newCfgVer + ", version " + newHumanVer + ").");

        } catch (Exception ex) {
            getLogger().warning("Failed to auto-update config.yml: " + ex.getMessage());
        }
    }

    /** Deep-merge values from 'from' into 'into'. For leaves, 'from' overwrites 'into' (user values win). */
    private void deepMergeInto(YamlConfiguration into, YamlConfiguration from) {
        for (String path : from.getKeys(true)) {
            if (from.isConfigurationSection(path)) {
                if (into.getConfigurationSection(path) == null) {
                    into.createSection(path);
                }
            } else {
                into.set(path, from.get(path));
            }
        }
    }

    private void backupConfigFile(File configFile, String oldVersion) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File backup = new File(configFile.getParentFile(),
                    "config.backup-v" + oldVersion + "-" + stamp + ".yml");
            java.nio.file.Files.copy(
                    configFile.toPath(),
                    backup.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
            getLogger().info("Backed up config.yml to " + backup.getName());
        } catch (Exception ex) {
            getLogger().warning("Failed to back up config.yml: " + ex.getMessage());
        }
    }

    /** Compare semver-like strings numerically by parts: "5" < "5.1" < "5.1.2". Non-numeric parts fallback to lexicographic. */
    private int compareSemVer(String a, String b) {
        if (Objects.equals(a, b)) return 0;
        String[] aa = String.valueOf(a).split("\\.");
        String[] bb = String.valueOf(b).split("\\.");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < aa.length ? aa[i] : "0";
            String sb = i < bb.length ? bb[i] : "0";
            // try numeric
            try {
                int ia = Integer.parseInt(sa.replaceAll("[^0-9-]", ""));
                int ib = Integer.parseInt(sb.replaceAll("[^0-9-]", ""));
                if (ia != ib) return Integer.compare(ia, ib);
            } catch (Exception ignore) {
                // fallback to string compare if non-numeric (e.g., "5.1-beta")
                int c = sa.compareToIgnoreCase(sb);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    // --------------------------------------------------------------------
    // Utils
    // --------------------------------------------------------------------

    /** Safe TPS read that works on Paper and (best-effort) Spigot. @return 1m TPS or -1 if unavailable */
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