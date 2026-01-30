package com.example.meteor;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class MeteorStorage {
    private final MeteorPlugin plugin;
    private final File databaseFile;

    public MeteorStorage(MeteorPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "meteor-data.db");
        initialize();
    }

    public Optional<StoredZone> loadZone() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT world, x, y, z, impact_time, stage FROM meteor_zone WHERE id = 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new StoredZone(
                resultSet.getString("world"),
                resultSet.getDouble("x"),
                resultSet.getDouble("y"),
                resultSet.getDouble("z"),
                resultSet.getLong("impact_time"),
                resultSet.getString("stage")
            ));
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось загрузить активную зону метеорита: " + ex.getMessage());
            return Optional.empty();
        }
    }

    public void saveZone(StoredZone zone) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO meteor_zone(id, world, x, y, z, impact_time, stage) " +
                     "VALUES(1, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(id) DO UPDATE SET world = excluded.world, x = excluded.x, y = excluded.y, " +
                     "z = excluded.z, impact_time = excluded.impact_time, stage = excluded.stage")) {
            statement.setString(1, zone.world());
            statement.setDouble(2, zone.x());
            statement.setDouble(3, zone.y());
            statement.setDouble(4, zone.z());
            statement.setLong(5, zone.impactTime());
            statement.setString(6, zone.stage());
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось сохранить активную зону метеорита: " + ex.getMessage());
        }
    }

    public void clearZone() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM meteor_zone WHERE id = 1")) {
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось очистить активную зону метеорита: " + ex.getMessage());
        }
    }

    public Map<UUID, Integer> loadRadiationLevels() {
        Map<UUID, Integer> levels = new HashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT uuid, level FROM radiation_levels");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                try {
                    UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                    int level = resultSet.getInt("level");
                    levels.put(uuid, level);
                } catch (IllegalArgumentException ignored) {
                    // Skip invalid UUIDs.
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось загрузить уровни радиации: " + ex.getMessage());
        }
        return levels;
    }

    public void saveRadiationLevel(UUID uuid, int level) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO radiation_levels(uuid, level) VALUES(?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET level = excluded.level")) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, level);
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось сохранить уровень радиации: " + ex.getMessage());
        }
    }

    public void removeRadiationLevel(UUID uuid) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "DELETE FROM radiation_levels WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось удалить уровень радиации: " + ex.getMessage());
        }
    }

    private void initialize() {
        if (!databaseFile.getParentFile().exists() && !databaseFile.getParentFile().mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку для базы данных метеорита.");
            return;
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS meteor_zone (
                  id INTEGER PRIMARY KEY CHECK (id = 1),
                  world TEXT NOT NULL,
                  x REAL NOT NULL,
                  y REAL NOT NULL,
                  z REAL NOT NULL,
                  impact_time INTEGER NOT NULL,
                  stage TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS radiation_levels (
                  uuid TEXT PRIMARY KEY,
                  level INTEGER NOT NULL
                )
                """);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось инициализировать базу данных метеорита: " + ex.getMessage());
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    public record StoredZone(String world, double x, double y, double z, long impactTime, String stage) {}
}
