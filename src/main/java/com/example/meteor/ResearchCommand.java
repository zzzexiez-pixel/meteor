package com.example.meteor;

import com.example.meteor.research.MeteorResearchEntry;
import com.example.meteor.research.MeteorResearchLibrary;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ResearchCommand implements CommandExecutor {
    private static final int PAGE_SIZE = 12;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendPage(sender, 1);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            int page = parsePage(args, 1, sender);
            if (page < 1) {
                return true;
            }
            sendPage(sender, page);
            return true;
        }

        if (action.equals("show")) {
            if (args.length < 2) {
                sender.sendMessage("§cУкажите id исследования: /research show research-001");
                return true;
            }
            showEntry(sender, args[1]);
            return true;
        }

        showEntry(sender, action);
        return true;
    }

    private void showEntry(CommandSender sender, String id) {
        MeteorResearchEntry entry = MeteorResearchLibrary.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) {
            sender.sendMessage("§cИсследование не найдено: " + id);
            return;
        }
        sender.sendMessage(entry.formatForChat());
    }

    private int parsePage(String[] args, int index, CommandSender sender) {
        if (args.length <= index) {
            return 1;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cСтраница должна быть числом.");
            return -1;
        }
    }

    private void sendPage(CommandSender sender, int page) {
        List<MeteorResearchEntry> entries = MeteorResearchLibrary.list();
        int totalPages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > totalPages) {
            sender.sendMessage("§cСтраница вне диапазона. Всего страниц: " + totalPages);
            return;
        }
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, entries.size());
        sender.sendMessage("§6Исследовательский архив §7(" + page + "/" + totalPages + ")");
        for (int i = start; i < end; i++) {
            MeteorResearchEntry entry = entries.get(i);
            sender.sendMessage("§e" + entry.id() + " §7- §f" + entry.title());
        }
        sender.sendMessage("§7Используйте /research show <id> для деталей.");
    }
}
