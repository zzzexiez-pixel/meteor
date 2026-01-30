package com.example.meteor.data;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ResearchRepository {
    private final Plugin plugin;
    private Connection connection;

    public ResearchRepository(Plugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            File dbFile = new File(dataFolder, "research.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS meteor_research (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        summary TEXT NOT NULL
                    )
                    """);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize research DB: " + e.getMessage());
        }
    }

    public void addEntry(String world, int x, int y, int z, String summary) {
        if (connection == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO meteor_research (created_at, world, x, y, z, summary) VALUES (?, ?, ?, ?, ?, ?)"
        )) {
            statement.setString(1, Instant.now().toString());
            statement.setString(2, world);
            statement.setInt(3, x);
            statement.setInt(4, y);
            statement.setInt(5, z);
            statement.setString(6, summary);
            statement.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to insert research entry: " + e.getMessage());
        }
    }

    public List<ResearchEntry> listEntries(int page, int pageSize) {
        List<ResearchEntry> entries = new ArrayList<>();
        if (connection == null) {
            return entries;
        }
        int offset = Math.max(page - 1, 0) * pageSize;
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, created_at, world, x, y, z, summary FROM meteor_research ORDER BY id DESC LIMIT ? OFFSET ?"
        )) {
            statement.setInt(1, pageSize);
            statement.setInt(2, offset);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    entries.add(new ResearchEntry(
                        rs.getInt("id"),
                        rs.getString("created_at"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("summary")
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to list research entries: " + e.getMessage());
        }
        return entries;
    }

    public ResearchEntry getEntry(int id) {
        if (connection == null) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id, created_at, world, x, y, z, summary FROM meteor_research WHERE id = ?"
        )) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new ResearchEntry(
                        rs.getInt("id"),
                        rs.getString("created_at"),
                        rs.getString("world"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("summary")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get research entry: " + e.getMessage());
        }
        return null;
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to close research DB: " + e.getMessage());
            }
        }
    }
}
