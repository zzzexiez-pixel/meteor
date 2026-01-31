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
    private boolean freeLaunch;

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
        World world = resolveWorld(sender, immediate);
        if (world == null) {
            sender.sendMessage("§cМир не найден.");
            return;
        }
        Location spawn = ConfigHelper.worldSpawnOrDefault(world);
        Random random = new Random();
        boolean useAdminLocation = immediate && sender instanceof Player && x == null && z == null;
        int targetX = x != null ? x : resolveTargetCoordinate(sender, spawn.getBlockX(), random, immediate, true);
        int targetZ = z != null ? z : resolveTargetCoordinate(sender, spawn.getBlockZ(), random, immediate, false);
        int targetY = world.getHighestBlockYAt(targetX, targetZ) + 1;
        impactLocation = new Location(world, targetX + 0.5, targetY, targetZ + 0.5);
        running = true;
        freeLaunch = immediate;

        FileConfiguration config = plugin.getConfig();
        int countdownMinutes = config.getInt("meteor.countdown-minutes", 60);
        List<Integer> reminders = config.getIntegerList("meteor.reminder-times-minutes");
        String title = config.getString("meteor.warning-title", "§6☄ Метеорит приближается!");
        String subtitleTemplate = config.getString("meteor.warning-subtitle", "Падение через %time% X:%x% Z:%z%");

        if (immediate) {
            sender.sendMessage(useAdminLocation
                ? "§aМетеорит запущен принудительно на ваших координатах."
                : "§aМетеорит запущен принудительно.");
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
        freeLaunch = false;
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
        double bodyRadius = config.getDouble("meteor.flight.body-radius", 2.1);
        double baseHorizontalOffset = config.getDouble("meteor.flight.horizontal-offset", 32.0);
        double freeHorizontalOffset = config.getDouble("meteor.flight.free-horizontal-offset", 52.0);
        List<Particle> trailParticles = ConfigHelper.readParticles(config, "meteor.flight.trail-particles");
        List<Material> bodyMaterials = readMaterials(config.getStringList("meteor.flight.body-materials"), List.of(Material.MAGMA_BLOCK));
        Sound whistle = ConfigHelper.safeSound(config.getString("meteor.flight.whistle-sound"), Sound.ENTITY_ARROW_SHOOT);
        Sound rumble = ConfigHelper.safeSound(config.getString("meteor.flight.rumble-sound"), Sound.ENTITY_PHANTOM_FLAP);
        Sound approach = ConfigHelper.safeSound(config.getString("meteor.flight.approach-sound"), Sound.ENTITY_GHAST_SHOOT);
        double startY = impactLocation.getY() + height;
        Location start = impactLocation.clone();
        double horizontalOffset = freeLaunch ? freeHorizontalOffset : baseHorizontalOffset;
        if (horizontalOffset > 0.1) {
            double angle = Math.random() * Math.PI * 2;
            double offsetX = Math.cos(angle) * horizontalOffset;
            double offsetZ = Math.sin(angle) * horizontalOffset;
            start.add(offsetX, 0, offsetZ);
        }
        start.set(start.getX(), startY, start.getZ());

        List<Vector> meteorOffsets = buildMeteorOffsets(bodyRadius);
        List<ArmorStand> meteorPieces = spawnMeteorPieces(world, start, meteorOffsets, bodyMaterials);
        if (meteorPieces.isEmpty()) {
            impact();
            return;
        }

        double totalTicks = durationSeconds * 20.0;
        Vector step = impactLocation.toVector().subtract(start.toVector()).multiply(1.0 / totalTicks);

        flightTask = new BukkitRunnable() {
            int tick = 0;
            Location base = start.clone();

            @Override
            public void run() {
                if (!running) {
                    removeMeteorPieces(meteorPieces);
                    cancel();
                    return;
                }
                if (meteorPieces.isEmpty()) {
                    cancel();
                    impact();
                    return;
                }
                for (ArmorStand piece : meteorPieces) {
                    if (piece.isDead()) {
                        removeMeteorPieces(meteorPieces);
                        cancel();
                        impact();
                        return;
                    }
                }
                if (tick == 0) {
                    world.playSound(base, whistle, 3.0f, 0.7f);
                }
                if (tick % 10 == 0) {
                    float volume = Math.min(4.0f, 1.2f + (tick / (float) totalTicks) * 2.0f);
                    world.playSound(base, rumble, volume, 0.7f);
                    world.playSound(base, approach, volume * 0.6f, 0.5f);
                }
                if (tick >= totalTicks) {
                    removeMeteorPieces(meteorPieces);
                    cancel();
                    impact();
                    return;
                }
                base = base.add(step);
                teleportMeteorPieces(base, meteorPieces, meteorOffsets);
                for (Particle particle : trailParticles) {
                    world.spawnParticle(particle, base, 18, bodyRadius * 0.3, bodyRadius * 0.3, bodyRadius * 0.3, 0.03);
                }
                spawnFlightSpiral(world, base, tick);
                spawnFlightAura(world, base, tick, bodyRadius);
                spawnFlightEmbers(world, base, bodyRadius);
                world.spawnParticle(Particle.LAVA, base, 10, bodyRadius * 0.2, bodyRadius * 0.2, bodyRadius * 0.2, 0.02);
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
        Sound landingSound = ConfigHelper.safeSound(config.getString("meteor.impact.landing-sound"), Sound.BLOCK_ANVIL_LAND);
        Sound explosionSound = ConfigHelper.safeSound(config.getString("meteor.impact.explosion-sound"), Sound.ENTITY_GENERIC_EXPLODE);
        Sound flashSound = ConfigHelper.safeSound(config.getString("meteor.impact.flash-sound"), Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
        double explosionPower = config.getDouble("meteor.impact.explosion-power", 10.0);
        boolean explosionFire = config.getBoolean("meteor.impact.explosion-fire", true);
        boolean explosionBreak = config.getBoolean("meteor.impact.explosion-break-blocks", true);
        boolean forceBlockBreak = config.getBoolean("meteor.impact.force-block-break", true);

        world.playSound(impactLocation, landingSound, 3.0f, 0.6f);
        world.playSound(impactLocation, impactSound, 4.0f, 0.6f);
        world.playSound(impactLocation, explosionSound, 5.0f, 0.5f);
        applyExplosion(world, explosionPower, explosionFire, explosionBreak, forceBlockBreak);
        spawnImpactResidue(world);

        int flashCount = config.getInt("meteor.impact.flash-count", 8);
        double flashRadius = config.getDouble("meteor.impact.flash-radius", 60.0);
        int blindnessTicks = config.getInt("meteor.impact.flash-blindness-ticks", 20);
        for (int i = 0; i < flashCount; i++) {
            int delay = i * 6;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                world.strikeLightningEffect(impactLocation);
                world.spawnParticle(Particle.FLASH, impactLocation, 1);
                world.spawnParticle(Particle.EXPLOSION, impactLocation, 1);
                world.playSound(impactLocation, flashSound, 2.5f, 1.2f);
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
        igniteCraterRim(config, world);
        launchDebrisBurst(config, world, freeLaunch);
        shakePlayers(config, world);
        applyScreenShake(config, world);

        placeCoreBlock(config, world);
        startRadiation(config);

        researchRepository.addEntry(
            world.getName(),
            impactLocation.getBlockX(),
            impactLocation.getBlockY(),
            impactLocation.getBlockZ(),
            "Метеорит с мощным радиационным ядром. Образован гигантский кратер, вокруг разбросаны обожжённые породы. " +
                "Свидетели сообщают о сияющей хвостовой вуали и раскатах, похожих на песню пустоты."
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

    private void applyExplosion(World world, double power, boolean fire, boolean breakBlocks, boolean forceBlockBreak) {
        if (power <= 0) {
            return;
        }
        boolean exploded = world.createExplosion(impactLocation, (float) power, fire, breakBlocks);
        if (!exploded) {
            Particle fallbackExplosion = ConfigHelper.safeParticle("EXPLOSION_HUGE");
            if (fallbackExplosion == null) {
                fallbackExplosion = ConfigHelper.safeParticle("EXPLOSION_LARGE");
            }
            if (fallbackExplosion != null) {
                world.spawnParticle(fallbackExplosion, impactLocation, 6, 1.5, 1.5, 1.5, 0.05);
            }
        }
        if (breakBlocks && (forceBlockBreak || !exploded)) {
            int radius = Math.max(1, (int) Math.round(power * 2.0));
            breakBlocksInRadius(world, radius);
        }
    }

    private void breakBlocksInRadius(World world, int radius) {
        int centerX = impactLocation.getBlockX();
        int centerY = impactLocation.getBlockY();
        int centerZ = impactLocation.getBlockZ();
        int radiusSquared = radius * radius;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    int distance = x * x + y * y + z * z;
                    if (distance > radiusSquared) {
                        continue;
                    }
                    Block block = world.getBlockAt(centerX + x, centerY + y, centerZ + z);
                    if (block.getType() == Material.BEDROCK) {
                        continue;
                    }
                    block.setType(Material.AIR, false);
                }
            }
        }
    }

    private void createCrater(FileConfiguration config, World world) {
        int craterSize = config.getInt("meteor.impact.crater-size", 15);
        int craterDepth = config.getInt("meteor.impact.crater-depth", craterSize + 6);
        int craterRimHeight = config.getInt("meteor.impact.crater-rim-height", 3);
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
        List<Material> debrisMaterials = readMaterials(
            config.getStringList("meteor.impact.debris-materials"),
            craterMaterials
        );

        int centerX = impactLocation.getBlockX();
        int centerY = impactLocation.getBlockY();
        int centerZ = impactLocation.getBlockZ();
        Random random = new Random();

        for (int x = -craterSize; x <= craterSize; x++) {
            for (int z = -craterSize; z <= craterSize; z++) {
                double dist = Math.sqrt(x * x + z * z);
                double noise = (random.nextDouble() - 0.5) * 2.0;
                double effectiveRadius = craterSize + noise;
                if (dist > effectiveRadius) {
                    continue;
                }
                int depth = (int) Math.max(2, craterDepth - dist * 1.35 + noise * 2.0);
                for (int y = 0; y <= depth; y++) {
                    Block block = world.getBlockAt(centerX + x, centerY - y, centerZ + z);
                    if (block.getType() == Material.BEDROCK) {
                        continue;
                    }
                    block.setType(Material.AIR);
                }
                if (dist >= craterSize - 2.2) {
                    for (int y = 0; y < craterRimHeight; y++) {
                        Block rimBlock = world.getBlockAt(centerX + x, centerY + y, centerZ + z);
                        if (rimBlock.getType() == Material.BEDROCK) {
                            continue;
                        }
                        rimBlock.setType(craterMaterials.get(random.nextInt(craterMaterials.size())));
                    }
                }
                if (dist < craterSize - 1.8) {
                    Block floor = world.getBlockAt(centerX + x, centerY - depth, centerZ + z);
                    if (floor.getType() != Material.BEDROCK) {
                        floor.setType(craterMaterials.get(random.nextInt(craterMaterials.size())));
                    }
                }
            }
        }
        scatterDebris(config, world, debrisMaterials);
        scatterDebrisClusters(config, world, debrisMaterials);
    }

    private void scatterDebris(FileConfiguration config, World world, List<Material> debrisMaterials) {
        int debrisRadius = config.getInt("meteor.impact.debris-radius", 28);
        int debrisCount = config.getInt("meteor.impact.debris-count", 90);
        int maxStack = Math.max(1, config.getInt("meteor.impact.debris-max-stack", 3));
        if (debrisMaterials.isEmpty()) {
            return;
        }
        Random random = new Random();
        for (int i = 0; i < debrisCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = random.nextDouble() * debrisRadius;
            int x = impactLocation.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = impactLocation.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight()) {
                continue;
            }
            int stackHeight = 1 + random.nextInt(maxStack);
            for (int offset = 0; offset < stackHeight; offset++) {
                Block target = world.getBlockAt(x, y + offset, z);
                if (!target.isEmpty()) {
                    break;
                }
                target.setType(debrisMaterials.get(random.nextInt(debrisMaterials.size())));
            }
        }
    }

    private void scatterDebrisClusters(FileConfiguration config, World world, List<Material> debrisMaterials) {
        if (debrisMaterials.isEmpty()) {
            return;
        }
        int clusterCount = config.getInt("meteor.impact.debris-cluster-count", 18);
        int clusterRadius = config.getInt("meteor.impact.debris-cluster-radius", 50);
        int clusterSize = Math.max(4, config.getInt("meteor.impact.debris-cluster-size", 12));
        int clusterHeight = Math.max(1, config.getInt("meteor.impact.debris-cluster-height", 3));
        Random random = new Random();
        for (int cluster = 0; cluster < clusterCount; cluster++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.pow(random.nextDouble(), 0.4) * clusterRadius;
            int centerX = impactLocation.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int centerZ = impactLocation.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int baseY = world.getHighestBlockYAt(centerX, centerZ);
            if (baseY <= world.getMinHeight()) {
                continue;
            }
            int blocksInCluster = clusterSize / 2 + random.nextInt(clusterSize);
            for (int i = 0; i < blocksInCluster; i++) {
                int offsetX = random.nextInt(7) - 3;
                int offsetZ = random.nextInt(7) - 3;
                int x = centerX + offsetX;
                int z = centerZ + offsetZ;
                int topY = world.getHighestBlockYAt(x, z);
                if (topY <= world.getMinHeight()) {
                    continue;
                }
                int height = 1 + random.nextInt(clusterHeight);
                for (int h = 0; h < height; h++) {
                    Block target = world.getBlockAt(x, topY + h, z);
                    if (!target.isEmpty()) {
                        break;
                    }
                    target.setType(debrisMaterials.get(random.nextInt(debrisMaterials.size())));
                }
            }
        }
    }

    private void launchDebrisBurst(FileConfiguration config, World world, boolean freeLaunch) {
        int burstCount = config.getInt("meteor.impact.debris-burst-count", 80);
        double burstRadius = config.getDouble("meteor.impact.debris-burst-radius", 6.0);
        double burstSpeed = config.getDouble("meteor.impact.debris-burst-speed", 0.65);
        double freeSpeedMultiplier = config.getDouble("meteor.impact.free-debris-speed-multiplier", 1.4);
        double freeScatter = config.getDouble("meteor.impact.free-debris-scatter", 0.75);
        List<Material> materials = readMaterials(
            config.getStringList("meteor.impact.debris-materials"),
            List.of(Material.COBBLED_DEEPSLATE, Material.BLACKSTONE)
        );
        if (materials.isEmpty()) {
            return;
        }
        Random random = new Random();
        for (int i = 0; i < burstCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = random.nextDouble() * burstRadius;
            double x = impactLocation.getX() + Math.cos(angle) * distance;
            double z = impactLocation.getZ() + Math.sin(angle) * distance;
            double y = impactLocation.getY() + 1.5 + random.nextDouble() * 2.0;
            Location spawn = new Location(world, x, y, z);
            Material material = materials.get(random.nextInt(materials.size()));
            var falling = world.spawnFallingBlock(spawn, material.createBlockData());
            falling.setDropItem(false);
            Vector velocity = spawn.toVector().subtract(impactLocation.toVector()).normalize();
            if (freeLaunch) {
                Vector scatter = new Vector(random.nextDouble() - 0.5, 0, random.nextDouble() - 0.5);
                if (scatter.lengthSquared() < 0.0001) {
                    scatter = new Vector(1, 0, 0);
                }
                scatter.normalize();
                velocity = velocity.add(scatter.multiply(freeScatter)).normalize();
            }
            velocity = velocity.multiply(burstSpeed * (freeLaunch ? freeSpeedMultiplier : 1.0));
            velocity.setY(0.45 + random.nextDouble() * (freeLaunch ? 0.9 : 0.6));
            falling.setVelocity(velocity);
        }
        world.spawnParticle(Particle.EXPLOSION, impactLocation, 6, 2.0, 1.0, 2.0, 0.02);
        world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, impactLocation, 18, 2.0, 1.2, 2.0, 0.01);
    }

    private void igniteCraterRim(FileConfiguration config, World world) {
        int fireRadius = config.getInt("meteor.impact.fire-radius", 14);
        double fireChance = config.getDouble("meteor.impact.fire-chance", 0.35);
        int magmaChance = config.getInt("meteor.impact.magma-chance-percent", 12);
        int centerX = impactLocation.getBlockX();
        int centerY = impactLocation.getBlockY();
        int centerZ = impactLocation.getBlockZ();
        Random random = new Random();

        for (int x = -fireRadius; x <= fireRadius; x++) {
            for (int z = -fireRadius; z <= fireRadius; z++) {
                double dist = Math.sqrt(x * x + z * z);
                if (dist > fireRadius) {
                    continue;
                }
                int topY = world.getHighestBlockYAt(centerX + x, centerZ + z);
                if (topY <= world.getMinHeight()) {
                    continue;
                }
                Block ground = world.getBlockAt(centerX + x, topY - 1, centerZ + z);
                Block above = world.getBlockAt(centerX + x, topY, centerZ + z);
                if (!above.isEmpty()) {
                    continue;
                }
                if (random.nextDouble() <= fireChance) {
                    above.setType(Material.FIRE);
                } else if (random.nextInt(100) < magmaChance) {
                    ground.setType(Material.MAGMA_BLOCK);
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

    private void applyScreenShake(FileConfiguration config, World world) {
        double radius = config.getDouble("meteor.impact.shake-radius", 50.0);
        int duration = config.getInt("meteor.impact.shake-duration-ticks", 60);
        double intensity = config.getDouble("meteor.impact.shake-intensity", 6.0);
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                for (Player player : world.getPlayers()) {
                    if (player.getLocation().distanceSquared(impactLocation) > radius * radius) {
                        continue;
                    }
                    Location view = player.getLocation().clone();
                    float yawJitter = (float) ((Math.random() - 0.5) * intensity);
                    float pitchJitter = (float) ((Math.random() - 0.5) * intensity);
                    view.setYaw(view.getYaw() + yawJitter);
                    view.setPitch(Math.max(-89.9f, Math.min(89.9f, view.getPitch() + pitchJitter)));
                    player.teleport(view);
                }
                tick += 2;
                if (tick >= duration) {
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
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

    private void spawnFlightSpiral(World world, Location current, int tick) {
        double angle = tick * 0.35;
        double radius = 0.7 + Math.sin(tick * 0.2) * 0.2;
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle) * radius;
        Location spiral = current.clone().add(x, 0.1, z);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, spiral, 6, 0.1, 0.1, 0.1, 0.01);
        world.spawnParticle(Particle.FLAME, spiral, 4, 0.05, 0.05, 0.05, 0.0);
    }

    private void spawnFlightAura(World world, Location current, int tick, double bodyRadius) {
        double angle = tick * 0.2;
        double ringRadius = bodyRadius * 0.8 + Math.sin(tick * 0.15) * 0.2;
        for (int i = 0; i < 3; i++) {
            double offsetAngle = angle + i * (Math.PI * 2 / 3);
            double x = Math.cos(offsetAngle) * ringRadius;
            double z = Math.sin(offsetAngle) * ringRadius;
            Location aura = current.clone().add(x, 0.2, z);
            world.spawnParticle(Particle.DRAGON_BREATH, aura, 4, 0.1, 0.1, 0.1, 0.01);
            world.spawnParticle(Particle.SMOKE, aura, 6, 0.12, 0.12, 0.12, 0.02);
        }
    }

    private void spawnFlightEmbers(World world, Location current, double bodyRadius) {
        world.spawnParticle(Particle.ASH, current, 12, bodyRadius * 0.4, bodyRadius * 0.2, bodyRadius * 0.4, 0.01);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, current, 8, bodyRadius * 0.3, bodyRadius * 0.3, bodyRadius * 0.3, 0.01);
        Particle smoke = resolveParticle("SMOKE_LARGE", "LARGE_SMOKE", "SMOKE_NORMAL", "SMOKE");
        if (smoke != null) {
            world.spawnParticle(smoke, current, 6, bodyRadius * 0.3, bodyRadius * 0.2, bodyRadius * 0.3, 0.02);
        }
    }

    private void spawnImpactResidue(World world) {
        Particle smoke = resolveParticle("SMOKE_LARGE", "LARGE_SMOKE", "SMOKE_NORMAL", "SMOKE");
        if (smoke != null) {
            world.spawnParticle(smoke, impactLocation, 80, 3.5, 1.2, 3.5, 0.02);
        }
        world.spawnParticle(Particle.ASH, impactLocation, 120, 4.0, 1.0, 4.0, 0.01);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, impactLocation, 50, 2.5, 0.8, 2.5, 0.02);
    }

    private Particle resolveParticle(String... names) {
        for (String name : names) {
            try {
                return Particle.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Try next fallback.
            }
        }
        return null;
    }

    private List<Vector> buildMeteorOffsets(double bodyRadius) {
        double offset = Math.max(0.6, bodyRadius * 0.55);
        double top = bodyRadius * 0.6;
        return List.of(
            new Vector(0, 0, 0),
            new Vector(offset, 0, 0),
            new Vector(-offset, 0, 0),
            new Vector(0, 0, offset),
            new Vector(0, 0, -offset),
            new Vector(offset * 0.7, 0, offset * 0.7),
            new Vector(-offset * 0.7, 0, -offset * 0.7),
            new Vector(0, top, 0),
            new Vector(0, -top * 0.4, 0)
        );
    }

    private List<ArmorStand> spawnMeteorPieces(World world, Location base, List<Vector> offsets, List<Material> materials) {
        List<ArmorStand> pieces = new ArrayList<>();
        Random random = new Random();
        for (Vector offset : offsets) {
            Location spawn = base.clone().add(offset);
            ArmorStand meteor = (ArmorStand) world.spawnEntity(spawn, EntityType.ARMOR_STAND);
            meteor.setInvisible(true);
            meteor.setMarker(true);
            meteor.setGravity(false);
            meteor.getEquipment().setHelmet(new ItemStack(materials.get(random.nextInt(materials.size()))));
            pieces.add(meteor);
        }
        return pieces;
    }

    private void teleportMeteorPieces(Location base, List<ArmorStand> pieces, List<Vector> offsets) {
        for (int i = 0; i < pieces.size(); i++) {
            ArmorStand piece = pieces.get(i);
            Vector offset = offsets.get(i);
            piece.teleport(base.clone().add(offset));
        }
    }

    private void removeMeteorPieces(List<ArmorStand> pieces) {
        for (ArmorStand piece : pieces) {
            piece.remove();
        }
    }

    private World resolveWorld(CommandSender sender, boolean immediate) {
        if (immediate && sender instanceof Player player) {
            return player.getWorld();
        }
        return ConfigHelper.defaultWorld();
    }

    private int resolveTargetCoordinate(CommandSender sender, int spawnCoordinate, Random random, boolean immediate, boolean isX) {
        if (immediate && sender instanceof Player player) {
            return isX ? player.getLocation().getBlockX() : player.getLocation().getBlockZ();
        }
        return spawnCoordinate + random.nextInt(401) - 200;
    }

    private List<Material> readMaterials(List<String> names, List<Material> fallback) {
        List<Material> materials = new ArrayList<>();
        for (String name : names) {
            try {
                materials.add(Material.valueOf(name));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown crater material: " + name);
            }
        }
        return materials.isEmpty() ? fallback : materials;
    }
}
