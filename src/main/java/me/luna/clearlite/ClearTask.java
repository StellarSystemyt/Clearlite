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

/**
 * Clearlite - ClearTask (Snapshot 3 + per-member)
 *
 * Adds:
 * - Per-member skip rules for items/projectiles (+ per-member grace override)
 * - Per-world enable + item grace
 * - Material deny/allow
 * - Projectile skipping (coarse + per-type)
 * - Valuable-only region/town safety
 * - Simulate breakdown & scoped execution
 */
public class ClearTask implements Runnable {

    public static final String META_OWNER = "clearlite_owner"; // UUID string
    private final JavaPlugin plugin;

    // Last simulate/run stats
    private int lastAffected = 0;
    private long lastRunMs = 0L;
    private final EnumMap<EntityType, Integer> lastBreakdown = new EnumMap<>(EntityType.class);

    public ClearTask(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        execute(false, null, null, -1.0);
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

    private boolean sameWorld(World a, World b) {
        return a != null && b != null && Objects.equals(a.getUID(), b.getUID());
    }

    // ---------- per-member support ----------

    private boolean isProtectedByMemberRules(Entity e, PerMemberRules rules) {
        // DROPPED_ITEM: consult tagged owner (metadata) or Paper owner
        if (e instanceof Item it) {
            Optional<UUID> owner = getEntityOwnerUUID(it);
            if (owner.isPresent()) {
                PerMemberRules.Member m = rules.get(owner.get());
                if (m != null && m.skipItems) return true;
            }
            return false;
        }

        // Projectiles: shooter may be a player
        if (e instanceof Projectile proj) {
            UUID shooter = getProjectilePlayerUUID(proj).orElse(null);
            if (shooter != null) {
                PerMemberRules.Member m = rules.get(shooter);
                if (m != null && m.skipProjectiles) return true;
            }
            return false;
        }

        return false;
    }

    private Optional<UUID> getEntityOwnerUUID(Item it) {
        // Prefer our metadata tag (set by listener)
        if (it.hasMetadata(META_OWNER)) {
            try {
                String raw = it.getMetadata(META_OWNER).get(0).asString();
                return Optional.of(UUID.fromString(raw));
            } catch (Exception ignored) {}
        }
        // Fallback: Spigot/Paper Item#getOwner (if used by server mechanics)
        try {
            UUID owner = it.getOwner(); // may be null
            if (owner != null) return Optional.of(owner);
        } catch (Throwable ignored) {}
        return Optional.empty();
    }

    private Optional<UUID> getProjectilePlayerUUID(Projectile proj) {
        try {
            Object shooter = proj.getShooter();
            if (shooter instanceof Player p) return Optional.of(p.getUniqueId());
        } catch (Throwable ignored) {}
        // Also allow metadata tag from listener (for odd shooters)
        if (proj.hasMetadata(META_OWNER)) {
            try {
                String raw = proj.getMetadata(META_OWNER).get(0).asString();
                return Optional.of(UUID.fromString(raw));
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    // ---------- stats accessors ----------

    public int getLastAffected() { return lastAffected; }
    public long getLastRunTimestampMs() { return lastRunMs; }
    public Map<EntityType, Integer> getLastBreakdownSnapshot() {
        synchronized (lastBreakdown) {
            return Collections.unmodifiableMap(new EnumMap<>(lastBreakdown));
        }
    }

    // ---------- utility to tag (optional programmatic tag) ----------

    public void tagOwner(Entity entity, UUID uuid) {
        if (uuid == null || entity == null) return;
        entity.setMetadata(META_OWNER, new FixedMetadataValue(plugin, uuid.toString()));
    }

    // ---------- per-member rules loader ----------

    static class PerMemberRules {
        static class Member {
            final boolean skipItems;
            final boolean skipProjectiles;
            final Integer graceOverrideTicks; // nullable

            Member(boolean skipItems, boolean skipProjectiles, Integer graceOverrideTicks) {
                this.skipItems = skipItems;
                this.skipProjectiles = skipProjectiles;
                this.graceOverrideTicks = graceOverrideTicks;
            }
        }

        private final Map<UUID, Member> byUuid = new HashMap<>();

        PerMemberRules(JavaPlugin plugin) {
            ConfigurationSection root = plugin.getConfig().getConfigurationSection("members");
            if (root == null) return;

            for (String key : root.getKeys(false)) {
                ConfigurationSection msec = root.getConfigurationSection(key);
                if (msec == null) continue;

                UUID uuid = parseUuidOrLookup(key);
                if (uuid == null) continue;

                boolean skipItems = msec.getBoolean("skip.items", false);
                boolean skipProj  = msec.getBoolean("skip.projectiles", false);
                Integer grace     = msec.contains("item_grace_ticks") ? msec.getInt("item_grace_ticks") : null;

                byUuid.put(uuid, new Member(skipItems, skipProj, grace));
            }
        }

        Member get(UUID uuid) {
            return byUuid.get(uuid);
        }

        Optional<Integer> getMemberGraceFor(Item it) {
            Optional<UUID> o = Optional.empty();
            try {
                if (it.hasMetadata(ClearTask.META_OWNER)) {
                    String raw = it.getMetadata(ClearTask.META_OWNER).get(0).asString();
                    o = Optional.of(UUID.fromString(raw));
                } else {
                    UUID owner = it.getOwner();
                    if (owner != null) o = Optional.of(owner);
                }
            } catch (Throwable ignored) {}
            if (o.isEmpty()) return Optional.empty();
            Member m = get(o.get());
            return (m != null && m.graceOverrideTicks != null) ? Optional.of(m.graceOverrideTicks) : Optional.empty();
        }

        private UUID parseUuidOrLookup(String key) {
            try {
                return UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                // Try to resolve current online player name -> UUID
                Player p = Bukkit.getPlayerExact(key);
                return (p != null) ? p.getUniqueId() : null;
            }
        }
    }
}