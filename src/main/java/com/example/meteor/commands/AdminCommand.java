package com.example.meteor.commands;

import com.example.meteor.world.MeteorManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class AdminCommand implements CommandExecutor, TabCompleter {
    private final MeteorManager meteorManager;

    public AdminCommand(MeteorManager meteorManager) {
        this.meteorManager = meteorManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /admin meteor <start|stop> [x] [z]");
            return true;
        }
        if (!args[0].equalsIgnoreCase("meteor")) {
            sender.sendMessage("§cНеизвестная команда.");
            return true;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "start" -> {
                Integer x = null;
                Integer z = null;
                if (args.length >= 4) {
                    try {
                        x = Integer.parseInt(args[2]);
                        z = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cКоординаты должны быть числами.");
                        return true;
                    }
                }
                meteorManager.startMeteor(sender, x, z);
            }
            case "stop" -> {
                meteorManager.stopMeteor();
                sender.sendMessage("§eМетеорит остановлен.");
            }
            default -> sender.sendMessage("§cИспользование: /admin meteor <start|stop> [x] [z]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            suggestions.add("meteor");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("meteor")) {
            suggestions.add("start");
            suggestions.add("stop");
        }
        return suggestions;
    }
}
