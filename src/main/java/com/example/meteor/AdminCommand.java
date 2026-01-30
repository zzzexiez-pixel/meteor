package com.example.meteor;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AdminCommand implements CommandExecutor {
    private final MeteorPlugin plugin;
    private final MeteorController meteorController;

    public AdminCommand(MeteorPlugin plugin, MeteorController meteorController) {
        this.plugin = plugin;
        this.meteorController = meteorController;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || !args[0].equalsIgnoreCase("meteor")) {
            sender.sendMessage("Использование: /admin meteor <start|stop> [x] [z] | /admin meteor start free [x] [z]");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Использование: /admin meteor <start|stop> [x] [z]");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("stop")) {
            meteorController.stopMeteor();
            sender.sendMessage("Метеорит остановлен, зона удалена.");
            return true;
        }

        if (!action.equals("start")) {
            sender.sendMessage("Неизвестная команда. Используйте /admin meteor start или /admin meteor stop.");
            return true;
        }

        boolean immediate = false;
        int index = 2;
        if (args.length > 2 && args[2].equalsIgnoreCase("free")) {
            immediate = true;
            index = 3;
        }

        Location target = resolveLocation(sender, args, index);
        if (target == null) {
            sender.sendMessage("Не удалось определить точку падения. Укажите координаты X Z.");
            return true;
        }

        meteorController.startMeteor(target, immediate);
        if (immediate) {
            sender.sendMessage("Метеорит запущен принудительно.");
        } else {
            sender.sendMessage("Метеорит запущен с часовым таймером.");
        }
        return true;
    }

    private Location resolveLocation(CommandSender sender, String[] args, int index) {
        if (args.length >= index + 2) {
            try {
                double x = Double.parseDouble(args[index]);
                double z = Double.parseDouble(args[index + 1]);
                World world = resolveWorld(sender);
                if (world == null) {
                    return null;
                }
                int y = world.getHighestBlockYAt((int) Math.round(x), (int) Math.round(z));
                return new Location(world, x, y, z);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (sender instanceof Player player) {
            Location base = player.getLocation();
            World world = base.getWorld();
            int y = world.getHighestBlockYAt(base.getBlockX(), base.getBlockZ());
            return new Location(world, base.getX(), y, base.getZ());
        }
        return null;
    }

    private World resolveWorld(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        if (!plugin.getServer().getWorlds().isEmpty()) {
            return plugin.getServer().getWorlds().get(0);
        }
        return null;
    }
}
