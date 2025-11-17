package me.luna.clearlite;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public class MemberCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;

    public MemberCommand(JavaPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("clearlite.member")) {
            sender.sendMessage(color("&cYou don't have permission (clearlite.member)."));
            return true;
        }
        if (args.length < 2) { sendUsage(sender, label); return true; }

        String targetKey = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);

        String memberKey = resolveMemberKey(targetKey);
        if (memberKey == null) {
            sender.sendMessage(color("&cCould not resolve player/UUID: &e" + targetKey));
            return true;
        }

        switch (action) {
            case "show" -> { showMember(sender, memberKey); return true; }
            case "skip" -> {
                if (args.length < 4) {
                    sender.sendMessage(color("&eUsage: &7/" + label + " member <player|uuid> skip <items|projectiles> <true|false>"));
                    return true;
                }
                String what = args[2].toLowerCase(Locale.ROOT);
                Boolean flag = parseBoolean(args[3].toLowerCase(Locale.ROOT));
                if (flag == null || !(what.equals("items") || what.equals("projectiles"))) {
                    sender.sendMessage(color("&cUsage: &7/" + label + " member <player|uuid> skip <items|projectiles> <true|false>"));
                    return true;
                }
                String path = "members." + memberKey + ".skip." + what;
                plugin.getConfig().set(path, flag);
                plugin.saveConfig();
                sender.sendMessage(color("&aSet &f" + path + " &ato &e" + flag));
                return true;
            }
            case "grace" -> {
                if (args.length < 3) {
                    sender.sendMessage(color("&eUsage: &7/" + label + " member <player|uuid> grace <ticks|off>"));
                    return true;
                }
                String v = args[2].toLowerCase(Locale.ROOT);
                String path = "members." + memberKey + ".item_grace_ticks";
                if (v.equals("off") || v.equals("none")) {
                    plugin.getConfig().set(path, null);
                    plugin.saveConfig();
                    sender.sendMessage(color("&aRemoved member grace override for &e" + memberKey));
                    return true;
                }
                Integer ticks = parseInt(v);
                if (ticks == null || ticks < 0) {
                    sender.sendMessage(color("&cInvalid ticks. Use a non-negative integer or 'off'."));
                    return true;
                }
                plugin.getConfig().set(path, ticks);
                plugin.saveConfig();
                sender.sendMessage(color("&aSet &f" + path + " &ato &e" + ticks + " &aticks"));
                return true;
            }
            default -> { sendUsage(sender, label); return true; }
        }
    }

    private void showMember(CommandSender sender, String memberKey) {
        String base = "members." + memberKey;
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(base);
        if (sec == null) { sender.sendMessage(color("&eNo member-specific settings for &f" + memberKey)); return; }
        boolean skipItems = sec.getBoolean("skip.items", false);
        boolean skipProj  = sec.getBoolean("skip.projectiles", false);
        String grace = sec.contains("item_grace_ticks") ? String.valueOf(sec.getInt("item_grace_ticks")) : "inherit";
        sender.sendMessage(color("&7--- &aClearlite Member &7---"));
        sender.sendMessage(color("&7Key: &f" + memberKey));
        sender.sendMessage(color("&7skip.items: &f" + skipItems));
        sender.sendMessage(color("&7skip.projectiles: &f" + skipProj));
        sender.sendMessage(color("&7item_grace_ticks: &f" + grace));
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(color("&7Usage:"));
        sender.sendMessage(color("&7/" + label + " member &f<player|uuid> &ashow"));
        sender.sendMessage(color("&7/" + label + " member &f<player|uuid> &askip &f<items|projectiles> <true|false>"));
        sender.sendMessage(color("&7/" + label + " member &f<player|uuid> &agrace &f<ticks|off>"));
    }

    private String resolveMemberKey(String input) {
        try { return UUID.fromString(input).toString(); } catch (IllegalArgumentException ignored) {}
        Player p = Bukkit.getPlayerExact(input);
        if (p != null) return p.getUniqueId().toString();
        try { OfflinePlayer off = Bukkit.getOfflinePlayer(input); if (off != null && off.getUniqueId() != null) return off.getUniqueId().toString(); } catch (Throwable ignored) {}
        return input; // keep name as key if nothing else
    }

    private Boolean parseBoolean(String s) {
        return switch (s) { case "true","t","yes","y","1","on" -> true; case "false","f","no","n","0","off" -> false; default -> null; };
    }
    private Integer parseInt(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; } }
    private String color(String s) { return s.replace('&', '§'); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("clearlite.member")) return Collections.emptyList();
        if (args.length == 1) return partial(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[0]);
        if (args.length == 2) return partial(List.of("show","skip","grace"), args[1]);
        if (args.length == 3 && args[1].equalsIgnoreCase("skip")) return partial(List.of("items","projectiles"), args[2]);
        if (args.length == 3 && args[1].equalsIgnoreCase("grace")) return partial(List.of("off","0","100","200","400","600"), args[2]);
        if (args.length == 4 && args[1].equalsIgnoreCase("skip")) return partial(List.of("true","false"), args[3]);
        return Collections.emptyList();
    }
    private List<String> partial(List<String> options, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(t)).collect(Collectors.toList());
    }
}
