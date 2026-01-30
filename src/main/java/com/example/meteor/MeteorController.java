package com.example.meteor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import net.kyori.adventure.text.Component;

public class MeteorController {
    private final MeteorPlugin plugin;
    private final MeteorStorage storage;
    private final Map<UUID, Integer> radiationLevels = new HashMap<>();
    private final Map<UUID, Integer> damageCounterFive = new HashMap<>();
    private final Map<UUID, Integer> damageCounterThree = new HashMap<>();
    private final List<BukkitTask> scheduledTasks = new ArrayList<>();

    private MeteorZone zone;
    private BukkitRunnable flightTask;
    private BukkitRunnable exposureTask;
    private BukkitRunnable damageTask;
    private BukkitRunnable visualTask;
    private BukkitRunnable sculkTask;
    private BukkitRunnable domeTask;
    private List<Vector> domeOffsets;

    public MeteorController(MeteorPlugin plugin, MeteorStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.radiationLevels.putAll(storage.loadRadiationLevels());
        resumeZone();
    }

    public void startMeteor(Location target, boolean immediate) {
        stopMeteor();

        World world = target.getWorld();
        if (world == null) {
            return;
        }
        int highestY = world.getHighestBlockYAt(target.getBlockX(), target.getBlockZ());
        Location center = new Location(world, target.getX(), highestY, target.getZ());

        long now = System.currentTimeMillis();
        int countdownMinutes = plugin.getConfig().getInt("meteor.countdown-minutes", 60);
        long countdownMillis = immediate ? 0 : countdownMinutes * 60L * 1000L;
        long impactTime = now + countdownMillis;

        zone = new MeteorZone(center, impactTime, Stage.SCHEDULED, false, null);
        storage.saveZone(zone.toStoredZone());

        broadcastWarning(center, countdownMinutes, immediate);

        if (!immediate) {
            scheduleCountdowns(center, impactTime);
        }
        scheduleFlight(center, impactTime, immediate);
    }

    public void stopMeteor() {
        cancelTasks();
        removeCoreBlock();
        zone = null;
        storage.clearZone();
    }

    public void start(Location target) {
        startMeteor(target, false);
    }

    public void stop() {
        stopMeteor();
    }

    public boolean checkDome(CommandSender sender) {
        if (zone == null || zone.stage().compareTo(Stage.IMPACTED) < 0 || zone.safe()) {
            return false;
        }
        boolean complete = isDomeComplete(zone.center());
        if (complete) {
            sealRadiation();
            if (sender != null) {
                sender.sendMessage("§aРадиация изолирована!");
            }
        }
        return complete;
    }

    public Location getCoreLocation() {
        return zone == null ? null : zone.coreLocation();
    }

    public boolean isMeteorSafe() {
        return zone != null && zone.safe();
    }

    public boolean canHarvestMeteor(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        return tool != null && tool.containsEnchantment(Enchantment.SILK_TOUCH);
    }

    public void handleMeteorHarvest(org.bukkit.event.block.BlockBreakEvent event) {
        Location core = zone == null ? null : zone.coreLocation();
        if (core == null) {
            return;
        }
        event.setCancelled(true);
        event.getBlock().setType(Material.AIR, false);
        ItemStack drop = createCoreItem();
        core.getWorld().dropItemNaturally(core, drop);
    }

    public void handleRadiationDeath(Player player) {
        if (zone == null || zone.stage() == Stage.SEALED) {
            return;
        }
        Integer level = radiationLevels.get(player.getUniqueId());
        if (level == null || level < 3) {
            return;
        }
        Location location = player.getLocation();
        if (location.getWorld() == null || zone.center().getWorld() == null) {
            return;
        }
        if (!location.getWorld().equals(zone.center().getWorld())) {
            return;
        }
        double radius = plugin.getConfig().getDouble("meteor.radiation.radius", 40.0);
        if (location.distanceSquared(zone.center()) > radius * radius) {
            return;
        }
        var zombie = location.getWorld().spawnEntity(location, EntityType.ZOMBIE);
        zombie.customName(Component.text("Заражённый зомби"));
        zombie.setCustomNameVisible(true);
    }

    private void resumeZone() {
        Optional<MeteorStorage.StoredZone> stored = storage.loadZone();
        if (stored.isEmpty()) {
            return;
        }
        MeteorStorage.StoredZone data = stored.get();
        World world = Bukkit.getWorld(data.world());
        if (world == null) {
            return;
        }
        Location center = new Location(world, data.x(), data.y(), data.z());
        Stage stage = Stage.fromString(data.stage());
        Location core = stage == Stage.IMPACTED || stage == Stage.SEALED ? center : null;
        zone = new MeteorZone(center, data.impactTime(), stage, stage == Stage.SEALED, core);
        long now = System.currentTimeMillis();
        if (stage == Stage.SCHEDULED || stage == Stage.FALLING) {
            if (now >= data.impactTime()) {
                impact(center);
            } else {
                scheduleCountdowns(center, data.impactTime());
                scheduleFlight(center, data.impactTime(), false);
            }
        } else if (stage == Stage.IMPACTED) {
            startRadiationTasks();
            scheduleDomeChecks();
        }
    }

    private void broadcastWarning(Location center, int countdownMinutes, boolean immediate) {
        String title = plugin.getConfig().getString("meteor.warning-title", "§6☄ Метеорит приближается!");
        String subtitleTemplate = plugin.getConfig().getString(
            "meteor.warning-subtitle",
            "Падение через %time% в районе X:%x% Z:%z%"
        );
        String timeText = immediate ? "10 секунд" : formatCountdown(countdownMinutes);
        String subtitle = subtitleTemplate
            .replace("%time%", timeText)
            .replace("%x%", String.valueOf(center.getBlockX()))
            .replace("%z%", String.valueOf(center.getBlockZ()));

        String chatMessage = title + " " + subtitle;
        Bukkit.broadcastMessage(chatMessage);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(title, subtitle, 10, 80, 20);
        }
    }

    private void scheduleCountdowns(Location center, long impactTime) {
        List<Integer> reminderMinutes = plugin.getConfig().getIntegerList("meteor.reminder-times-minutes");
        if (reminderMinutes.isEmpty()) {
            reminderMinutes = List.of(45, 30, 15, 5);
        }
        long now = System.currentTimeMillis();
        for (int minutes : reminderMinutes) {
            long reminderTime = impactTime - minutes * 60L * 1000L;
            long delayTicks = Math.max(0, (reminderTime - now) / 50L);
            if (delayTicks <= 0) {
                continue;
            }
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                String message = "§6☄ До падения метеорита: " + minutes + " мин. (X:" +
                    center.getBlockX() + ", Z:" + center.getBlockZ() + ")";
                Bukkit.broadcastMessage(message);
            }, delayTicks);
            scheduledTasks.add(task);
        }
    }

    private void scheduleFlight(Location center, long impactTime, boolean immediate) {
        int flightSeconds = plugin.getConfig().getInt("meteor.flight.duration-seconds", 10);
        long flightMillis = flightSeconds * 1000L;
        long now = System.currentTimeMillis();
        long startMillis = immediate ? now : Math.max(now, impactTime - flightMillis);
        long delayTicks = Math.max(0, (startMillis - now) / 50L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> startFlight(center), delayTicks);
        scheduledTasks.add(task);
    }

    private void startFlight(Location center) {
        if (zone == null) {
            return;
        }
        zone = zone.withStage(Stage.FALLING);
        storage.saveZone(zone.toStoredZone());

        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int flightHeight = plugin.getConfig().getInt("meteor.flight.height", 200);
        int flightSeconds = plugin.getConfig().getInt("meteor.flight.duration-seconds", 10);
        int totalTicks = Math.max(1, flightSeconds * 20);

        Location start = new Location(world, center.getX(), flightHeight, center.getZ());
        DragonFireball fireball = (DragonFireball) world.spawnEntity(start, EntityType.DRAGON_FIREBALL);
        fireball.setIsIncendiary(false);
        fireball.setYield(0);

        Vector step = center.toVector().subtract(start.toVector()).multiply(1.0 / totalTicks);

        List<Particle> trailParticles = resolveParticles(
            plugin.getConfig().getStringList("meteor.flight.trail-particles"),
            defaultTrailParticles()
        );
        Sound whistle = resolveSound(plugin.getConfig().getString("meteor.flight.whistle-sound", "ENTITY_ARROW_SHOOT"));
        int flashHeight = plugin.getConfig().getInt("meteor.flight.flash-y", 100);

        flightTask = new BukkitRunnable() {
            int tick = 0;
            boolean flashed = false;

            @Override
            public void run() {
                if (!fireball.isValid()) {
                    cancel();
                    return;
                }
                if (tick >= totalTicks) {
                    fireball.remove();
                    cancel();
                    impact(center);
                    return;
                }
                Location current = fireball.getLocation().add(step);
                fireball.teleport(current);
                spawnTrail(world, current, trailParticles);
                float pitch = 0.5f + (1.5f * tick / totalTicks);
                world.playSound(current, whistle, 1.2f, pitch);
                if (!flashed && current.getY() < flashHeight) {
                    Particle flash = resolveParticle("FLASH", "EXPLOSION_NORMAL", "EXPLOSION");
                    if (flash != null) {
                        world.spawnParticle(flash, current, 4, 0.6, 0.6, 0.6, 0.0);
                    }
                    flashed = true;
                }
                tick++;
            }

            @Override
            public void cancel() {
                super.cancel();
                flightTask = null;
            }
        };
        flightTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void impact(Location center) {
        if (zone == null) {
            return;
        }
        zone = zone.withStage(Stage.IMPACTED);
        storage.saveZone(zone.toStoredZone());

        World world = center.getWorld();
        if (world == null) {
            return;
        }
        Sound impactSound = resolveSound(plugin.getConfig().getString("meteor.impact.sound", "ENTITY_GENERIC_EXPLODE"));
        world.playSound(center, impactSound, 2.0f, 0.8f);
        startImpactEffects(center);
        float explosionPower = (float) plugin.getConfig().getDouble("meteor.impact.explosion-power", 4.0);
        boolean explosionFire = plugin.getConfig().getBoolean("meteor.impact.explosion-fire", false);
        boolean explosionBreakBlocks = plugin.getConfig().getBoolean("meteor.impact.explosion-break-blocks", false);
        if (explosionPower > 0.0f) {
            world.createExplosion(center, explosionPower, explosionFire, explosionBreakBlocks);
        }

        createCrater(center);
        applyImpactShake(center);
        startSculkSpread(center, explosionPower);
        startRadiationTasks();
        scheduleDomeChecks();
    }

    private void startImpactEffects(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int flashCount = plugin.getConfig().getInt("meteor.impact.flash-count", 8);
        double flashRadius = plugin.getConfig().getDouble("meteor.impact.flash-radius", 60.0);
        int flashBlindness = plugin.getConfig().getInt("meteor.impact.flash-blindness-ticks", 20);
        double shockwaveRadius = plugin.getConfig().getDouble("meteor.impact.shockwave-radius", 18.0);
        int shockwaveSteps = plugin.getConfig().getInt("meteor.impact.shockwave-steps", 12);
        int shockwavePoints = plugin.getConfig().getInt("meteor.impact.shockwave-points", 40);
        Particle flash = resolveParticle("FLASH", "EXPLOSION_NORMAL", "EXPLOSION");
        Particle explosion = resolveParticle("EXPLOSION", "EXPLOSION_HUGE", "EXPLOSION_LARGE");
        Particle smoke = resolveParticle("SMOKE_LARGE", "SMOKE_NORMAL", "CLOUD");
        Particle shockwaveParticle = resolveParticle("CLOUD", "SMOKE_LARGE", "SMOKE_NORMAL");
        PotionEffectType blindness = resolvePotionEffectType("BLINDNESS");

        if (flash != null) {
            world.spawnParticle(flash, center, flashCount, 2.5, 2.5, 2.5, 0.0);
        }
        if (explosion != null) {
            world.spawnParticle(explosion, center, 12, 2.2, 1.5, 2.2, 0.1);
        }
        if (smoke != null) {
            world.spawnParticle(smoke, center, 40, 3.5, 1.8, 3.5, 0.05);
        }

        for (Player player : world.getPlayers()) {
            if (!isWithinRadius(player.getLocation(), center, flashRadius)) {
                continue;
            }
            if (flash != null) {
                player.spawnParticle(flash, player.getEyeLocation(), flashCount, 0.6, 0.6, 0.6, 0.0);
            }
            if (blindness != null && flashBlindness > 0) {
                player.addPotionEffect(new PotionEffect(blindness, flashBlindness, 0, true, false, true));
            }
        }

        if (shockwaveParticle != null && shockwaveSteps > 0 && shockwavePoints > 0) {
            BukkitTask task = new BukkitRunnable() {
                int step = 0;

                @Override
                public void run() {
                    double currentRadius = shockwaveRadius * (step + 1) / shockwaveSteps;
                    for (int i = 0; i < shockwavePoints; i++) {
                        double angle = (2 * Math.PI / shockwavePoints) * i;
                        double x = Math.cos(angle) * currentRadius;
                        double z = Math.sin(angle) * currentRadius;
                        Location point = center.clone().add(x, 0.2, z);
                        world.spawnParticle(shockwaveParticle, point, 2, 0.2, 0.05, 0.2, 0.0);
                    }
                    step++;
                    if (step >= shockwaveSteps) {
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 0L, 2L);
            scheduledTasks.add(task);
        }
    }

    private void createCrater(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int size = plugin.getConfig().getInt("meteor.impact.crater-size", 5);
        int half = size / 2;
        List<Material> materials = resolveMaterials(
            plugin.getConfig().getStringList("meteor.impact.crater-materials"),
            List.of(Material.OBSIDIAN, Material.CRYING_OBSIDIAN)
        );
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) {
                Block block = world.getBlockAt(center.getBlockX() + x, center.getBlockY(), center.getBlockZ() + z);
                Material chosen = materials.get((int) (Math.random() * materials.size()));
                block.setType(chosen, false);
            }
        }
        Material coreMaterial = resolveCoreMaterial();
        Block coreBlock = world.getBlockAt(center.getBlockX(), center.getBlockY(), center.getBlockZ());
        coreBlock.setType(coreMaterial, false);
        zone = zone.withCore(coreBlock.getLocation());
        storage.saveZone(zone.toStoredZone());
    }

    private void startRadiationTasks() {
        if (zone == null) {
            return;
        }
        cancelRadiationTasks();
        double radius = plugin.getConfig().getDouble("meteor.radiation.radius", 40.0);
        int exposureSeconds = plugin.getConfig().getInt("meteor.radiation.progression-seconds", 20);
        int damageIntervalFive = plugin.getConfig().getInt("meteor.radiation.damage-half-heart-interval", 5);
        int damageIntervalThree = plugin.getConfig().getInt("meteor.radiation.damage-heart-interval", 3);

        exposureTask = new BukkitRunnable() {
            @Override
            public void run() {
                applyRadiationExposure(zone.center(), radius);
            }

            @Override
            public void cancel() {
                super.cancel();
                exposureTask = null;
            }
        };
        exposureTask.runTaskTimer(plugin, 0L, exposureSeconds * 20L);

        damageTask = new BukkitRunnable() {
            @Override
            public void run() {
                applyRadiationDamage(zone.center(), radius, damageIntervalFive, damageIntervalThree);
            }

            @Override
            public void cancel() {
                super.cancel();
                damageTask = null;
            }
        };
        damageTask.runTaskTimer(plugin, 0L, 20L);

        visualTask = new BukkitRunnable() {
            @Override
            public void run() {
                spawnRadiationParticles(zone.center(), radius);
            }

            @Override
            public void cancel() {
                super.cancel();
                visualTask = null;
            }
        };
        visualTask.runTaskTimer(plugin, 0L, 20L);
    }

    private void startSculkSpread(Location center, float explosionPower) {
        boolean enabled = plugin.getConfig().getBoolean("meteor.radiation.sculk.enabled", true);
        double minExplosionPower = plugin.getConfig().getDouble("meteor.radiation.sculk.min-explosion-power", 6.0);
        if (!enabled || explosionPower < minExplosionPower) {
            return;
        }
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int radius = plugin.getConfig().getInt("meteor.radiation.sculk.radius", 18);
        int blocksPerStep = plugin.getConfig().getInt("meteor.radiation.sculk.blocks-per-step", 6);
        int intervalSeconds = plugin.getConfig().getInt("meteor.radiation.sculk.interval-seconds", 8);
        List<Material> replaceable = resolveMaterials(
            plugin.getConfig().getStringList("meteor.radiation.sculk.replaceable-blocks"),
            List.of(
                Material.STONE,
                Material.DEEPSLATE,
                Material.DIRT,
                Material.GRASS_BLOCK,
                Material.ANDESITE,
                Material.DIORITE,
                Material.GRANITE,
                Material.TUFF
            )
        );

        List<Location> targets = new ArrayList<>();
        int originX = center.getBlockX();
        int originZ = center.getBlockZ();
        int radiusSquared = radius * radius;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int distance = x * x + z * z;
                if (distance > radiusSquared) {
                    continue;
                }
                int blockX = originX + x;
                int blockZ = originZ + z;
                int highestY = world.getHighestBlockYAt(blockX, blockZ);
                Block block = world.getBlockAt(blockX, highestY, blockZ);
                if (replaceable.contains(block.getType())) {
                    targets.add(block.getLocation());
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Collections.shuffle(targets);
        if (sculkTask != null) {
            sculkTask.cancel();
        }
        sculkTask = new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                int placed = 0;
                while (placed < blocksPerStep && index < targets.size()) {
                    Location location = targets.get(index++);
                    if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(location);
                    if (!replaceable.contains(block.getType())) {
                        continue;
                    }
                    block.setType(Material.SCULK, false);
                    Block above = block.getRelative(0, 1, 0);
                    if (above.getType() == Material.AIR && Math.random() < 0.3) {
                        above.setType(Material.SCULK_VEIN, false);
                    }
                    placed++;
                }
                if (index >= targets.size()) {
                    cancel();
                }
            }

            @Override
            public void cancel() {
                super.cancel();
                sculkTask = null;
            }
        };
        sculkTask.runTaskTimer(plugin, 20L, intervalSeconds * 20L);
    }

    private void applyRadiationExposure(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        PotionEffectType nausea = resolvePotionEffectType("NAUSEA", "CONFUSION");
        for (Player player : world.getPlayers()) {
            if (!isWithinRadius(player.getLocation(), center, radius)) {
                continue;
            }
            int level = radiationLevels.getOrDefault(player.getUniqueId(), 0);
            if (level < 3) {
                level++;
                radiationLevels.put(player.getUniqueId(), level);
                storage.saveRadiationLevel(player.getUniqueId(), level);
            }
            applyEffectsForLevel(player, level, nausea);
        }
    }

    private void applyEffectsForLevel(Player player, int level, PotionEffectType nausea) {
        if (level >= 1) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 25 * 20, 0, true, false, true));
            if (nausea != null) {
                player.addPotionEffect(new PotionEffect(nausea, 25 * 20, 0, true, false, true));
            }
        }
        if (level >= 2) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 25 * 20, 1, true, false, true));
        }
        if (level >= 3) {
            PotionEffectType slow = resolvePotionEffectType("SLOW", "SLOWNESS");
            if (slow != null) {
                player.addPotionEffect(new PotionEffect(slow, 25 * 20, 0, true, false, true));
            }
        }
    }

    private void applyRadiationDamage(Location center, double radius, int intervalFive, int intervalThree) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (!isWithinRadius(player.getLocation(), center, radius)) {
                continue;
            }
            int level = radiationLevels.getOrDefault(player.getUniqueId(), 0);
            if (level < 2) {
                continue;
            }
            UUID uuid = player.getUniqueId();
            if (level >= 2) {
                int counter = damageCounterFive.getOrDefault(uuid, 0) + 1;
                if (counter >= intervalFive) {
                    player.damage(1.0);
                    counter = 0;
                }
                damageCounterFive.put(uuid, counter);
            }
            if (level >= 3) {
                int counter = damageCounterThree.getOrDefault(uuid, 0) + 1;
                if (counter >= intervalThree) {
                    player.damage(2.0);
                    counter = 0;
                }
                damageCounterThree.put(uuid, counter);
            }
        }
    }

    private void spawnRadiationParticles(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        Particle happy = resolveParticle("VILLAGER_HAPPY", "HAPPY_VILLAGER");
        if (happy != null) {
            world.spawnParticle(happy, center, 30, radius, 2.0, radius, 0.05);
        }
        Particle ash = resolveParticle("ASH", "SMOKE_NORMAL");
        if (ash != null) {
            world.spawnParticle(ash, center, 40, radius, 2.5, radius, 0.03);
        }
        if (zone != null && zone.coreLocation() != null) {
            Location core = zone.coreLocation();
            List<Particle> coreParticles = resolveParticles(
                plugin.getConfig().getStringList("meteor.core.radiation-particles"),
                defaultCoreParticles()
            );
            for (Particle particle : coreParticles) {
                world.spawnParticle(particle, core, 18, 0.6, 0.8, 0.6, 0.02);
            }
        }
    }

    private void scheduleDomeChecks() {
        int intervalSeconds = plugin.getConfig().getInt("meteor.dome.check-interval-seconds", 60);
        domeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (zone == null || zone.safe()) {
                    return;
                }
                if (isDomeComplete(zone.center())) {
                    sealRadiation();
                }
            }

            @Override
            public void cancel() {
                super.cancel();
                domeTask = null;
            }
        };
        domeTask.runTaskTimer(plugin, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    private void sealRadiation() {
        if (zone == null) {
            return;
        }
        cancelRadiationTasks();
        zone = zone.withSafe(true).withStage(Stage.SEALED);
        storage.saveZone(zone.toStoredZone());
        Bukkit.broadcastMessage("§aРадиация изолирована!");
    }

    private boolean isDomeComplete(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return false;
        }
        int radius = plugin.getConfig().getInt("meteor.dome.radius", 15);
        List<String> glassNames = plugin.getConfig().getStringList("meteor.dome.glass-types");
        Set<Material> allowedGlass = new HashSet<>(resolveMaterials(glassNames, defaultGlassList()));
        for (Vector offset : getDomeOffsets(radius)) {
            int x = center.getBlockX() + offset.getBlockX();
            int y = center.getBlockY() + offset.getBlockY();
            int z = center.getBlockZ() + offset.getBlockZ();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return false;
            }
            Material type = world.getBlockAt(x, y, z).getType();
            if (!allowedGlass.contains(type)) {
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
        domeOffsets = Collections.unmodifiableList(offsets);
        return domeOffsets;
    }

    private void applyImpactShake(Location center) {
        double radius = plugin.getConfig().getDouble("meteor.impact.shake-radius", 50.0);
        int duration = plugin.getConfig().getInt("meteor.impact.shake-duration-ticks", 60);
        double intensity = plugin.getConfig().getDouble("meteor.impact.shake-intensity", 6.0);
        PotionEffectType nausea = resolvePotionEffectType("NAUSEA", "CONFUSION");
        PotionEffectType slow = resolvePotionEffectType("SLOW");
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        BukkitTask task = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                for (Player player : world.getPlayers()) {
                    if (!isWithinRadius(player.getLocation(), center, radius)) {
                        continue;
                    }
                    if (nausea != null) {
                        player.addPotionEffect(new PotionEffect(nausea, duration, 0, true, false, true));
                    }
                    if (slow != null) {
                        player.addPotionEffect(new PotionEffect(slow, duration, 0, true, false, true));
                    }
                    float yawJitter = (float) ((Math.random() - 0.5) * intensity);
                    float pitchJitter = (float) ((Math.random() - 0.5) * intensity);
                    Location view = player.getLocation().clone();
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
        scheduledTasks.add(task);
    }

    private boolean isWithinRadius(Location location, Location center, double radius) {
        if (location.getWorld() == null || center.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().equals(center.getWorld())) {
            return false;
        }
        return location.distanceSquared(center) <= radius * radius;
    }

    private void spawnTrail(World world, Location location, List<Particle> particles) {
        for (Particle particle : particles) {
            world.spawnParticle(particle, location, 12, 0.6, 0.6, 0.6, 0.05);
        }
    }

    private List<Particle> resolveParticles(List<String> names, List<Particle> fallback) {
        List<Particle> particles = new ArrayList<>();
        for (String name : names) {
            try {
                particles.add(Particle.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid entries.
            }
        }
        return particles.isEmpty() ? fallback : particles;
    }

    private List<Material> resolveMaterials(List<String> names, List<Material> fallback) {
        List<Material> materials = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                materials.add(material);
            }
        }
        return materials.isEmpty() ? fallback : materials;
    }

    private List<Material> defaultGlassList() {
        return List.copyOf(EnumSet.of(
            Material.GLASS,
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
    }

    private List<Particle> defaultTrailParticles() {
        List<Particle> particles = new ArrayList<>(List.of(Particle.FLAME));
        Particle smoke = resolveParticle("SMOKE", "SMOKE_NORMAL", "CAMPFIRE_COSY_SMOKE");
        if (smoke != null) {
            particles.add(smoke);
        }
        Particle fireworks = resolveParticle("FIREWORKS_SPARK", "FIREWORK", "FIREWORK_ROCKET");
        if (fireworks != null) {
            particles.add(fireworks);
        }
        return particles;
    }

    private List<Particle> defaultCoreParticles() {
        List<Particle> particles = new ArrayList<>();
        Particle rod = resolveParticle("END_ROD");
        if (rod != null) {
            particles.add(rod);
        }
        Particle portal = resolveParticle("PORTAL", "SPELL_WITCH");
        if (portal != null) {
            particles.add(portal);
        }
        if (particles.isEmpty()) {
            Particle fallback = resolveParticle("SMOKE_NORMAL", "SMOKE", "CAMPFIRE_COSY_SMOKE");
            return fallback != null ? List.of(fallback) : List.of(Particle.FLAME);
        }
        return particles;
    }

    private Material resolveCoreMaterial() {
        String materialName = plugin.getConfig().getString("meteor.core.block-material", "CRYING_OBSIDIAN");
        Material material = Material.matchMaterial(materialName);
        return material != null ? material : Material.CRYING_OBSIDIAN;
    }

    private Particle resolveParticle(String... names) {
        for (String name : names) {
            try {
                return Particle.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Ignore.
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

    private ItemStack createCoreItem() {
        ItemStack stack = new ItemStack(Material.OBSIDIAN);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String displayName = plugin.getConfig().getString("meteor.core.display-name", "§aРадиоактивный метеорит");
            meta.setDisplayName(displayName);
            int model = plugin.getConfig().getInt("meteor.core.custom-model-data", 0);
            if (model > 0) {
                meta.setCustomModelData(model);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String formatCountdown(int minutes) {
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours == 1 ? "1 час" : hours + " часов";
        }
        return minutes + " минут";
    }

    private void cancelTasks() {
        cancelRadiationTasks();
        if (flightTask != null) {
            flightTask.cancel();
        }
        if (domeTask != null) {
            domeTask.cancel();
        }
        for (BukkitTask task : scheduledTasks) {
            task.cancel();
        }
        scheduledTasks.clear();
    }

    private void cancelRadiationTasks() {
        if (exposureTask != null) {
            exposureTask.cancel();
        }
        if (damageTask != null) {
            damageTask.cancel();
        }
        if (visualTask != null) {
            visualTask.cancel();
        }
        if (sculkTask != null) {
            sculkTask.cancel();
        }
    }

    private void removeCoreBlock() {
        if (zone == null || zone.coreLocation() == null) {
            return;
        }
        Location core = zone.coreLocation();
        if (core.getWorld() == null) {
            return;
        }
        core.getWorld().getBlockAt(core).setType(Material.AIR, false);
    }

    private record MeteorZone(Location center, long impactTime, Stage stage, boolean safe, Location coreLocation) {
        MeteorZone withStage(Stage newStage) {
            return new MeteorZone(center, impactTime, newStage, safe, coreLocation);
        }

        MeteorZone withSafe(boolean newSafe) {
            return new MeteorZone(center, impactTime, stage, newSafe, coreLocation);
        }

        MeteorZone withCore(Location core) {
            return new MeteorZone(center, impactTime, stage, safe, core);
        }

        MeteorStorage.StoredZone toStoredZone() {
            return new MeteorStorage.StoredZone(
                center.getWorld() == null ? "" : center.getWorld().getName(),
                center.getX(),
                center.getY(),
                center.getZ(),
                impactTime,
                stage.name()
            );
        }
    }

    private enum Stage {
        SCHEDULED,
        FALLING,
        IMPACTED,
        SEALED;

        static Stage fromString(String value) {
            if (value == null) {
                return SCHEDULED;
            }
            try {
                return Stage.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return SCHEDULED;
            }
        }
    }
}
