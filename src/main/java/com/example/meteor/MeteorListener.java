package com.example.meteor;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public class MeteorListener implements Listener {
    private final MeteorController meteorController;

    public MeteorListener(MeteorController meteorController) {
        this.meteorController = meteorController;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Location core = meteorController.getCoreLocation();
        if (core == null) {
            return;
        }
        if (!event.getBlock().getLocation().equals(core)) {
            return;
        }
        Player player = event.getPlayer();
        if (!meteorController.isMeteorSafe()) {
            event.setCancelled(true);
            player.sendMessage("§cМетеорит слишком радиоактивен. Постройте купол.");
            return;
        }
        if (!meteorController.canHarvestMeteor(player)) {
            event.setCancelled(true);
            player.sendMessage("§eНужен инструмент с Silk Touch.");
            return;
        }
        meteorController.handleMeteorHarvest(event);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        meteorController.handleRadiationDeath(event.getEntity());
    }
}
