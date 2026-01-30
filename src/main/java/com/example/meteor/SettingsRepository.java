package com.example.meteor;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.bukkit.configuration.file.FileConfiguration;

public class SettingsRepository {
    private final MeteorPlugin plugin;
    private final File databaseFile;

    public SettingsRepository(MeteorPlugin plugin) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), "meteor.db");
        initialize();
    }

    public void loadToConfig(FileConfiguration config) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT key, value FROM settings");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String key = resultSet.getString("key");
                String value = resultSet.getString("value");
                if (key != null && value != null) {
                    config.set(key, parseValue(value, config.get(key)));
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось загрузить настройки из базы данных: " + ex.getMessage());
        }
    }

    public void saveFromConfig(FileConfiguration config) {
        Map<String, Object> values = config.getValues(true);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "INSERT INTO settings(key, value) VALUES(?, ?) " +
                     "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                statement.setString(1, entry.getKey());
                statement.setString(2, String.valueOf(entry.getValue()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось сохранить настройки в базу данных: " + ex.getMessage());
        }
    }

    private void initialize() {
        if (!databaseFile.getParentFile().exists() && !databaseFile.getParentFile().mkdirs()) {
            plugin.getLogger().warning("Не удалось создать папку для базы данных.");
            return;
        }
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                  key TEXT PRIMARY KEY,
                  value TEXT NOT NULL
                )
                """);
        } catch (SQLException ex) {
            plugin.getLogger().warning("Не удалось инициализировать базу данных: " + ex.getMessage());
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    private Object parseValue(String rawValue, Object currentValue) {
        if (currentValue instanceof Integer) {
            try {
                return Integer.parseInt(rawValue);
            } catch (NumberFormatException ignored) {
                return currentValue;
            }
        }
        if (currentValue instanceof Double) {
            try {
                return Double.parseDouble(rawValue);
            } catch (NumberFormatException ignored) {
                return currentValue;
            }
        }
        if (currentValue instanceof Boolean) {
            return Boolean.parseBoolean(rawValue);
        }
        return rawValue;
    }
}
