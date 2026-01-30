package com.example.meteor;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MeteorCommand implements CommandExecutor {
    private final MeteorPlugin plugin;
    private final MeteorController meteorController;

    public MeteorCommand(MeteorPlugin plugin, MeteorController meteorController) {
        this.plugin = plugin;
        this.meteorController = meteorController;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Использование: /meteor <start|stop> [x y z]");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("stop")) {
            meteorController.stop();
            sender.sendMessage("Метеорит остановлен.");
            return true;
        }

        if (!action.equals("start")) {
            sender.sendMessage("Неизвестная команда. Используйте /meteor start или /meteor stop.");
            return true;
        }

        Location target = null;
        if (args.length >= 4) {
            try {
                double x = Double.parseDouble(args[1]);
                double y = Double.parseDouble(args[2]);
                double z = Double.parseDouble(args[3]);
                if (sender instanceof Player player) {
                    target = new Location(player.getWorld(), x, y, z);
                } else if (!plugin.getServer().getWorlds().isEmpty()) {
                    target = new Location(plugin.getServer().getWorlds().get(0), x, y, z);
                } else {
                    sender.sendMessage("Не удалось определить мир сервера.");
                    return true;
                }
            } catch (NumberFormatException ex) {
                sender.sendMessage("Координаты должны быть числами.");
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player.getLocation();
        } else {
            sender.sendMessage("Для консоли нужно указать координаты: /meteor start x y z.");
            return true;
        }

        if (target == null) {
            sender.sendMessage("Не удалось определить точку падения.");
            return true;
        }

        meteorController.start(target);
        sender.sendMessage("Метеорит запущен.");
        return true;
    }
}
