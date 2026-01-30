package com.example.meteor.world;

import com.example.meteor.util.ConfigHelper;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DomeMonitor {
    private final Plugin plugin;
    private final MeteorManager meteorManager;
    private BukkitRunnable task;
    private boolean domeStable = false;

    public DomeMonitor(Plugin plugin, MeteorManager meteorManager) {
        this.plugin = plugin;
        this.meteorManager = meteorManager;
    }

    public void start() {
        stop();
        FileConfiguration config = plugin.getConfig();
        int interval = config.getInt("meteor.dome.check-interval-seconds", 60);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                Location impact = meteorManager.getImpactLocation();
                if (impact == null) {
                    return;
                }
                World world = impact.getWorld();
                if (world == null) {
                    return;
                }
                int radius = config.getInt("meteor.dome.radius", 15);
                Set<Material> glassTypes = loadGlassTypes(config);

                boolean stable = isDomeStable(world, impact, radius, glassTypes);
                if (stable != domeStable) {
                    domeStable = stable;
                    String message = stable
                        ? "§aКупол стабилизирован. Радиация временно подавлена."
                        : "§cКупол разрушен! Радиация усиливается.";
                    Bukkit.broadcastMessage(message);
                    for (Player player : world.getPlayers()) {
                        player.playSound(player.getLocation(), ConfigHelper.safeSound("BLOCK_GLASS_BREAK", org.bukkit.Sound.BLOCK_GLASS_BREAK), 1.0f, stable ? 1.4f : 0.6f);
                    }
                }
            }
        };
        task.runTaskTimer(plugin, 20L, interval * 20L);
    }

    private boolean isDomeStable(World world, Location center, int radius, Set<Material> glassTypes) {
        int samples = 64;
        int hits = 0;
        for (int i = 0; i < samples; i++) {
            double theta = Math.random() * Math.PI * 2;
            double phi = Math.acos(2 * Math.random() - 1);
            int x = center.getBlockX() + (int) Math.round(Math.sin(phi) * Math.cos(theta) * radius);
            int y = center.getBlockY() + (int) Math.round(Math.cos(phi) * radius);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(phi) * Math.sin(theta) * radius);
            Material material = world.getBlockAt(x, y, z).getType();
            if (glassTypes.contains(material)) {
                hits++;
            }
        }
        return hits >= samples * 0.75;
    }

    private Set<Material> loadGlassTypes(FileConfiguration config) {
        Set<Material> glassTypes = new HashSet<>();
        List<String> types = config.getStringList("meteor.dome.glass-types");
        for (String type : types) {
            try {
                glassTypes.add(Material.valueOf(type));
            } catch (IllegalArgumentException ignored) {
                // ignore
            }
        }
        for (Material material : Material.values()) {
            if (material.isBlock() && material.name().contains("GLASS")) {
                glassTypes.add(material);
            }
        }
        return glassTypes;
    }

    public boolean isDomeStable() {
        return domeStable;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
