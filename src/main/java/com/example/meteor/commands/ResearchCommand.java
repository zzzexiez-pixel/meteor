package com.example.meteor.commands;

import com.example.meteor.data.ResearchEntry;
import com.example.meteor.data.ResearchRepository;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

public class ResearchCommand implements CommandExecutor, TabCompleter {
    private final ResearchRepository repository;

    public ResearchCommand(ResearchRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cИспользование: /research [list <page>|show <id>]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "list" -> {
                int page = 1;
                if (args.length >= 2) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cНомер страницы должен быть числом.");
                        return true;
                    }
                }
                List<ResearchEntry> entries = repository.listEntries(page, 6);
                if (entries.isEmpty()) {
                    sender.sendMessage("§7Записей не найдено.");
                    return true;
                }
                sender.sendMessage("§6Архив метеоритов (стр. " + page + "):");
                for (ResearchEntry entry : entries) {
                    sender.sendMessage("§e#" + entry.id() + " §7" + entry.createdAt() + " §f" + entry.summary());
                }
            }
            case "show" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cУкажите ID записи.");
                    return true;
                }
                int id;
                try {
                    id = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cID должен быть числом.");
                    return true;
                }
                ResearchEntry entry = repository.getEntry(id);
                if (entry == null) {
                    sender.sendMessage("§cЗапись не найдена.");
                    return true;
                }
                sender.sendMessage("§6Запись #" + entry.id());
                sender.sendMessage("§7Дата: " + entry.createdAt());
                sender.sendMessage("§7Мир: " + entry.world());
                sender.sendMessage("§7Координаты: X" + entry.x() + " Y" + entry.y() + " Z" + entry.z());
                sender.sendMessage("§fОписание: " + entry.summary());
            }
            default -> sender.sendMessage("§cИспользование: /research [list <page>|show <id>]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("list", "show");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            return List.of("1", "2", "3");
        }
        return new ArrayList<>();
    }
}
