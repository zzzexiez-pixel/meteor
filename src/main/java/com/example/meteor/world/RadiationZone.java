package com.example.meteor.world;

import com.example.meteor.util.ConfigHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

public class RadiationZone {
    private final Plugin plugin;
    private final Location origin;
    private final FileConfiguration config;
    private BukkitRunnable task;
    private int elapsedSeconds = 0;

    public RadiationZone(Plugin plugin, Location origin, FileConfiguration config) {
        this.plugin = plugin;
        this.origin = origin.clone();
        this.config = config;
    }

    public void start() {
        stop();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                World world = origin.getWorld();
                if (world == null) {
                    cancel();
                    return;
                }
                double radius = config.getDouble("meteor.radiation.radius", 40.0);
                int progressionSeconds = config.getInt("meteor.radiation.progression-seconds", 20);
                int halfHeartInterval = config.getInt("meteor.radiation.damage-half-heart-interval", 5);
                int fullHeartInterval = config.getInt("meteor.radiation.damage-heart-interval", 3);
                Sound hiss = ConfigHelper.safeSound("BLOCK_SCULK_SENSOR_CLICKING", Sound.BLOCK_SCULK_SENSOR_CLICKING);
                List<Particle> particles = ConfigHelper.readParticles(config, "meteor.core.radiation-particles");
                PotionEffectType nausea = ConfigHelper.safePotionEffectType("NAUSEA", "CONFUSION");

                for (Player player : world.getPlayers()) {
                    double distanceSq = player.getLocation().distanceSquared(origin);
                    if (distanceSq > radius * radius) {
                        continue;
                    }
                    if (nausea != null) {
                        player.addPotionEffect(new PotionEffect(nausea, 60, 0, true, true, true));
                    }
                    player.spawnParticle(Particle.SPORE_BLOSSOM_AIR, player.getLocation(), 12, 0.7, 1.2, 0.7, 0.02);
                    for (Particle particle : particles) {
                        player.spawnParticle(particle, player.getLocation(), 6, 0.6, 0.8, 0.6, 0.02);
                    }
                    if (elapsedSeconds % (elapsedSeconds < progressionSeconds ? halfHeartInterval : fullHeartInterval) == 0) {
                        player.damage(elapsedSeconds < progressionSeconds ? 1.0 : 2.0);
                        player.playSound(player.getLocation(), hiss, 0.6f, 0.5f);
                    }
                }

                if (config.getBoolean("meteor.radiation.sculk.enabled", true) && elapsedSeconds % config.getInt("meteor.radiation.sculk.interval-seconds", 8) == 0) {
                    spreadSculk(world);
                }

                elapsedSeconds++;
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
    }

    private void spreadSculk(World world) {
        double radius = config.getDouble("meteor.radiation.sculk.radius", 18.0);
        int blocksPerStep = config.getInt("meteor.radiation.sculk.blocks-per-step", 6);
        List<String> replaceable = config.getStringList("meteor.radiation.sculk.replaceable-blocks");
        List<Material> replaceableMaterials = new ArrayList<>();
        for (String material : replaceable) {
            try {
                replaceableMaterials.add(Material.valueOf(material));
            } catch (IllegalArgumentException ignored) {
                // ignore
            }
        }
        if (replaceableMaterials.isEmpty()) {
            replaceableMaterials.add(Material.STONE);
        }

        for (int i = 0; i < blocksPerStep; i++) {
            double angle = Math.random() * Math.PI * 2;
            double distance = Math.random() * radius;
            int x = origin.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = origin.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight()) {
                continue;
            }
            var block = world.getBlockAt(x, y - 1, z);
            if (!replaceableMaterials.contains(block.getType())) {
                continue;
            }
            block.setType(Material.SCULK);
            world.spawnParticle(Particle.SCULK_SOUL, block.getLocation().add(0.5, 1.0, 0.5), 6, 0.3, 0.4, 0.3, 0.01);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
