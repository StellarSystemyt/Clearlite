package me.luna.clearlite;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ClearCommand implements CommandExecutor {

    private final Clearlite plugin;

    public ClearCommand(Clearlite plugin) {
        this.plugin = plugin;
    }

    private String col(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage(col("&eUsage: /" + label + " &7[simulate|now|reload]"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "simulate" -> {
                if (!sender.hasPermission("clearlite.simulate")) {
                    sender.sendMessage(col("&cYou lack permission: clearlite.simulate"));
                    return true;
                }
                int count = new ClearTask(plugin).execute(true); // <-- call the method
                sender.sendMessage(col("&a[Clearlite] &f" + count + " &7entities would be removed."));
            }

            case "now" -> {
                if (!sender.hasPermission("clearlite.now")) {
                    sender.sendMessage(col("&cYou lack permission: clearlite.now"));
                    return true;
                }
                int removed = new ClearTask(plugin).execute(false); // <-- call the method
                sender.sendMessage(col("&a[Clearlite] Removed &f" + removed + " &aentities."));
            }

            case "reload" -> {
                if (!sender.hasPermission("clearlite.reload")) {
                    sender.sendMessage(col("&cYou lack permission: clearlite.reload"));
                    return true;
                }
                plugin.reloadConfig();
                sender.sendMessage(col("&a[Clearlite] Config reloaded."));
            }

            default -> sender.sendMessage(col("&eUsage: /" + label + " &7[simulate|now|reload]"));
        }

        return true;
    }
}