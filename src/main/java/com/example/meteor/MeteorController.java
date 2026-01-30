package com.example.meteor;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class MeteorController {
    private final MeteorPlugin plugin;
    private BukkitRunnable activeTask;
    private UUID armorStandId;

    public MeteorController(MeteorPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(Location target) {
        stop();

        FileConfiguration config = plugin.getConfig();
        World world = target.getWorld();
        if (world == null) {
            return;
        }

        int height = config.getInt("meteor.height", 60);
        int duration = config.getInt("meteor.fall-duration-ticks", 80);
        double radius = config.getDouble("meteor.radius", 2.0);
        Material blockMaterial = Material.matchMaterial(config.getString("meteor.block", "NETHERITE_BLOCK"));
        if (blockMaterial == null) {
            blockMaterial = Material.NETHERITE_BLOCK;
        }

        Location startLocation = target.clone().add(0, height, 0);
        ArmorStand stand = (ArmorStand) world.spawnEntity(startLocation, EntityType.ARMOR_STAND);
        stand.setInvisible(true);
        stand.setMarker(true);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.getEquipment().setHelmet(createMeteorHelmet(blockMaterial));
        armorStandId = stand.getUniqueId();

        List<Particle> trailParticles = resolveParticles(config.getStringList("meteor.trail-particles"));
        List<Particle> impactParticles = resolveParticles(config.getStringList("meteor.impact-particles"));
        Sound impactSound = resolveSound(config.getString("meteor.impact-sound", "ENTITY_GENERIC_EXPLODE"));
        float impactPower = (float) config.getDouble("meteor.impact-power", 0.0);

        Vector step = target.toVector().subtract(startLocation.toVector()).multiply(1.0 / duration);

        activeTask = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!stand.isValid()) {
                    cancel();
                    return;
                }

                if (ticks >= duration) {
                    onImpact(target, impactParticles, impactSound, impactPower, radius);
                    stand.remove();
                    cancel();
                    return;
                }

                Location current = stand.getLocation().add(step);
                stand.teleport(current);
                spawnTrailParticles(world, current, radius, trailParticles);
                ticks++;
            }

            @Override
            public void cancel() {
                super.cancel();
                activeTask = null;
                armorStandId = null;
            }
        };
        activeTask.runTaskTimer(plugin, 0L, 1L);
    }

    public void stop() {
        if (activeTask != null) {
            activeTask.cancel();
            activeTask = null;
        }
        if (armorStandId != null) {
            var entity = plugin.getServer().getEntity(armorStandId);
            if (entity != null) {
                entity.remove();
            }
            armorStandId = null;
        }
    }

    private void onImpact(Location target, List<Particle> impactParticles, Sound impactSound, float power, double radius) {
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        for (Particle particle : impactParticles) {
            world.spawnParticle(particle, target, 120, radius, radius, radius, 0.2);
        }
        world.playSound(target, impactSound, 1.8f, 0.7f);
        world.createExplosion(target, power, false, false);
    }

    private void spawnTrailParticles(World world, Location location, double radius, List<Particle> particles) {
        for (Particle particle : particles) {
            world.spawnParticle(particle, location, 8, radius * 0.5, radius * 0.5, radius * 0.5, 0.01);
        }
        Particle dustParticle = resolveParticle("REDSTONE", "DUST");
        if (dustParticle != null) {
            world.spawnParticle(
                dustParticle,
                location,
                2,
                0.2,
                0.2,
                0.2,
                new Particle.DustOptions(Color.ORANGE, 1.6f)
            );
        }
    }

    private ItemStack createMeteorHelmet(Material material) {
        ItemStack stack = new ItemStack(material);
        if (material == Material.LEATHER_HELMET) {
            LeatherArmorMeta meta = (LeatherArmorMeta) stack.getItemMeta();
            meta.setColor(Color.ORANGE);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<Particle> resolveParticles(List<String> names) {
        return names.stream()
            .map(name -> {
                try {
                    return Particle.valueOf(name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private Particle resolveParticle(String... names) {
        for (String name : names) {
            try {
                return Particle.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                // Ignore invalid particle names.
            }
        }
        return null;
    }

    private Sound resolveSound(String name) {
        try {
            return Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Sound.ENTITY_GENERIC_EXPLODE;
        }
    }
}
