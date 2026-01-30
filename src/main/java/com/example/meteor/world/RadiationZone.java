package com.example.meteor.world;

import com.example.meteor.util.ConfigHelper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RadiationZone {
    private final Plugin plugin;
    private final Location origin;
    private final FileConfiguration config;
    private BukkitRunnable task;
    private int elapsedSeconds = 0;
    private boolean domeStable = false;
    private int domeCheckCounter = 0;
    private List<Vector> domeOffsets;

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
                if (shouldCheckDome()) {
                    domeStable = isDomeComplete(world);
                }
                if (domeStable) {
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
                    boolean protectedByLeather = applyLeatherProtection(player);
                    if (protectedByLeather) {
                        player.spawnParticle(Particle.SPORE_BLOSSOM_AIR, player.getLocation(), 6, 0.5, 0.8, 0.5, 0.01);
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
                    spreadSculk(world, calculateSculkRadius());
                }

                elapsedSeconds++;
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
    }

    private void spreadSculk(World world, double radius) {
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

    private double calculateSculkRadius() {
        double startRadius = config.getDouble("meteor.radiation.sculk.start-radius", 10.0);
        double maxRadius = config.getDouble("meteor.radiation.sculk.max-radius", 24.0);
        int growthDuration = config.getInt("meteor.radiation.sculk.grow-duration-seconds", 600);
        if (growthDuration <= 0) {
            return maxRadius;
        }
        double progress = Math.min(1.0, elapsedSeconds / (double) growthDuration);
        return startRadius + (maxRadius - startRadius) * progress;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private boolean applyLeatherProtection(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean hasLeather = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (!isLeatherArmor(item.getType())) {
                continue;
            }
            hasLeather = true;
            Damageable meta = item.hasItemMeta() && item.getItemMeta() instanceof Damageable damageable ? damageable : null;
            if (meta == null) {
                continue;
            }
            int newDamage = meta.getDamage() + 1;
            if (newDamage >= item.getType().getMaxDurability()) {
                armor[i] = new ItemStack(Material.AIR);
            } else {
                meta.setDamage(newDamage);
                item.setItemMeta(meta);
                armor[i] = item;
            }
        }
        if (hasLeather) {
            player.getInventory().setArmorContents(armor);
        }
        return hasLeather;
    }

    private boolean isLeatherArmor(Material material) {
        return switch (material) {
            case LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS -> true;
            default -> false;
        };
    }

    private boolean shouldCheckDome() {
        int interval = Math.max(1, config.getInt("meteor.dome.check-interval-seconds", 60));
        if (domeCheckCounter <= 0) {
            domeCheckCounter = interval;
            return true;
        }
        domeCheckCounter--;
        return false;
    }

    private boolean isDomeComplete(World world) {
        int radius = config.getInt("meteor.dome.radius", 15);
        Set<Material> glassTypes = loadGlassTypes();
        for (Vector offset : getDomeOffsets(radius)) {
            int x = origin.getBlockX() + offset.getBlockX();
            int y = origin.getBlockY() + offset.getBlockY();
            int z = origin.getBlockZ() + offset.getBlockZ();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Material material = world.getBlockAt(x, y, z).getType();
            if (!glassTypes.contains(material)) {
                return false;
            }
        }
        return true;
    }

    private List<Vector> getDomeOffsets(int radius) {
        if (domeOffsets != null) {
            return domeOffsets;
        }
        List<Vector> offsets = new ArrayList<>();
        double min = radius - 0.5;
        double max = radius + 0.5;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    double distance = Math.sqrt(x * x + y * y + z * z);
                    if (distance >= min && distance <= max) {
                        offsets.add(new Vector(x, y, z));
                    }
                }
            }
        }
        domeOffsets = offsets;
        return offsets;
    }

    private Set<Material> loadGlassTypes() {
        List<String> names = config.getStringList("meteor.dome.glass-types");
        Set<Material> types = new HashSet<>();
        for (String name : names) {
            try {
                types.add(Material.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // ignore
            }
        }
        for (Material material : Material.values()) {
            if (material.isBlock() && material.name().contains("GLASS")) {
                types.add(material);
            }
        }
        if (types.isEmpty()) {
            types.addAll(defaultGlassTypes());
        }
        return types;
    }

    private Set<Material> defaultGlassTypes() {
        Set<Material> types = new HashSet<>(EnumSet.of(
            Material.GLASS,
            Material.TINTED_GLASS,
            Material.GLASS_PANE,
            Material.WHITE_STAINED_GLASS,
            Material.LIGHT_GRAY_STAINED_GLASS,
            Material.GRAY_STAINED_GLASS,
            Material.BLACK_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS,
            Material.CYAN_STAINED_GLASS,
            Material.GREEN_STAINED_GLASS,
            Material.LIME_STAINED_GLASS,
            Material.BROWN_STAINED_GLASS,
            Material.ORANGE_STAINED_GLASS,
            Material.YELLOW_STAINED_GLASS,
            Material.RED_STAINED_GLASS,
            Material.PURPLE_STAINED_GLASS,
            Material.MAGENTA_STAINED_GLASS,
            Material.PINK_STAINED_GLASS
        ));
        for (Material material : Material.values()) {
            if (material.isBlock() && material.name().endsWith("_STAINED_GLASS_PANE")) {
                types.add(material);
            }
        }
        return types;
    }
}
