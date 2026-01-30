package com.example.meteor;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MeteorPlugin extends JavaPlugin {
    private MeteorController meteorController;
    private SettingsRepository settingsRepository;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settingsRepository = new SettingsRepository(this);
        settingsRepository.loadToConfig(getConfig());
        saveConfig();

        meteorController = new MeteorController(this);
        PluginCommand command = getCommand("meteor");
        if (command != null) {
            command.setExecutor(new MeteorCommand(this, meteorController));
        }
    }

    @Override
    public void onDisable() {
        if (meteorController != null) {
            meteorController.stop();
        }
        if (settingsRepository != null) {
            settingsRepository.saveFromConfig(getConfig());
        }
    }
}
