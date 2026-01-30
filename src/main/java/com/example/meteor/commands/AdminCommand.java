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
            sender.sendMessage("§cИспользование: /admin meteor <start|stop> [x] [z] | /admin meteor start free [x] [z]");
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
                boolean immediate = false;
                int index = 2;
                if (args.length >= 3 && args[2].equalsIgnoreCase("free")) {
                    immediate = true;
                    index = 3;
                }
                if (args.length >= index + 2) {
                    try {
                        x = Integer.parseInt(args[index]);
                        z = Integer.parseInt(args[index + 1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cКоординаты должны быть числами.");
                        return true;
                    }
                }
                meteorManager.startMeteor(sender, x, z, immediate);
            }
            case "stop" -> {
                meteorManager.stopMeteor();
                sender.sendMessage("§eМетеорит остановлен.");
            }
            default -> sender.sendMessage("§cИспользование: /admin meteor <start|stop> [x] [z] | /admin meteor start free [x] [z]");
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
        } else if (args.length == 3 && args[0].equalsIgnoreCase("meteor") && args[1].equalsIgnoreCase("start")) {
            suggestions.add("free");
        }
        return suggestions;
    }
}
