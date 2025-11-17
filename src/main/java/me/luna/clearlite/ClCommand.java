package me.luna.clearlite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class ClCommand implements CommandExecutor, TabCompleter {

    private final Clearlite plugin;   // <-- use concrete type to access mergeDefaultsIntoConfig()
    private final ClearTask task;
    private final MemberCommand memberCmd;

    public ClCommand(Clearlite plugin, ClearTask task, MemberCommand memberCmd) {
        this.plugin = plugin;
        this.task = task;
        this.memberCmd = memberCmd;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { usage(sender, label); return true; }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "simulate" -> {
                if (!sender.hasPermission("clearlite.simulate")) { deny(sender); return true; }
                World world = worldArg(args, 1);
                Double radius = radiusArg(args, 2);
                Location center = autoCenter(sender, world);
                int count = task.execute(true, world, center, radius == null ? -1 : radius);
                sender.sendMessage(col("&a[Clearlite] Simulated &f" + count + " &aentities."));
                sender.sendMessage(formatBreakdown(task.getLastBreakdownSnapshot(), 8));
                return true;
            }
            case "now" -> {
                if (!sender.hasPermission("clearlite.now")) { deny(sender); return true; }
                World world = worldArg(args, 1);
                Double radius = radiusArg(args, 2);
                Location center = autoCenter(sender, world);
                int count = task.execute(false, world, center, radius == null ? -1 : radius);
                String msg = col("&a[Clearlite] Removed &f" + count + " &aentities.");
                if (plugin.getConfig().getBoolean("broadcast", true)) {
                    Bukkit.getOnlinePlayers().forEach(pl -> pl.sendMessage(msg));
                } else {
                    sender.sendMessage(msg);
                }
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("clearlite.reload")) { deny(sender); return true; }
                plugin.reloadConfig();
                plugin.updateConfigAuto();
                task.setIntervalTicks(plugin.getConfig().getInt("tps-check-interval-ticks", 200));
                sender.sendMessage(col("&a[Clearlite] Config reloaded and updated with new defaults."));
                return true;
            }
            case "status" -> {
                if (!sender.hasPermission("clearlite.status")) { deny(sender); return true; }
                double tps = readTpsSafe();
                long lastMs = task.getLastRunTimestampMs();
                String ago = (lastMs > 0) ? human(System.currentTimeMillis() - lastMs) + " ago" : "never";
                long nextEta = task.getNextRunEtaMs();
                String next = (nextEta >= 0) ? (nextEta == 0 ? "imminent" : "in " + human(nextEta)) : "unknown";
                sender.sendMessage(col("&7--- &aClearlite Status &7---"));
                sender.sendMessage(col("&7TPS: &f" + (tps <= 0 ? "N/A" : String.format(Locale.US, "%.1f", tps))));
                sender.sendMessage(col("&7Last run: &f" + ago));
                sender.sendMessage(col("&7Removed last run: &f" + task.getLastAffected()));
                sender.sendMessage(col("&7Breakdown: &f" + inlineBreakdown(task.getLastBreakdownSnapshot(), 5)));
                sender.sendMessage(col("&7Next run: &f" + next));
                return true;
            }
            case "member" -> {
                if (!sender.hasPermission("clearlite.member")) { deny(sender); return true; }
                String[] shifted = Arrays.copyOfRange(args, 1, args.length);
                return memberCmd.onCommand(sender, cmd, label, shifted);
            }
            // NEW: debug dropped items (reasons)
            case "debug" -> {
                if (!sender.hasPermission("clearlite.debug")) { deny(sender); return true; }
                World w = (args.length > 1) ? Bukkit.getWorld(args[1]) : (sender instanceof Player p ? p.getWorld() : null);
                int limit = (args.length > 2) ? Optional.ofNullable(tryInt(args[2])).orElse(20) : 20;
                for (String line : task.debugItems(w, limit)) {
                    sender.sendMessage(col("&7[debug] &f" + line));
                }
                return true;
            }
            // NEW: targeted item-only clear
            case "items" -> {
                if (!sender.hasPermission("clearlite.now")) { deny(sender); return true; }
                String material = (args.length > 1 && !"*".equals(args[1])) ? args[1].toUpperCase(Locale.ROOT) : null;
                Integer minAge = (args.length > 2) ? tryInt(args[2]) : 0;
                World world = (args.length > 3) ? Bukkit.getWorld(args[3]) : (sender instanceof Player p ? p.getWorld() : null);
                Double radius = (args.length > 4) ? tryDouble(args[4]) : -1;
                boolean force = (args.length > 5) && isTrue(args[5]);      // ignoreSkips
                boolean simulate = (args.length > 6) && isTrue(args[6]);   // simulate

                Location center = (sender instanceof Player p && world != null && p.getWorld().equals(world))
                        ? p.getLocation()
                        : (world != null ? world.getSpawnLocation() : null);

                int count = task.clearItemsTargeted(
                        simulate,
                        world,
                        center,
                        radius == null ? -1 : radius,
                        material,
                        minAge == null ? 0 : minAge,
                        null,  // owner filter not exposed in CLI yet
                        force
                );

                sender.sendMessage(col("&a[Clearlite] " + (simulate ? "Would remove" : "Removed") +
                        " &f" + count + " &aitem(s)" +
                        (material != null ? " of &f" + material : "") +
                        (minAge != null && minAge > 0 ? " &7(≥" + minAge + " ticks)" : "") +
                        (radius != null && radius >= 0 ? " &7(radius " + radius.intValue() + ")" : "") +
                        (force ? " &c[FORCED]" : "")));
                return true;
            }
            default -> { usage(sender, label); return true; }
        }
    }

    // -------- helpers --------
    private void usage(CommandSender s, String l) {
        s.sendMessage(col("&7Usage: &f/" + l + " &7<simulate|now|reload|status|member|debug|items> [args]"));
        s.sendMessage(col("&7 - &f/" + l + " debug &7[world] [limit]"));
        s.sendMessage(col("&7 - &f/" + l + " items &7[material|*] [minAge] [world] [radius] [force] [simulate]"));
    }
    private void deny(CommandSender s) { s.sendMessage(col("&cYou don't have permission for that.")); }
    private String col(String s) { return s.replace('&', '§'); }

    private Location autoCenter(CommandSender sender, World world) {
        if (world == null) return null;
        if (sender instanceof Player p && p.getWorld().equals(world)) return p.getLocation();
        return world.getSpawnLocation();
    }

    private World worldArg(String[] args, int idx) {
        if (args.length <= idx) return null;
        String w = args[idx];
        return Bukkit.getWorld(w);
    }

    private Double radiusArg(String[] args, int idx) {
        if (args.length <= idx) return null;
        try {
            double r = Double.parseDouble(args[idx]);
            return r > 0 ? r : null;
        } catch (NumberFormatException e) { return null; }
    }

    private double readTpsSafe() {
        try {
            double[] tps = Bukkit.getServer().getTPS(); // Paper
            if (tps != null && tps.length > 0) return tps[0];
        } catch (Throwable ignored) {
            try {
                double[] tps = (double[]) Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
                if (tps != null && tps.length > 0) return tps[0];
            } catch (Throwable ignored2) {}
        }
        return -1.0;
    }

    private String formatBreakdown(Map<EntityType,Integer> map, int maxLines) {
        if (map == null || map.isEmpty()) return col("&7(no matches)");
        List<Map.Entry<EntityType,Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a,b)->Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder(col("&7Breakdown:"));
        int n = Math.min(maxLines, list.size());
        for (int i=0;i<n;i++) {
            var e = list.get(i);
            sb.append(col("\n&7 - &f")).append(e.getKey().name()).append(col(" &7x&f")).append(e.getValue());
        }
        return sb.toString();
    }

    private String inlineBreakdown(Map<EntityType,Integer> map, int maxItems) {
        if (map == null || map.isEmpty()) return "(none)";
        List<Map.Entry<EntityType,Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a,b)->Integer.compare(b.getValue(), a.getValue()));
        return list.stream().limit(maxItems).map(e -> e.getKey().name() + " x" + e.getValue()).collect(Collectors.joining(", "));
    }

    private String human(long ms) {
        long s = Math.max(0, ms/1000);
        long m = s/60, h = m/60; s%=60; m%=60;
        if (h>0) return h+"h"+(m>0?m+"m":"");
        if (m>0) return m+"m"+(s>0?s+"s":"");
        return s+"s";
    }

    private Integer tryInt(String s){ try{ return Integer.parseInt(s);}catch(Exception e){ return null; } }
    private Double tryDouble(String s){ try{ return Double.parseDouble(s);}catch(Exception e){ return null; } }
    private boolean isTrue(String s){
        return switch(s.toLowerCase(Locale.ROOT)){
            case "1","true","yes","y","on" -> true;
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return partial(List.of("simulate","now","reload","status","member","debug","items"), args[0]);

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (List.of("simulate","now").contains(sub)) {
            if (args.length == 2) return partial(Bukkit.getWorlds().stream().map(World::getName).toList(), args[1]);
            if (args.length == 3) return partial(List.of("50","100","200","500","1000"), args[2]);
        }
        if (sub.equals("member")) {
            String[] shifted = Arrays.copyOfRange(args, 1, args.length);
            return memberCmd.onTabComplete(sender, cmd, alias, shifted);
        }
        if (sub.equals("debug")) {
            if (args.length == 2) return partial(Bukkit.getWorlds().stream().map(World::getName).toList(), args[1]);
            if (args.length == 3) return partial(List.of("10","20","50","100"), args[2]);
        }
        if (sub.equals("items")) {
            if (args.length == 2) return partial(List.of("*"), args[1]);
            if (args.length == 3) return partial(List.of("0","100","200","400","600"), args[2]);
            if (args.length == 4) return partial(Bukkit.getWorlds().stream().map(World::getName).toList(), args[3]);
            if (args.length == 5) return partial(List.of("25","50","100","200","500","1000"), args[4]);
            if (args.length == 6) return partial(List.of("false","true"), args[5]);
            if (args.length == 7) return partial(List.of("false","true"), args[6]);
        }
        return Collections.emptyList();
    }

    private List<String> partial(List<String> options, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).collect(Collectors.toList());
    }
}
