package com.example.meteor;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MeteorPlugin extends JavaPlugin {
    private MeteorController meteorController;
    private SettingsRepository settingsRepository;
    private MeteorStorage meteorStorage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settingsRepository = new SettingsRepository(this);
        settingsRepository.loadToConfig(getConfig());
        saveConfig();

        meteorStorage = new MeteorStorage(this);
        meteorController = new MeteorController(this, meteorStorage);
        PluginCommand adminCommand = getCommand("admin");
        if (adminCommand != null) {
            adminCommand.setExecutor(new AdminCommand(this, meteorController));
        }
        PluginCommand radiationCommand = getCommand("radiation");
        if (radiationCommand != null) {
            radiationCommand.setExecutor(new RadiationCommand(meteorController));
        }
        getServer().getPluginManager().registerEvents(new MeteorListener(meteorController), this);
    }

    @Override
    public void onDisable() {
        if (meteorController != null) {
            meteorController.stopMeteor();
        }
        if (settingsRepository != null) {
            settingsRepository.saveFromConfig(getConfig());
        }
    }
}
