package com.example.meteor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
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
import org.joml.Quaternionf;

public class MeteorController {
    private final MeteorPlugin plugin;
    private BukkitRunnable activeTask;
    private UUID armorStandId;
    private final List<DisplayShard> displayShards = new ArrayList<>();
    private final Random random = new Random();

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
        double horizontalOffset = config.getDouble("meteor.horizontal-offset", 20.0);
        Material blockMaterial = Material.matchMaterial(config.getString("meteor.block", "NETHERITE_BLOCK"));
        if (blockMaterial == null) {
            blockMaterial = Material.NETHERITE_BLOCK;
        }

        double offsetX = 0.0;
        double offsetZ = 0.0;
        if (horizontalOffset > 0.0) {
            offsetX = (random.nextDouble() * 2.0 - 1.0) * horizontalOffset;
            offsetZ = (random.nextDouble() * 2.0 - 1.0) * horizontalOffset;
        }
        Location startLocation = target.clone().add(offsetX, height, offsetZ);
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
        int destructionRadius = config.getInt("meteor.block-destruction-radius", 50);
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
                    onImpact(target, impactParticles, impactSound, impactPower, radius, explosionRadius, destructionRadius);
                    stand.remove();
                    removeDisplays();
                    cancel();
                    return;
                }

                Location current = stand.getLocation().add(step);
                stand.teleport(current);
                moveDisplays(current, ticks);
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
        float explosionRadius,
        int destructionRadius
    ) {
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        for (Particle particle : impactParticles) {
            world.spawnParticle(particle, target, 120, radius, radius, radius, 0.2);
        }
        Particle explosionParticle = resolveParticle("EXPLOSION_HUGE", "EXPLOSION_LARGE", "EXPLOSION");
        if (explosionParticle != null) {
            world.spawnParticle(explosionParticle, target, 4, 1.5, 1.5, 1.5, 0.1);
        }
        Particle flashParticle = resolveParticle("FLASH", "EXPLOSION_NORMAL", "EXPLOSION");
        if (flashParticle != null) {
            world.spawnParticle(flashParticle, target, 6, 1.0, 1.0, 1.0, 0.0);
        }
        world.playSound(target, impactSound, 2.2f, 0.65f);
        world.createExplosion(target, power, false, true);
        destroyBlocks(target, destructionRadius);
        createImpactRuins(target);
        applyImpactShake(world, target);
        applyRadiation(target, explosionRadius);
    }

    private void destroyBlocks(Location target, int radius) {
        if (radius <= 0) {
            return;
        }
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        int intRadius = Math.max(1, radius);
        int radiusSquared = intRadius * intRadius;
        int originX = target.getBlockX();
        int originY = target.getBlockY();
        int originZ = target.getBlockZ();
        for (int x = -intRadius; x <= intRadius; x++) {
            int xSquared = x * x;
            for (int y = -intRadius; y <= intRadius; y++) {
                int xySquared = xSquared + y * y;
                if (xySquared > radiusSquared) {
                    continue;
                }
                for (int z = -intRadius; z <= intRadius; z++) {
                    if (xySquared + z * z > radiusSquared) {
                        continue;
                    }
                    var block = world.getBlockAt(originX + x, originY + y, originZ + z);
                    if (block.getType() == Material.BEDROCK || block.getType().isAir()) {
                        continue;
                    }
                    block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void spawnTrailParticles(World world, Location location, double radius, List<Particle> particles) {
        for (Particle particle : particles) {
            world.spawnParticle(particle, location, 8, radius * 0.5, radius * 0.5, radius * 0.5, 0.01);
        }
        Particle ashParticle = resolveParticle("ASH", "SMOKE_NORMAL");
        if (ashParticle != null) {
            world.spawnParticle(ashParticle, location, 12, radius * 0.6, 0.6, radius * 0.6, 0.01);
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
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, location, 6, radius * 0.5, 0.5, radius * 0.5, 0.01);
        world.spawnParticle(Particle.LAVA, location, 2, radius * 0.2, 0.2, radius * 0.2, 0.01);
        Particle sparks = resolveParticle("FIREWORKS_SPARK", "FIREWORK", "CRIT");
        if (sparks != null) {
            world.spawnParticle(sparks, location, 10, radius * 0.4, 0.4, radius * 0.4, 0.05);
        }
    }

    private void spawnMeteorDisplays(World world, Location location, Material material) {
        displayShards.clear();
        List<Vector> offsets = List.of(
            new Vector(0.0, 0.0, 0.0),
            new Vector(0.7, 0.2, 0.4),
            new Vector(-0.6, -0.1, -0.5),
            new Vector(0.3, -0.5, 0.6),
            new Vector(-0.8, 0.3, 0.2),
            new Vector(0.4, 0.6, -0.3),
            new Vector(-0.4, 0.5, 0.7),
            new Vector(0.9, -0.3, -0.2)
        );
        for (int i = 0; i < offsets.size(); i++) {
            float scale = 1.0f - (i * 0.08f);
            BlockDisplay display = createBlockDisplay(world, location.clone().add(offsets.get(i)), material, scale);
            display.setGlowing(true);
            displayShards.add(new DisplayShard(
                display.getUniqueId(),
                offsets.get(i),
                0.06f + (random.nextFloat() * 0.04f),
                0.12f + (random.nextFloat() * 0.1f),
                0.12f + (random.nextFloat() * 0.08f),
                random.nextInt(360)
            ));
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
        transformation.getLeftRotation().set(new Quaternionf().rotationXYZ(
            random.nextFloat(),
            random.nextFloat(),
            random.nextFloat()
        ));
        display.setTransformation(transformation);
        return display;
    }

    private void moveDisplays(Location location, int ticks) {
        if (displayShards.isEmpty()) {
            return;
        }
        for (DisplayShard shard : displayShards) {
            var entity = plugin.getServer().getEntity(shard.id());
            if (entity instanceof BlockDisplay display) {
                double wobble = Math.sin((ticks + shard.phase()) * shard.wobbleSpeed()) * shard.wobbleAmplitude();
                Location adjusted = location.clone().add(
                    shard.offset().getX(),
                    shard.offset().getY() + wobble,
                    shard.offset().getZ()
                );
                display.teleport(adjusted);
                float angle = (ticks + shard.phase()) * shard.spinSpeed();
                Transformation transformation = display.getTransformation();
                transformation.getLeftRotation().set(new Quaternionf().rotationXYZ(angle, angle * 0.7f, angle * 0.4f));
                display.setTransformation(transformation);
            }
        }
    }

    private void removeDisplays() {
        for (DisplayShard shard : displayShards) {
            var entity = plugin.getServer().getEntity(shard.id());
            if (entity != null) {
                entity.remove();
            }
        }
        displayShards.clear();
    }

    private void applyScreenShake(World world, Location location, double radius) {
        double shakeRadius = plugin.getConfig().getDouble("meteor.shake-radius", Math.min(40.0, radius));
        int duration = plugin.getConfig().getInt("meteor.shake-duration-ticks", 20);
        int amplifier = plugin.getConfig().getInt("meteor.shake-amplifier", 0);
        PotionEffectType shakeEffect = resolvePotionEffectType("CONFUSION", "NAUSEA");
        if (shakeEffect == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) > shakeRadius * shakeRadius) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(shakeEffect, duration, amplifier, true, false, false));
        }
    }

    private void applyImpactShake(World world, Location location) {
        FileConfiguration config = plugin.getConfig();
        double shakeRadius = config.getDouble("meteor.impact-shake-radius", 80.0);
        int duration = config.getInt("meteor.impact-shake-duration-ticks", 60);
        int amplifier = config.getInt("meteor.impact-shake-amplifier", 1);
        PotionEffectType shakeEffect = resolvePotionEffectType("CONFUSION", "NAUSEA");
        if (shakeEffect == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) > shakeRadius * shakeRadius) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(shakeEffect, duration, amplifier, true, false, false));
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
                Particle radiationParticle = resolveParticle("SPELL_MOB_AMBIENT", "SPELL_MOB", "AMBIENT_ENTITY_EFFECT");
                if (radiationParticle != null) {
                    world.spawnParticle(radiationParticle, target, 12, 0.6, 0.8, 0.6, 0.02);
                }
                Particle coreParticle = resolveParticle("REDSTONE", "DUST");
                if (coreParticle != null) {
                    world.spawnParticle(
                        coreParticle,
                        target.clone().add(0, 0.8, 0),
                        8,
                        0.35,
                        0.35,
                        0.35,
                        new Particle.DustOptions(Color.LIME, 1.4f)
                    );
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
                    if (radiationParticle != null) {
                        player.spawnParticle(
                            radiationParticle,
                            player.getLocation().add(0, 1.0, 0),
                            8,
                            0.4,
                            0.6,
                            0.4,
                            0.01
                        );
                    }
                }
                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void createImpactRuins(Location target) {
        World world = target.getWorld();
        if (world == null) {
            return;
        }
        FileConfiguration config = plugin.getConfig();
        int ruinRadius = config.getInt("meteor.ruin-radius", 12);
        int craterRadius = config.getInt("meteor.crater-radius", 6);
        int craterDepth = config.getInt("meteor.crater-depth", 3);
        int bedrockRadius = config.getInt("meteor.bedrock-radius", 1);
        List<Material> ruinMaterials = resolveMaterials(
            config.getStringList("meteor.ruin-blocks"),
            List.of(Material.NETHERRACK, Material.COBBLESTONE, Material.MAGMA_BLOCK, Material.BLACKSTONE)
        );

        for (int x = -ruinRadius; x <= ruinRadius; x++) {
            for (int z = -ruinRadius; z <= ruinRadius; z++) {
                double distance = Math.sqrt(x * x + z * z);
                Location base = target.clone().add(x, 0, z);
                if (distance <= bedrockRadius) {
                    world.getBlockAt(base).setType(Material.BEDROCK, false);
                    continue;
                }
                if (distance <= craterRadius) {
                    carveCraterColumn(world, base, craterDepth);
                    continue;
                }
                if (distance <= ruinRadius && random.nextDouble() < 0.35) {
                    Material chosen = ruinMaterials.get(random.nextInt(ruinMaterials.size()));
                    world.getBlockAt(base).setType(chosen, false);
                    if (random.nextDouble() < 0.2) {
                        world.getBlockAt(base.clone().add(0, 1, 0)).setType(chosen, false);
                    }
                }
            }
        }
    }

    private void carveCraterColumn(World world, Location base, int depth) {
        for (int y = 0; y <= depth; y++) {
            world.getBlockAt(base.clone().add(0, -y, 0)).setType(Material.AIR, false);
        }
        if (random.nextDouble() < 0.4) {
            world.getBlockAt(base.clone().add(0, -depth - 1, 0)).setType(Material.MAGMA_BLOCK, false);
        }
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

    private List<Material> resolveMaterials(List<String> names, List<Material> fallback) {
        List<Material> materials = names.stream()
            .map(name -> Material.matchMaterial(name))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        return materials.isEmpty() ? fallback : materials;
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

    private PotionEffectType resolvePotionEffectType(String... names) {
        for (String name : names) {
            PotionEffectType effectType = PotionEffectType.getByName(name);
            if (effectType != null) {
                return effectType;
            }
        }
        return null;
    }

    private record DisplayShard(
        UUID id,
        Vector offset,
        float spinSpeed,
        float wobbleSpeed,
        float wobbleAmplitude,
        int phase
    ) {}
}
