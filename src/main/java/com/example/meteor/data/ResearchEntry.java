package com.example.meteor.data;

public record ResearchEntry(
    int id,
    String createdAt,
    String world,
    int x,
    int y,
    int z,
    String summary
) {
}
