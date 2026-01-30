package com.example.meteor;

import com.example.meteor.commands.AdminCommand;
import com.example.meteor.commands.RadiationCommand;
import com.example.meteor.commands.ResearchCommand;
import com.example.meteor.data.ResearchRepository;
import com.example.meteor.world.DomeMonitor;
import com.example.meteor.world.MeteorManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class MeteorPlugin extends JavaPlugin {
    private MeteorManager meteorManager;
    private ResearchRepository researchRepository;
    private DomeMonitor domeMonitor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        researchRepository = new ResearchRepository(this);
        researchRepository.init();

        meteorManager = new MeteorManager(this, researchRepository);
        domeMonitor = new DomeMonitor(this, meteorManager);

        var admin = new AdminCommand(meteorManager);
        var radiation = new RadiationCommand(meteorManager);
        var research = new ResearchCommand(researchRepository);

        getCommand("admin").setExecutor(admin);
        getCommand("admin").setTabCompleter(admin);
        getCommand("radiation").setExecutor(radiation);
        getCommand("radiation").setTabCompleter(radiation);
        getCommand("research").setExecutor(research);
        getCommand("research").setTabCompleter(research);

        domeMonitor.start();

        Bukkit.getLogger().info("MeteorPlugin enabled");
    }

    @Override
    public void onDisable() {
        if (meteorManager != null) {
            meteorManager.stopMeteor();
        }
        if (domeMonitor != null) {
            domeMonitor.stop();
        }
        if (researchRepository != null) {
            researchRepository.close();
        }
    }
}
