package me.luna.clearlite;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ClearCommand implements CommandExecutor {

    private final Clearlite plugin;

    public ClearCommand(Clearlite plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        int removed = new ClearTask(plugin).runWithCount();
        sender.sendMessage("§a[ClearLite] Manual clear completed! Removed " + removed + " entities.");
        return true;
    }
}
