package com.example.meteor;

import java.util.ArrayList;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

public class MeteorController {
    private final MeteorPlugin plugin;
    private BukkitRunnable activeTask;
    private UUID armorStandId;
    private final List<UUID> displayIds = new ArrayList<>();

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

        spawnMeteorDisplays(world, startLocation, blockMaterial);

        List<Particle> trailParticles = resolveParticles(config.getStringList("meteor.trail-particles"));
        List<Particle> impactParticles = resolveParticles(config.getStringList("meteor.impact-particles"));
        Sound impactSound = resolveSound(config.getString("meteor.impact-sound", "ENTITY_GENERIC_EXPLODE"));
        float impactPower = (float) config.getDouble("meteor.impact-power", 12.0);
        float explosionRadius = (float) config.getDouble("meteor.explosion-radius", 100.0);
        Sound fallingSound = resolveSound(config.getString("meteor.falling-sound", "ENTITY_PHANTOM_FLAP"));

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
                    onImpact(target, impactParticles, impactSound, impactPower, radius, explosionRadius);
                    stand.remove();
                    removeDisplays();
                    cancel();
                    return;
                }

                Location current = stand.getLocation().add(step);
                stand.teleport(current);
                moveDisplays(current);
                spawnTrailParticles(world, current, radius, trailParticles);
                applyScreenShake(world, current, explosionRadius);
                if (ticks % 10 == 0) {
                    world.playSound(current, fallingSound, 1.2f, 0.8f);
                }
                if (ticks % 4 == 0) {
                    applyFlashBlindness(world, current, stand, explosionRadius);
                }
                ticks++;
            }

            @Override
            public void cancel() {
                super.cancel();
                activeTask = null;
                armorStandId = null;
                removeDisplays();
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
        removeDisplays();
    }

    private void onImpact(
        Location target,
        List<Particle> impactParticles,
        Sound impactSound,
        float power,
        double radius,
        float explosionRadius
    ) {
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        for (Particle particle : impactParticles) {
            world.spawnParticle(particle, target, 120, radius, radius, radius, 0.2);
        }
        world.spawnParticle(Particle.EXPLOSION_HUGE, target, 4, 1.5, 1.5, 1.5, 0.1);
        world.spawnParticle(Particle.FLASH, target, 6, 1.0, 1.0, 1.0, 0.0);
        world.playSound(target, impactSound, 2.2f, 0.65f);
        world.createExplosion(target, power, false, true);
        applyRadiation(target, explosionRadius);
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
        world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, location, 2, radius * 0.3, 0.5, radius * 0.3, 0.02);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, location, 4, radius * 0.4, 0.4, radius * 0.4, 0.01);
    }

    private void spawnMeteorDisplays(World world, Location location, Material material) {
        List<BlockDisplay> displays = new ArrayList<>();
        displays.add(createBlockDisplay(world, location, material, 1.0f));
        displays.add(createBlockDisplay(world, location.clone().add(0.6, 0.2, 0.3), material, 0.7f));
        displays.add(createBlockDisplay(world, location.clone().add(-0.5, -0.1, -0.4), material, 0.6f));
        for (BlockDisplay display : displays) {
            display.setGlowing(true);
        }
        displayIds.clear();
        for (BlockDisplay display : displays) {
            displayIds.add(display.getUniqueId());
        }
    }

    private BlockDisplay createBlockDisplay(World world, Location location, Material material, float scale) {
        BlockDisplay display = (BlockDisplay) world.spawnEntity(location, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setShadowRadius(scale * 0.4f);
        display.setShadowStrength(0.6f);
        display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
        Transformation transformation = display.getTransformation();
        transformation.getScale().set(new Vector3f(scale, scale, scale));
        display.setTransformation(transformation);
        return display;
    }

    private void moveDisplays(Location location) {
        if (displayIds.isEmpty()) {
            return;
        }
        for (UUID id : displayIds) {
            var entity = plugin.getServer().getEntity(id);
            if (entity instanceof BlockDisplay display) {
                display.teleport(location);
            }
        }
    }

    private void removeDisplays() {
        for (UUID id : displayIds) {
            var entity = plugin.getServer().getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        displayIds.clear();
    }

    private void applyScreenShake(World world, Location location, double radius) {
        double shakeRadius = plugin.getConfig().getDouble("meteor.shake-radius", Math.min(40.0, radius));
        int duration = plugin.getConfig().getInt("meteor.shake-duration-ticks", 20);
        int amplifier = plugin.getConfig().getInt("meteor.shake-amplifier", 0);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) > shakeRadius * shakeRadius) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, duration, amplifier, true, false, false));
        }
    }

    private void applyFlashBlindness(World world, Location location, ArmorStand stand, double radius) {
        double flashRadius = plugin.getConfig().getDouble("meteor.flash-radius", radius);
        int duration = plugin.getConfig().getInt("meteor.flash-duration-ticks", 60);
        double dotThreshold = plugin.getConfig().getDouble("meteor.flash-dot-threshold", 0.85);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) > flashRadius * flashRadius) {
                continue;
            }
            if (!player.hasLineOfSight(stand)) {
                continue;
            }
            Vector toMeteor = location.clone().add(0, 1.0, 0).toVector().subtract(player.getEyeLocation().toVector()).normalize();
            Vector view = player.getEyeLocation().getDirection().normalize();
            if (view.dot(toMeteor) < dotThreshold) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, duration, 1, true, false, true));
        }
    }

    private void applyRadiation(Location target, double radius) {
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        FileConfiguration config = plugin.getConfig();
        int durationSeconds = config.getInt("meteor.radiation.duration-seconds", 12);
        double maxDamage = config.getDouble("meteor.radiation.max-damage", 10.0);
        double minDamage = config.getDouble("meteor.radiation.min-damage", 1.0);
        double leatherReduction = config.getDouble("meteor.radiation.leather-reduction", 0.15);
        int durabilityLoss = config.getInt("meteor.radiation.armor-durability-loss", 1);
        double radiusSquared = radius * radius;

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (ticks >= durationSeconds) {
                    cancel();
                    return;
                }
                for (Player player : world.getPlayers()) {
                    double distanceSquared = player.getLocation().distanceSquared(target);
                    if (distanceSquared > radiusSquared) {
                        continue;
                    }
                    double distance = Math.sqrt(distanceSquared);
                    double intensity = 1.0 - Math.min(distance / radius, 1.0);
                    double damage = minDamage + (maxDamage - minDamage) * intensity;
                    double reduction = calculateLeatherReduction(player, leatherReduction);
                    if (reduction > 0) {
                        damage *= Math.max(0.0, 1.0 - reduction);
                        damageLeatherArmor(player, durabilityLoss);
                    }
                    player.damage(damage);
                    player.spawnParticle(Particle.SPELL_MOB_AMBIENT, player.getLocation().add(0, 1.0, 0), 8, 0.4, 0.6, 0.4, 0.01);
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private double calculateLeatherReduction(Player player, double reductionPerPiece) {
        int pieces = 0;
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack != null && stack.getType().name().startsWith("LEATHER_")) {
                pieces++;
            }
        }
        return Math.min(1.0, pieces * reductionPerPiece);
    }

    private void damageLeatherArmor(Player player, int durabilityLoss) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            ItemStack stack = armor[i];
            if (stack == null || !stack.getType().name().startsWith("LEATHER_")) {
                continue;
            }
            if (!(stack.getItemMeta() instanceof Damageable damageable)) {
                continue;
            }
            int newDamage = damageable.getDamage() + durabilityLoss;
            if (newDamage >= stack.getType().getMaxDurability()) {
                armor[i] = null;
                continue;
            }
            damageable.setDamage(newDamage);
            stack.setItemMeta(damageable);
        }
        player.getInventory().setArmorContents(armor);
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
