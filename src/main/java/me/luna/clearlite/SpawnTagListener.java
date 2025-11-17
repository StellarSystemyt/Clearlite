package me.luna.clearlite;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class SpawnTagListener implements Listener {

    private final JavaPlugin plugin;

    public SpawnTagListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        Item item = e.getItemDrop();
        tagOwner(item, p.getUniqueId());
    }

    @EventHandler
    public void onProjectile(ProjectileLaunchEvent e) {
        Projectile proj = e.getEntity();
        if (proj.getShooter() instanceof Player p) {
            tagOwner(proj, p.getUniqueId());
        }
    }

    private void tagOwner(Entity ent, UUID uuid) {
        ent.setMetadata(ClearTask.META_OWNER, new FixedMetadataValue(plugin, uuid.toString()));
    }
}
