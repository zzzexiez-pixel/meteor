package com.example.meteor.research;

import java.util.List;

public record MeteorResearchEntry(
    String id,
    String title,
    String summary,
    List<String> tasks,
    String reward,
    String risk
) {
    public String formatForChat() {
        StringBuilder builder = new StringBuilder();
        builder.append("§6[").append(id).append("] §e").append(title).append("\n");
        builder.append("§7").append(summary).append("\n");
        for (int i = 0; i < tasks.size(); i++) {
            builder.append("§f").append(i + 1).append('.').append(' ').append(tasks.get(i)).append("\n");
        }
        builder.append("§a").append(reward).append("\n");
        builder.append("§c").append(risk);
        return builder.toString();
    }
}
