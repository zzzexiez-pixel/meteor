package com.example.meteor.world;

import com.example.meteor.data.ResearchRepository;
import com.example.meteor.util.ConfigHelper;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.plugin.Plugin;

import java.util.*;

public class MeteorManager {
    private final Plugin plugin;
    private final ResearchRepository researchRepository;
    private BukkitTask countdownTask;
    private BukkitTask flightTask;
    private RadiationZone radiationZone;
    private Location impactLocation;
    private boolean running;

    public MeteorManager(Plugin plugin, ResearchRepository researchRepository) {
        this.plugin = plugin;
        this.researchRepository = researchRepository;
    }

    public boolean isRunning() {
        return running;
    }

    public Location getImpactLocation() {
        return impactLocation;
    }

    public void startMeteor(CommandSender sender, Integer x, Integer z) {
        startMeteor(sender, x, z, false);
    }

    public void startMeteor(CommandSender sender, Integer x, Integer z, boolean immediate) {
        if (running) {
            sender.sendMessage("§cМетеорит уже запущен.");
            return;
        }
        World world = ConfigHelper.defaultWorld();
        if (world == null) {
            sender.sendMessage("§cМир не найден.");
            return;
        }
        Location spawn = world.getSpawnLocation();
        Random random = new Random();
        int targetX = x != null ? x : spawn.getBlockX() + random.nextInt(401) - 200;
        int targetZ = z != null ? z : spawn.getBlockZ() + random.nextInt(401) - 200;
        int targetY = world.getHighestBlockYAt(targetX, targetZ) + 1;
        impactLocation = new Location(world, targetX + 0.5, targetY, targetZ + 0.5);
        running = true;

        FileConfiguration config = plugin.getConfig();
        int countdownMinutes = config.getInt("meteor.countdown-minutes", 60);
        List<Integer> reminders = config.getIntegerList("meteor.reminder-times-minutes");
        String title = config.getString("meteor.warning-title", "§6☄ Метеорит приближается!");
        String subtitleTemplate = config.getString("meteor.warning-subtitle", "Падение через %time% X:%x% Z:%z%");

        if (immediate) {
            sender.sendMessage("§aМетеорит запущен принудительно.");
            startFlight();
            return;
        }

        countdownTask = new BukkitRunnable() {
            int minutesLeft = countdownMinutes;

            @Override
            public void run() {
                if (!running) {
                    cancel();
                    return;
                }
                if (minutesLeft <= 0) {
                    cancel();
                    startFlight();
                    return;
                }
                if (reminders.contains(minutesLeft)) {
                    String subtitle = subtitleTemplate
                        .replace("%time%", minutesLeft + "м")
                        .replace("%x%", String.valueOf(targetX))
                        .replace("%z%", String.valueOf(targetZ));
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendTitle(title, subtitle, 10, 60, 10);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.6f);
                    }
                    Bukkit.broadcastMessage("§6☄ Метеорит через " + minutesLeft + "м. Координаты: X:" + targetX + " Z:" + targetZ);
                }
                minutesLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L * 60);

        sender.sendMessage("§aМетеорит запущен. Падение через " + countdownMinutes + "м.");
    }

    public void stopMeteor() {
        running = false;
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (flightTask != null) {
            flightTask.cancel();
            flightTask = null;
        }
        if (radiationZone != null) {
            radiationZone.stop();
            radiationZone = null;
        }
    }

    private void startFlight() {
        FileConfiguration config = plugin.getConfig();
        World world = impactLocation.getWorld();
        if (world == null) {
            stopMeteor();
            return;
        }
        int height = config.getInt("meteor.flight.height", 200);
        int durationSeconds = config.getInt("meteor.flight.duration-seconds", 10);
        List<Particle> trailParticles = ConfigHelper.readParticles(config, "meteor.flight.trail-particles");
        Sound whistle = ConfigHelper.safeSound(config.getString("meteor.flight.whistle-sound"), Sound.ENTITY_ARROW_SHOOT);
        double startY = impactLocation.getY() + height;
        Location start = impactLocation.clone();
        start.set(impactLocation.getX(), startY, impactLocation.getZ());

        ArmorStand meteor = (ArmorStand) world.spawnEntity(start, EntityType.ARMOR_STAND);
        meteor.setInvisible(true);
        meteor.setMarker(true);
        meteor.setGravity(false);
        meteor.getEquipment().setHelmet(new ItemStack(Material.MAGMA_BLOCK));

        double totalTicks = durationSeconds * 20.0;
        Vector step = new Vector(0, -(height / totalTicks), 0);

        flightTask = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!running || meteor.isDead()) {
                    meteor.remove();
                    cancel();
                    return;
                }
                if (tick == 0) {
                    world.playSound(meteor.getLocation(), whistle, 3.0f, 0.7f);
                }
                if (tick >= totalTicks) {
                    meteor.remove();
                    cancel();
                    impact();
                    return;
                }
                Location current = meteor.getLocation().add(step);
                meteor.teleport(current);
                for (Particle particle : trailParticles) {
                    world.spawnParticle(particle, current, 12, 0.3, 0.3, 0.3, 0.02);
                }
                world.spawnParticle(Particle.LAVA, current, 4, 0.2, 0.2, 0.2, 0.01);
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void impact() {
        FileConfiguration config = plugin.getConfig();
        World world = impactLocation.getWorld();
        if (world == null) {
            stopMeteor();
            return;
        }
        Sound impactSound = ConfigHelper.safeSound(config.getString("meteor.impact.sound"), Sound.ENTITY_GENERIC_EXPLODE);
        double explosionPower = config.getDouble("meteor.impact.explosion-power", 10.0);
        boolean explosionFire = config.getBoolean("meteor.impact.explosion-fire", true);
        boolean explosionBreak = config.getBoolean("meteor.impact.explosion-break-blocks", true);

        world.playSound(impactLocation, impactSound, 4.0f, 0.6f);
        world.createExplosion(impactLocation.getX(), impactLocation.getY(), impactLocation.getZ(), (float) explosionPower, explosionFire, explosionBreak);

        int flashCount = config.getInt("meteor.impact.flash-count", 8);
        double flashRadius = config.getDouble("meteor.impact.flash-radius", 60.0);
        int blindnessTicks = config.getInt("meteor.impact.flash-blindness-ticks", 20);
        for (int i = 0; i < flashCount; i++) {
            int delay = i * 6;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                world.strikeLightningEffect(impactLocation);
                world.spawnParticle(Particle.FLASH, impactLocation, 1);
                for (Player player : world.getPlayers()) {
                    if (player.getLocation().distanceSquared(impactLocation) <= flashRadius * flashRadius) {
                        player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.BLINDNESS,
                            blindnessTicks,
                            1,
                            true,
                            true,
                            true
                        ));
                    }
                }
            }, delay);
        }

        triggerShockwave(config, world);
        createCrater(config, world);
        shakePlayers(config, world);

        placeCoreBlock(config, world);
        startRadiation(config);

        researchRepository.addEntry(
            world.getName(),
            impactLocation.getBlockX(),
            impactLocation.getBlockY(),
            impactLocation.getBlockZ(),
            "Метеорит с мощным радиационным ядром. Образован кратер и зона поражения."
        );

        Bukkit.broadcastMessage("§4☄ Метеорит упал! Радиоактивная зона сформирована.");
    }

    private void triggerShockwave(FileConfiguration config, World world) {
        int steps = config.getInt("meteor.impact.shockwave-steps", 12);
        int points = config.getInt("meteor.impact.shockwave-points", 40);
        double radius = config.getDouble("meteor.impact.shockwave-radius", 18.0);
        Particle spark = ConfigHelper.safeParticle("FIREWORKS_SPARK");
        if (spark == null) {
            spark = ConfigHelper.safeParticle("FIREWORK");
        }
        final Particle sparkEffect = spark;
        for (int step = 0; step < steps; step++) {
            double currentRadius = radius * ((double) step / steps);
            int delay = step * 4;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (int i = 0; i < points; i++) {
                    double angle = (2 * Math.PI / points) * i;
                    double x = impactLocation.getX() + Math.cos(angle) * currentRadius;
                    double z = impactLocation.getZ() + Math.sin(angle) * currentRadius;
                    Location particleLoc = new Location(world, x, impactLocation.getY() + 0.2, z);
                    world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, particleLoc, 3, 0.05, 0.05, 0.05, 0.0);
                    if (sparkEffect != null) {
                        world.spawnParticle(sparkEffect, particleLoc, 4, 0.1, 0.1, 0.1, 0.02);
                    }
                }
            }, delay);
        }
    }

    private void createCrater(FileConfiguration config, World world) {
        int craterSize = config.getInt("meteor.impact.crater-size", 9);
        List<String> materials = config.getStringList("meteor.impact.crater-materials");
        List<Material> craterMaterials = new ArrayList<>();
        for (String material : materials) {
            try {
                craterMaterials.add(Material.valueOf(material));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown crater material: " + material);
            }
        }
        if (craterMaterials.isEmpty()) {
            craterMaterials.add(Material.OBSIDIAN);
        }

        int centerX = impactLocation.getBlockX();
        int centerY = impactLocation.getBlockY();
        int centerZ = impactLocation.getBlockZ();

        for (int x = -craterSize; x <= craterSize; x++) {
            for (int z = -craterSize; z <= craterSize; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist > craterSize) {
                    continue;
                }
                int depth = (int) Math.max(1, craterSize - dist * 0.8);
                for (int y = 0; y < depth; y++) {
                    Block block = world.getBlockAt(centerX + x, centerY - y, centerZ + z);
                    if (block.getType() == Material.BEDROCK) {
                        continue;
                    }
                    block.setType(craterMaterials.get((x * 31 + z * 17 + y) & (craterMaterials.size() - 1)));
                }
            }
        }
    }

    private void shakePlayers(FileConfiguration config, World world) {
        double radius = config.getDouble("meteor.impact.shake-radius", 50.0);
        int duration = config.getInt("meteor.impact.shake-duration-ticks", 60);
        double intensity = config.getDouble("meteor.impact.shake-intensity", 6.0);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(impactLocation) <= radius * radius) {
                Vector knock = player.getLocation().toVector().subtract(impactLocation.toVector()).normalize().multiply(intensity / 10.0).setY(0.6);
                player.setVelocity(knock);
                player.sendMessage("§cУдарная волна сбила с ног!");
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(impactLocation) <= radius * radius) {
                    player.spawnParticle(Particle.ASH, player.getLocation(), 20, 0.5, 0.5, 0.5, 0.02);
                }
            }
        }, duration);
    }

    private void placeCoreBlock(FileConfiguration config, World world) {
        String blockMaterial = config.getString("meteor.core.block-material", "CRYING_OBSIDIAN");
        Material material;
        try {
            material = Material.valueOf(blockMaterial);
        } catch (IllegalArgumentException e) {
            material = Material.CRYING_OBSIDIAN;
        }
        Block coreBlock = world.getBlockAt(impactLocation);
        coreBlock.setType(material);
        world.spawnParticle(Particle.END_ROD, impactLocation, 40, 1.0, 1.0, 1.0, 0.02);
    }

    private void startRadiation(FileConfiguration config) {
        if (radiationZone != null) {
            radiationZone.stop();
        }
        radiationZone = new RadiationZone(plugin, impactLocation, config);
        radiationZone.start();
    }
}
