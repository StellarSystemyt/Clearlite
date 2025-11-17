package me.luna.clearlite;

import com.palmergames.bukkit.towny.TownyAPI;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Predicate;

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
 * - Status helpers for next run ETA
 * - debugItems() & clearItemsTargeted() utilities
 * - (NEW) Slabbo/shop hard-guards
 */
public class ClearTask implements Runnable {

    public static final String META_OWNER = "clearlite_owner"; // UUID string
    private final JavaPlugin plugin;

    // Last simulate/run stats
    private int lastAffected = 0;
    private long lastRunMs = 0L;
    private final EnumMap<EntityType, Integer> lastBreakdown = new EnumMap<>(EntityType.class);

    // scheduling visibility for /cl status
    private int intervalTicks = 200; // default; main plugin should overwrite

    public ClearTask(JavaPlugin plugin) { this.plugin = plugin; }

    @Override public void run() { execute(false, null, null, -1.0); }
    public int execute(boolean simulate) { return execute(simulate, null, null, -1.0); }

    public int execute(boolean simulate, World scopeWorld, Location center, double radius) {
        final boolean regionSafe = plugin.getConfig().getBoolean("region-safe", false);
        final boolean townySafe  = plugin.getConfig().getBoolean("towny-safe",  false);

        final List<String> clearTypes = plugin.getConfig().getStringList("clear-types");
        final List<String> whitelist  = plugin.getConfig().getStringList("whitelist");
        final boolean legacyMode      = (clearTypes == null || clearTypes.isEmpty());

        final Predicate<Entity> skipPredicate = buildSkipPredicate();
        final Predicate<Entity> projectileSkip = this::shouldSkipProjectile;

        // materials
        final Set<String> denyMaterials = new HashSet<>(plugin.getConfig().getStringList("materials.denylist"));
        final List<String> allowRaw     = plugin.getConfig().getStringList("materials.allowlist");
        final Set<String> allowMaterials = (allowRaw == null) ? Collections.emptySet() : new HashSet<>(allowRaw);
        final boolean useAllowList = !allowMaterials.isEmpty();

        // per-member rules map (UUID -> rules)
        final PerMemberRules perMember = new PerMemberRules(plugin);

        double radiusSq = radius > 0 ? radius * radius : -1.0;

        int affected = 0;
        EnumMap<EntityType, Integer> breakdown = new EnumMap<>(EntityType.class);

        Iterable<World> worlds = (scopeWorld != null) ? List.of(scopeWorld) : Bukkit.getWorlds();

        for (World world : worlds) {
            if (!isWorldEnabled(world)) continue;

            for (Entity e : world.getEntities()) {
                // radius
                if (radiusSq > 0) {
                    Location c = (center != null ? center : world.getSpawnLocation());
                    if (!sameWorld(e.getWorld(), c.getWorld())) continue;
                    if (e.getLocation().distanceSquared(c) > radiusSq) continue;
                }

                // --- HARD GUARD: Slabbo/shop entities are always skipped (before any target selection) ---
                ConfigurationSection skipSec = plugin.getConfig().getConfigurationSection("skip");
                boolean slabboSafe = (skipSec != null && skipSec.getBoolean("slabbo", true))
                        || plugin.getConfig().getBoolean("slabbo-safe", true);
                if (slabboSafe) {
                    if (isSlabboEntity(e)) continue;                   // PDC flags, namespaces, keys
                    if (isShopDisplayEntity(e)) continue;              // Item/Block/Text/Interaction displays
                    if (e instanceof ArmorStand as && looksLikeShopStand(as)) continue; // hologram stands
                }

                // target selection
                if (!legacyMode) {
                    if (clearTypes == null || !clearTypes.contains(e.getType().name())) continue;
                } else {
                    if (whitelist != null && whitelist.contains(e.getType().name())) continue;
                }

                // general skips
                if (skipPredicate.test(e)) continue;

                // per-member protection (items/projectiles)
                if (isProtectedByMemberRules(e, perMember)) continue;

                // projectile coarse/fine skip
                if (projectileSkip.test(e)) continue;

                // dropped item-specific rules
                if (e instanceof Item it) {
                    if (isDeniedMaterial(it, denyMaterials, allowMaterials, useAllowList)) continue;

                    // grace: per-member override > per-world > global
                    int grace = perMember.getMemberGraceFor(it).orElse(getItemGraceTicks(world));
                    if (grace > 0 && it.getTicksLived() < grace) continue;
                }

                // region/towny on valuable/placed only
                if (regionSafe && requiresRegionProtection(e) && isInProtectedRegion(e)) continue;
                if (townySafe  && requiresRegionProtection(e) && isInTownyTown(e))       continue;

                // remove/count
                affected++;
                breakdown.merge(e.getType(), 1, Integer::sum);
                if (!simulate) e.remove();
            }
        }

        lastAffected = affected;
        lastRunMs = System.currentTimeMillis();
        synchronized (lastBreakdown) {
            lastBreakdown.clear();
            lastBreakdown.putAll(breakdown);
        }
        return affected;
    }

    public int runWithCount() { return execute(false); }

    // ---------- predicates & helpers ----------

    private Predicate<Entity> buildSkipPredicate() {
        return e -> {
            if (e instanceof Player) return true;
            ConfigurationSection skipSec = plugin.getConfig().getConfigurationSection("skip");
            boolean skipNamed      = skipSec == null || skipSec.getBoolean("named", true);
            boolean skipTamed      = skipSec == null || skipSec.getBoolean("tamed", true);
            boolean skipVillagers  = skipSec == null || skipSec.getBoolean("villagers", true);
            boolean skipArmorStand = skipSec == null || skipSec.getBoolean("armor-stands", true);
            boolean skipItemFrame  = skipSec == null || skipSec.getBoolean("item-frames", true);
            boolean skipPainting   = skipSec == null || skipSec.getBoolean("paintings", true);
            boolean skipVehicles   = skipSec == null || skipSec.getBoolean("vehicles", true);
            boolean skipCitizens   = skipSec == null || skipSec.getBoolean("citizens-npc", true);

            // Slabbo safety: prefer skip.slabbo, fallback to root slabbo-safe
            boolean slabboSafe = (skipSec != null && skipSec.getBoolean("slabbo", true))
                    || plugin.getConfig().getBoolean("slabbo-safe", true);

            // don't treat dropped items as "named"
            if (skipNamed && !(e instanceof Item) && e.getCustomName() != null && !e.getCustomName().isEmpty()) return true;

            if (skipTamed && e instanceof Tameable t && t.isTamed()) return true;
            if (skipVillagers && e instanceof Villager) return true;
            if (skipArmorStand && e instanceof ArmorStand) return true;
            if (skipItemFrame && e instanceof ItemFrame) return true;
            if (skipPainting && e instanceof Painting) return true;

            if (skipVehicles && (e instanceof Boat || e instanceof Minecart)) return true;

            if (skipCitizens && e.hasMetadata("NPC")) return true;

            // secondary Slabbo guard (kept as extra safety)
            if (slabboSafe) {
                if (isSlabboEntity(e)) return true;
                if (isShopDisplayEntity(e)) return true;
                if (e instanceof ArmorStand as && looksLikeShopStand(as)) return true;
            }

            return false;
        };
    }

    // Display & Interaction entities often used by shop plugins (API-safe string check)
    private boolean isShopDisplayEntity(Entity e) {
        String t = e.getType().name();
        return t.equals("ITEM_DISPLAY") || t.equals("BLOCK_DISPLAY")
                || t.equals("TEXT_DISPLAY") || t.equals("INTERACTION");
    }

    // Slabbo detection via metadata, scoreboard tags, and PDC keys (from /data)
    private boolean isSlabboEntity(Entity e) {
        try {
            if (e.hasMetadata("slabbo") || e.hasMetadata("Slabbo")) return true;
        } catch (Throwable ignored) {}

        try {
            for (String tag : e.getScoreboardTags()) {
                String t = tag.toLowerCase(java.util.Locale.ROOT);
                if (t.contains("slabbo")) return true;
            }
        } catch (Throwable ignored) {}

        try {
            var pdc = e.getPersistentDataContainer();
            if (pdc != null && !pdc.getKeys().isEmpty()) {
                for (var key : pdc.getKeys()) {
                    String ns = key.getNamespace().toLowerCase(java.util.Locale.ROOT);
                    String k  = key.getKey().toLowerCase(java.util.Locale.ROOT);
                    if (ns.contains("slabbo") || k.contains("slabbo")) return true;
                    if (k.equals("slabbonomerge") || k.equals("slabbonopickup") || k.equals("slabboshoplocation")) return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    // Heuristics for hologram/marker ArmorStands commonly used by shops
    private boolean looksLikeShopStand(ArmorStand as) {
        if (as.isMarker() && as.isInvisible() && !as.hasBasePlate() && !as.hasArms()) return true;

        if (as.isInvisible() && as.getCustomName() != null && !as.getCustomName().isEmpty()) {
            try {
                if (as.getEquipment() != null) {
                    for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
                        var item = as.getEquipment().getItem(slot);
                        if (item != null && !item.getType().isAir()) {
                            return false;
                        }
                    }
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean shouldSkipProjectile(Entity e) {
        ConfigurationSection skipSec = plugin.getConfig().getConfigurationSection("skip");
        if (skipSec == null) return false;
        boolean skipAll = skipSec.getBoolean("projectiles", false);
        ConfigurationSection overrides = skipSec.getConfigurationSection("projectile_overrides");
        if (overrides != null && overrides.contains(e.getType().name())) {
            return overrides.getBoolean(e.getType().name(), skipAll);
        }
        return skipAll && (e instanceof Projectile);
    }

    private boolean requiresRegionProtection(Entity e) {
        return (e instanceof ArmorStand)
                || (e instanceof ItemFrame)
                || (e instanceof Painting)
                || (e instanceof Villager)
                || (e instanceof Tameable)
                || (e instanceof Boat)
                || (e instanceof Minecart);
    }

    private boolean isDeniedMaterial(Item item, Set<String> deny, Set<String> allow, boolean useAllow) {
        String name = item.getItemStack().getType().name();
        if (deny != null && deny.contains(name)) return true;
        if (useAllow && (allow == null || !allow.contains(name))) return true;
        return false;
    }

    private boolean isWorldEnabled(World w) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("worlds." + w.getName());
        if (sec == null) return true;
        return sec.getBoolean("enabled", true);
    }

    private int getItemGraceTicks(World w) {
        int globalGrace = plugin.getConfig().getInt("item_grace_ticks", 200);
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("worlds." + w.getName());
        return (sec != null) ? sec.getInt("item_grace_ticks", globalGrace) : globalGrace;
    }

    private boolean isInProtectedRegion(Entity entity) {
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            return !query.getApplicableRegions(BukkitAdapter.adapt(entity.getLocation()))
                    .getRegions()
                    .isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

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
        if (e instanceof Item it) {
            Optional<UUID> owner = getEntityOwnerUUID(it);
            if (owner.isPresent()) {
                PerMemberRules.Member m = rules.get(owner.get());
                if (m != null && m.skipItems) return true;
            }
            return false;
        }

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
        if (it.hasMetadata(META_OWNER)) {
            try {
                String raw = it.getMetadata(META_OWNER).get(0).asString();
                return Optional.of(UUID.fromString(raw));
            } catch (Exception ignored) {}
        }
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
        if (proj.hasMetadata(META_OWNER)) {
            try {
                String raw = proj.getMetadata(META_OWNER).get(0).asString();
                return Optional.of(UUID.fromString(raw));
            } catch (Exception ignored) {}
        }
        return Optional.empty();
    }

    // ---------- stats & scheduling accessors ----------

    public int getLastAffected() { return lastAffected; }
    public long getLastRunTimestampMs() { return lastRunMs; }
    public Map<EntityType, Integer> getLastBreakdownSnapshot() {
        synchronized (lastBreakdown) {
            return Collections.unmodifiableMap(new EnumMap<>(lastBreakdown));
        }
    }
    public void setIntervalTicks(int ticks) { this.intervalTicks = Math.max(1, ticks); }
    public long getNextRunEtaMs() {
        if (lastRunMs <= 0L || intervalTicks <= 0) return -1L;
        long intervalMs = intervalTicks * 50L;
        long nextAt = lastRunMs + intervalMs;
        long eta = nextAt - System.currentTimeMillis();
        return Math.max(0L, eta);
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

        Member get(UUID uuid) { return byUuid.get(uuid); }

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
                Player p = Bukkit.getPlayerExact(key);
                return (p != null) ? p.getUniqueId() : null;
            }
        }
    }

    // ---------- diagnostics & targeted item-clear ----------

    /** Inspect dropped items and explain why they would / wouldn't be removed. */
    public List<String> debugItems(World world, int maxLines) {
        List<String> out = new ArrayList<>();
        if (world == null) world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (world == null) { out.add("No worlds loaded."); return out; }

        final List<String> clearTypes = plugin.getConfig().getStringList("clear-types");
        final boolean legacyMode = (clearTypes == null || clearTypes.isEmpty());

        Set<String> deny = new HashSet<>(plugin.getConfig().getStringList("materials.denylist"));
        List<String> allowRaw = plugin.getConfig().getStringList("materials.allowlist");
        Set<String> allow = (allowRaw == null) ? Set.of() : new HashSet<>(allowRaw);
        boolean useAllow = !allow.isEmpty();

        int shown = 0;
        for (Item it : world.getEntitiesByClass(Item.class)) {
            if (shown >= maxLines) break;

            String mat = it.getItemStack().getType().name();

            // Slabbo? always protected
            if (isSlabboEntity(it)) {
                out.add("Item " + mat + " protected (Slabbo entity)");
                shown++; continue;
            }

            // 1) target list?
            if (!legacyMode && (clearTypes == null || !clearTypes.contains(EntityType.ITEM.name()))) {
                out.add("Blocked: ITEM not in clear-types");
                break;
            }

            // 2) per-member + world/global grace
            Integer memberGrace = new PerMemberRules(plugin).getMemberGraceFor(it).orElse(null);
            int worldGrace = getItemGraceTicks(world);
            int effGrace = (memberGrace != null ? memberGrace : worldGrace);
            if (effGrace > 0 && it.getTicksLived() < effGrace) {
                out.add("Item " + mat + " age " + it.getTicksLived() + " < grace " + effGrace);
                shown++; continue;
            }

            // 3) materials filters
            if (isDeniedMaterial(it, deny, allow, useAllow)) {
                out.add("Item " + mat + " blocked by materials (deny/allow)");
                shown++; continue;
            }

            // 4) would remove
            out.add("Item " + mat + " ✅ would be removed");
            shown++;
        }

        if (out.isEmpty()) out.add("No dropped items found to evaluate.");
        return out;
    }

    /** Clear only dropped items with fine-grained filters. */
    public int clearItemsTargeted(boolean simulate,
                                  World world,
                                  Location center,
                                  double radius,
                                  String materialName,
                                  int minAgeTicks,
                                  UUID ownerFilter,
                                  boolean ignoreSkips) {
        double r2 = (radius >= 0) ? radius * radius : -1;
        int removed = 0;

        List<World> worlds = (world != null) ? List.of(world) : Bukkit.getWorlds();
        for (World w : worlds) {
            if (!isWorldEnabled(w)) continue;

            // prepare rule sets only if we honor skips
            Set<String> deny = ignoreSkips ? Set.of() : new HashSet<>(plugin.getConfig().getStringList("materials.denylist"));
            List<String> allowRaw = ignoreSkips ? List.of() : plugin.getConfig().getStringList("materials.allowlist");
            Set<String> allow = (allowRaw == null) ? Set.of() : new HashSet<>(allowRaw);
            boolean useAllow = !ignoreSkips && !allow.isEmpty();
            PerMemberRules pmr = ignoreSkips ? null : new PerMemberRules(plugin);

            for (Item it : w.getEntitiesByClass(Item.class)) {
                if (r2 >= 0) {
                    Location c = (center != null ? center : w.getSpawnLocation());
                    if (!sameWorld(w, c.getWorld())) continue;
                    if (it.getLocation().distanceSquared(c) > r2) continue;
                }
                if (materialName != null && !it.getItemStack().getType().name().equalsIgnoreCase(materialName)) continue;
                if (minAgeTicks > 0 && it.getTicksLived() < minAgeTicks) continue;

                if (ownerFilter != null) {
                    Optional<UUID> o = getEntityOwnerUUID(it);
                    if (o.isEmpty() || !o.get().equals(ownerFilter)) continue;
                }

                // protect Slabbo unless forced
                if (!ignoreSkips && isSlabboEntity(it)) continue;

                if (!ignoreSkips) {
                    if (isDeniedMaterial(it, deny, allow, useAllow)) continue;
                    int grace = pmr.getMemberGraceFor(it).orElse(getItemGraceTicks(w));
                    if (grace > 0 && it.getTicksLived() < grace) continue;
                    // region/towny not applied to items by design here
                }

                removed++;
                if (!simulate) it.remove();
            }
        }
        return removed;
    }
}
