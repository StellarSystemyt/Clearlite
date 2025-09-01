package me.luna.clearlite;

import com.palmergames.bukkit.towny.TownyAPI;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class ClearTask implements Runnable {

    private final JavaPlugin plugin;

    public ClearTask(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        // scheduled runs do a real clear
        execute(false);
    }

    /** New: supports simulation mode; returns how many entities matched */
    public int execute(boolean simulate) {
        int affected = 0;

        final boolean regionSafe = plugin.getConfig().getBoolean("region-safe", false);
        final boolean townySafe  = plugin.getConfig().getBoolean("towny-safe",  false);

        // Safety/selection lists
        final List<String> clearTypes = plugin.getConfig().getStringList("clear-types"); // preferred
        final List<String> whitelist  = plugin.getConfig().getStringList("whitelist");   // legacy fallback

        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {

                // Decide if this entity is even a target
                if (!isTarget(e, clearTypes, whitelist)) continue;

                // Safety filters
                if (shouldSkip(e)) continue;

                // Region/Towny guards
                if (regionSafe && isInProtectedRegion(e)) continue;
                if (townySafe  && isInTownyTown(e))       continue;

                affected++;
                if (!simulate) e.remove();
            }
        }
        return affected;
    }

    /** Legacy method kept for compatibility with your old command; now just runs a real clear */
    public int runWithCount() {
        return execute(false);
    }

    /** Selection logic: prefer 'clear-types' list; else use legacy whitelist (clear everything NOT in whitelist) */
    private boolean isTarget(Entity e, List<String> clearTypes, List<String> whitelist) {
        if (clearTypes != null && !clearTypes.isEmpty()) {
            // Only these types are eligible
            return clearTypes.contains(e.getType().name());
        }
        // Legacy behavior: everything except those explicitly whitelisted
        return whitelist == null || !whitelist.contains(e.getType().name());
    }

    /** Safety rules to avoid removing important entities */
    private boolean shouldSkip(Entity e) {
        if (e instanceof Player) return true;

        var skipSec = plugin.getConfig().getConfigurationSection("skip");
        boolean skipNamed      = skipSec == null || skipSec.getBoolean("named", true);
        boolean skipTamed      = skipSec == null || skipSec.getBoolean("tamed", true);
        boolean skipVillagers  = skipSec == null || skipSec.getBoolean("villagers", true);
        boolean skipArmorStand = skipSec == null || skipSec.getBoolean("armor-stands", true);
        boolean skipItemFrame  = skipSec == null || skipSec.getBoolean("item-frames", true);
        boolean skipPainting   = skipSec == null || skipSec.getBoolean("paintings", true);
        boolean skipVehicles   = skipSec == null || skipSec.getBoolean("vehicles", true);
        boolean skipCitizens   = skipSec == null || skipSec.getBoolean("citizens-npc", true);

        if (skipNamed && e.getCustomName() != null && !e.getCustomName().isEmpty()) return true;
        if (skipTamed && e instanceof Tameable t && t.isTamed()) return true;
        if (skipVillagers && e instanceof Villager) return true;
        if (skipArmorStand && e instanceof ArmorStand) return true;
        if (skipItemFrame && e instanceof ItemFrame) return true;
        if (skipPainting && e instanceof Painting) return true;

// 1.21+: minecart subclasses were flattened — just check Minecart or Boat
        if (skipVehicles && (e instanceof Boat || e instanceof Minecart)) return true;

/* OPTIONAL: if you want finer control per minecart type, use this instead of the line above:
if (skipVehicles && e instanceof Minecart cart) {
    switch (cart.getMinecartType()) {
        case CHEST, HOPPER, FURNACE, TNT, COMMAND, SPAWNER -> { return true; }
        default -> { /* do nothing *-/ }
    }
}
if (skipVehicles && e instanceof Boat) return true;
*/

        if (skipCitizens && e.hasMetadata("NPC")) return true; // Citizens marks NPCs with this

        return false;
    }

    /** WorldGuard 7.x region check via RegionQuery (needs WorldEdit on classpath) */
    private boolean isInProtectedRegion(Entity entity) {
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return !query.getApplicableRegions(BukkitAdapter.adapt(entity.getLocation()))
                    .getRegions()
                    .isEmpty();
        } catch (Throwable t) {
            return false; // fail open if WG/WE missing or mismatched
        }
    }

    /** Towny presence check */
    private boolean isInTownyTown(Entity entity) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("Towny")) return false;
            return TownyAPI.getInstance().isTownyWorld(entity.getWorld())
                    && TownyAPI.getInstance().getTownBlock(entity.getLocation()) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}