package com.example.meteor.commands;

import com.example.meteor.world.MeteorManager;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class RadiationCommand implements CommandExecutor, TabCompleter {
    private final MeteorManager meteorManager;

    public RadiationCommand(MeteorManager meteorManager) {
        this.meteorManager = meteorManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cКоманда доступна только игроку.");
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("check")) {
            sender.sendMessage("§cИспользование: /radiation check");
            return true;
        }
        Location impact = meteorManager.getImpactLocation();
        if (impact == null) {
            player.sendMessage("§7Радиация отсутствует.");
            return true;
        }
        double distance = player.getLocation().distance(impact);
        String level;
        if (distance <= 10) {
            level = "§4Критический";
        } else if (distance <= 25) {
            level = "§cОпасный";
        } else if (distance <= 40) {
            level = "§eПовышенный";
        } else {
            level = "§aНизкий";
        }
        player.sendMessage("§6Радиация: " + level + "§6 (" + String.format("%.1f", distance) + "м до ядра)");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("check");
        }
        return List.of();
    }
}
