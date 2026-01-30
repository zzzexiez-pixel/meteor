package com.example.meteor.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ConfigHelper {
    private ConfigHelper() {
    }

    public static List<Particle> readParticles(FileConfiguration config, String path) {
        List<String> names = config.getStringList(path);
        List<Particle> particles = new ArrayList<>();
        for (String name : names) {
            Particle particle = safeParticle(name);
            if (particle != null) {
                particles.add(particle);
            }
        }
        return particles;
    }

    public static Particle safeParticle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("Unknown particle: " + name);
            return null;
        }
    }

    public static Sound safeSound(String name, Sound fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("Unknown sound: " + name);
            return fallback;
        }
    }

    public static World defaultWorld() {
        if (!Bukkit.getWorlds().isEmpty()) {
            return Bukkit.getWorlds().getFirst();
        }
        return null;
    }

    public static Location worldSpawnOrDefault(World world) {
        if (world == null) {
            return new Location(null, 0, 80, 0);
        }
        return world.getSpawnLocation();
    }
}
